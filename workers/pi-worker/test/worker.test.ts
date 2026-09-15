import assert from "node:assert/strict";
import { test } from "node:test";
import { randomUUID } from "node:crypto";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { SettingsManager } from "@earendil-works/pi-coding-agent";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { AssistantMessage } from "@earendil-works/pi-ai";
import { BackendClient } from "../src/backend-client.js";
import { loadConfig } from "../src/config.js";
import type { Config } from "../src/config.js";
import { isolatedLoader, systemPrompt, createPiRuntime, PiSession } from "../src/pi-session.js";
import { ScriptedSession } from "../src/scripted-session.js";
import { executeRun } from "../src/worker.js";
import { ApprovalPaused, BackendError, WorkerError, validateAssignment } from "../src/protocol.js";
import type { Assignment, Observation, Report, ToolName } from "../src/protocol.js";

const config: Config = { apiUrl: "http://localhost:8080", workerToken: "test-worker-token-with-32-characters",
  mode: "scripted", provider: "", model: "", apiKey: "" };
function assignment(): Assignment {
  return { schemaVersion: 1, runId: randomUUID(), traceId: randomUUID(), question: "Inspect the documents",
    documentIds: [randomUUID()], claimToken: "claim-token-for-test", executionMode: "scripted",
    provider: "scripted", model: "fixtures-v1", deadlineAt: new Date(Date.now() + 60_000).toISOString(),
    leaseSeconds: 45, maxTools: 30, maxRounds: 12, promptVersion: "diagnostics-v1" };
}
function observation(invocationId = randomUUID()): Observation {
  return { schemaVersion: 1, invocationId, observationId: randomUUID(), status: "SUCCEEDED",
    observedAt: new Date().toISOString(), data: { condition: "CHUNKING_REQUIRED" } };
}

test("scripted mode uses both tools, preserves provenance, and labels itself", async () => {
  const run = assignment();
  const names: ToolName[] = [];
  const refs: string[] = [];
  const report = await new ScriptedSession().report(run, async name => {
    names.push(name);
    const result = observation();
    refs.push(result.observationId);
    return result;
  }, new AbortController().signal);
  assert.deepEqual(names, ["inspect_document", "list_ingestion_jobs"]);
  assert.deepEqual(report.findings[0].observationIds, refs);
  assert.match(report.summary, /no model was called/);
  assert.equal(report.findings[0].proposedNextAction, "CHUNK");
});

test("scripted failure does not fabricate a report", async () => {
  await assert.rejects(new ScriptedSession().report(assignment(), async () => ({ ...observation(), status: "FAILED" }),
    new AbortController().signal), (error: unknown) => error instanceof WorkerError && error.code === "TOOL_FAILED");
});

test("lost POST response is resolved by reading the same invocation", async () => {
  const run = assignment();
  let saved: Observation;
  let posts = 0;
  const backend = new BackendClient(config, (async (input, options) => {
    assert.equal(options?.redirect, "error");
    assert.equal((options?.headers as Record<string, string>)["X-Claim-Token"], run.claimToken);
    if (options?.method === "POST") {
      posts++;
      saved = observation(JSON.parse(String(options.body)).invocationId);
      throw new TypeError("simulated lost response");
    }
    assert.ok(String(input).endsWith(saved.invocationId));
    return Response.json(saved);
  }) as typeof fetch);
  const result = await backend.tool(run, "inspect_document", run.documentIds[0], new AbortController().signal);
  assert.equal(posts, 1);
  assert.equal(result.observationId, saved!.observationId);
});

test("request never received can be resent with the same identity", async () => {
  const ids: string[] = [];
  const backend = new BackendClient(config, (async (_input, options) => {
    if (options?.method === "GET") { return Response.json({ code: "NOT_FOUND" }, { status: 404 }); }
    const id = JSON.parse(String(options?.body)).invocationId;
    ids.push(id);
    if (ids.length === 1) { throw new TypeError("not delivered"); }
    return Response.json(observation(id));
  }) as typeof fetch);
  const run = assignment();
  await backend.tool(run, "inspect_document", run.documentIds[0], new AbortController().signal);
  assert.equal(ids.length, 2);
  assert.equal(ids[0], ids[1]);
});

test("transient read retry has a new identity and links to the failed invocation", async () => {
  const bodies: Array<Record<string, string>> = [];
  const backend = new BackendClient(config, (async (_input, options) => {
    const body = JSON.parse(String(options?.body));
    bodies.push(body);
    return Response.json({ ...observation(body.invocationId), status: "FAILED",
      error: { code: "TRANSIENT_DEPENDENCY", message: "unavailable", retryable: true } });
  }) as typeof fetch);
  const run = assignment();
  const result = await backend.tool(run, "inspect_document", run.documentIds[0], new AbortController().signal);
  assert.equal(result.status, "FAILED");
  assert.equal(bodies.length, 2);
  assert.notEqual(bodies[0].invocationId, bodies[1].invocationId);
  assert.equal(bodies[1].retryOf, bodies[0].invocationId);
});

test("out-of-scope document is rejected before any HTTP call", async () => {
  const backend = new BackendClient(config, (async () => { assert.fail("must not call backend"); }) as typeof fetch);
  await assert.rejects(backend.tool(assignment(), "inspect_document", randomUUID(), new AbortController().signal));
});

class TestBackend extends BackendClient {
  completed: unknown[] = [];
  failure?: string;
  heartbeats = 0;
  rejectFirst = false;
  constructor() { super(config); }
  override async heartbeat(): Promise<void> { this.heartbeats++; }
  override async tool(_run: Assignment, _name: ToolName): Promise<Observation> { return observation(); }
  override async complete(_run: Assignment, report: unknown): Promise<void> {
    this.completed.push(report);
    if (this.rejectFirst && this.completed.length === 1) { throw new BackendError(400, "INVALID_INPUT"); }
  }
  override async fail(_run: Assignment, code: string): Promise<void> { this.failure = code; }
}

test("orchestrator repairs one rejected report and disposes its session", async () => {
  const backend = new TestBackend();
  backend.rejectFirst = true;
  let repairs = 0;
  let disposed = false;
  assert.equal(await executeRun(assignment(), backend, {
    report: async () => ({ bad: true }),
    repair: async () => { repairs++; return { repaired: true }; },
    dispose: async () => { disposed = true; },
  }, new AbortController().signal), true);
  assert.equal(repairs, 1);
  assert.equal(disposed, true);
  assert.equal(backend.completed.length, 2);
});

test("model error fails visibly without a scripted fallback", async () => {
  const backend = new TestBackend();
  assert.equal(await executeRun(assignment(), backend, {
    report: async () => { throw new WorkerError("MODEL_ERROR"); }, dispose: async () => {},
  }, new AbortController().signal), false);
  assert.equal(backend.failure, "MODEL_ERROR");
  assert.equal(backend.completed.length, 0);
});

test("aborted execution cannot publish success", async () => {
  const controller = new AbortController();
  controller.abort();
  const backend = new TestBackend();
  assert.equal(await executeRun(assignment(), backend, new ScriptedSession(), controller.signal), false);
  assert.equal(backend.completed.length, 0);
});

test("cancelled or uncertain approved writes stop without another model session or failure write", async () => {
  for (const status of ["CANCELLED", "RECOVERY_REQUIRED"]) {
    const backend = new TestBackend();
    const run = assignment();
    run.pendingApprovalId = randomUUID();
    backend.executeApproved = async () => ({ approvalId: run.pendingApprovalId!, executionId: randomUUID(),
      documentId: run.documentIds[0], action: "CHUNK", status: status === "CANCELLED" ? "SUCCEEDED" : "UNKNOWN",
      runStatus: status });
    let disposed = false;
    assert.equal(await executeRun(run, backend, {
      report: async () => { assert.fail("must not start reporting after controlled stop"); },
      dispose: async () => { disposed = true; },
    }, new AbortController().signal), false);
    assert.equal(disposed, true);
    assert.equal(backend.failure, undefined);
    assert.equal(backend.completed.length, 0);
  }
});

test("revoked claims and user cancellation are not reported as worker failures", async () => {
  for (const code of ["RUN_CANCELLED", "CLAIM_UNAUTHORIZED", "CLAIM_INACTIVE"]) {
    const backend = new TestBackend();
    backend.tool = async () => { throw new BackendError(409, code); };
    assert.equal(await executeRun(assignment(), backend, new ScriptedSession(), new AbortController().signal), false);
    assert.equal(backend.failure, undefined);
    assert.equal(backend.completed.length, 0);
  }
});

test("graceful worker shutdown leaves its lease for durable recovery", async () => {
  const backend = new TestBackend();
  const controller = new AbortController();
  const session = { report: async () => { controller.abort(); throw controller.signal.reason; }, dispose: async () => {} };
  assert.equal(await executeRun(assignment(), backend, session, controller.signal), false);
  assert.equal(backend.failure, undefined);
  assert.equal(backend.completed.length, 0);
});

test("recovered diagnostics never redispatch a completed action from continuation", async () => {
  const backend = new TestBackend();
  const run = assignment();
  run.recoveryCount = 1;
  run.toolsUsed = 3;
  run.roundsUsed = 2;
  run.continuation = { schemaVersion: 1, observations: [], approvals: [], actionResults: [{
    approvalId: randomUUID(), executionId: randomUUID(), documentId: run.documentIds[0], action: "CHUNK", status: "SUCCEEDED",
  }] };
  backend.executeApproved = async () => { assert.fail("no new pending approval means no mutation"); };
  assert.equal(await executeRun(run, backend, new ScriptedSession(), new AbortController().signal), true);
  assert.equal(run.toolsUsed, 3);
  assert.equal(run.roundsUsed, 2);
  assert.equal(backend.completed.length, 1);
  assert.throws(() => validateAssignment({ ...run, recoveryCount: 11 }));
});

test("configuration uses explicit .env with environment taking precedence", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nexus-config-test-"));
  try {
    const path = join(directory, ".env");
    await writeFile(path, `NEXUS_AGENT_WORKER_TOKEN=${config.workerToken}\nNEXUS_AGENT_WORKER_MODE=pi\n`);
    assert.equal(loadConfig({ NEXUS_AGENT_WORKER_MODE: "scripted" }, path).mode, "scripted");
    assert.throws(() => loadConfig({}, path), WorkerError);
    assert.throws(() => loadConfig({ NEXUS_AGENT_WORKER_MODE: "scripted", NEXUS_AGENT_API_URL: "https://example.com/redirect" }, path));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("resource loader ignores ambient extensions, skills, and context files", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nexus-loader-test-"));
  try {
    await mkdir(join(directory, ".pi", "extensions"), { recursive: true });
    await writeFile(join(directory, ".pi", "extensions", "bad.ts"), "throw new Error('extension should not execute');");
    await writeFile(join(directory, "AGENTS.md"), "Secret ambient instructions that must not enter the prompt.");
    const loader = isolatedLoader(directory, SettingsManager.inMemory({ packages: [] }));
    await loader.reload();
    assert.equal(loader.getExtensions().extensions.length, 0);
    assert.equal(loader.getSkills().skills.length, 0);
    assert.equal(loader.getAgentsFiles().agentsFiles.length, 0);
    assert.equal(loader.getSystemPrompt(), systemPrompt);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("invalid provider fails before a model request", async () => {
  await assert.rejects(createPiRuntime({ ...config, mode: "pi", provider: "invalid-provider", model: "missing", apiKey: "test-key" }),
    (error: unknown) => error instanceof WorkerError && error.code === "CONFIGURATION_ERROR");
});

test("assignment rejects invalid protocol and unbounded budgets", () => {
  assert.throws(() => validateAssignment({ ...assignment(), maxTools: 100000 }));
  assert.throws(() => validateAssignment({ ...assignment(), schemaVersion: 2 } as unknown as Assignment));
});

test("scripted report schema is JSON serializable and bounded", async () => {
  const report: Report = await new ScriptedSession().report(assignment(), async () => observation(), new AbortController().signal);
  assert.ok(Buffer.byteLength(JSON.stringify(report)) < 32768);
  assert.equal(report.schemaVersion, 1);
});

test("successful proposal releases session without completion or failure", async () => {
  const backend = new TestBackend();
  const run = assignment();
  run.question = "Approve processing: request missing processing";
  const names: ToolName[] = [];
  backend.tool = async (_run, name) => {
    names.push(name);
    return name === "propose_retry" ? { ...observation(), data: { approvalId: randomUUID(), status: "PENDING" } } : observation();
  };
  let disposed = false;
  const session = new ScriptedSession();
  session.dispose = async () => { disposed = true; };
  assert.equal(await executeRun(run, backend, session, new AbortController().signal), true);
  assert.deepEqual(names, ["inspect_document", "list_ingestion_jobs", "propose_retry"]);
  assert.equal(disposed, true);
  assert.equal(backend.completed.length, 0);
  assert.equal(backend.failure, undefined);
});

test("planned continuation executes stored approval before fresh diagnostic tools", async () => {
  const backend = new TestBackend();
  const run = assignment();
  run.pendingApprovalId = randomUUID();
  run.toolsUsed = 3;
  const saved = { approvalId: run.pendingApprovalId, executionId: randomUUID(), documentId: run.documentIds[0],
    action: "CHUNK", status: "PENDING" as const };
  run.continuation = { schemaVersion: 1, observations: [], approvals: [], actionResults: [saved] };
  const order: string[] = [];
  backend.executeApproved = async () => { order.push("java_execute"); return { ...saved, status: "SUCCEEDED" }; };
  backend.tool = async (_run, name) => { order.push(name); return observation(); };
  assert.equal(await executeRun(run, backend, new ScriptedSession(), new AbortController().signal), true);
  assert.deepEqual(order, ["java_execute", "inspect_document", "list_ingestion_jobs"]);
  assert.equal(run.continuation.actionResults[0].status, "SUCCEEDED");
});

test("remaining tool budget does not reset on continuation", async () => {
  const backend = new TestBackend();
  const run = assignment();
  run.toolsUsed = run.maxTools;
  assert.equal(await executeRun(run, backend, new ScriptedSession(), new AbortController().signal), false);
  assert.equal(backend.failure, "BUDGET_EXCEEDED");
});

test("lost proposal response retrieves the saved approval instead of proposing again", async () => {
  const run = assignment();
  let saved: Observation;
  let posts = 0;
  const approval = randomUUID();
  const backend = new BackendClient(config, (async (_input, options) => {
    if (options?.method === "POST") {
      posts++;
      const body = JSON.parse(String(options.body));
      assert.equal(body.arguments.action, "CHUNK");
      saved = { ...observation(body.invocationId), data: { approvalId: approval, status: "PENDING" } };
      throw new TypeError("lost response");
    }
    return Response.json(saved);
  }) as typeof fetch);
  const result = await backend.tool(run, "propose_retry", run.documentIds[0], new AbortController().signal,
    undefined, { action: "CHUNK", reason: "Missing chunks" });
  assert.equal(posts, 1);
  assert.equal(result.data?.approvalId, approval);
});

test("ambiguous approved execution reuses the immutable approval URL", async () => {
  const run = assignment();
  run.pendingApprovalId = randomUUID();
  const urls: string[] = [];
  const backend = new BackendClient(config, (async (input) => {
    urls.push(String(input));
    if (urls.length === 1) { throw new TypeError("response lost"); }
    return Response.json({ executionId: randomUUID(), approvalId: run.pendingApprovalId, documentId: run.documentIds[0],
      action: "CHUNK", status: "SUCCEEDED", ingestionJobId: randomUUID() });
  }) as typeof fetch);
  assert.equal((await backend.executeApproved(run, new AbortController().signal)).status, "SUCCEEDED");
  assert.equal(urls.length, 2);
  assert.equal(urls[0], urls[1]);
});

test("model-round transport retry reuses its reservation identity", async () => {
  const urls: string[] = [];
  const backend = new BackendClient(config, (async (input) => {
    urls.push(String(input));
    if (urls.length === 1) { throw new TypeError("response lost"); }
    return new Response(null, { status: 204 });
  }) as typeof fetch);
  await backend.reserveRound(assignment(), new AbortController().signal);
  assert.equal(urls.length, 2);
  assert.equal(urls[0], urls[1]);
});

test("scripted fixture requests only one approval at a time", async () => {
  const run = assignment();
  run.question = "Approve processing: these documents";
  run.documentIds.push(randomUUID());
  const names: ToolName[] = [];
  await assert.rejects(new ScriptedSession().report(run, async name => {
    names.push(name);
    if (name === "propose_retry") { throw new ApprovalPaused(randomUUID()); }
    return observation();
  }, new AbortController().signal), ApprovalPaused);
  assert.deepEqual(names, ["inspect_document", "list_ingestion_jobs", "propose_retry"]);
});

test("real Pi session stops its tool loop on approval without another model round", { timeout: 10000 }, async () => {
  const piConfig: Config = { ...config, mode: "pi", provider: "openai", model: "gpt-5.6-luna", apiKey: "offline-test-key" };
  const runtime = await createPiRuntime(piConfig);
  const run = { ...assignment(), executionMode: "pi" as const, provider: piConfig.provider, model: piConfig.model };
  const names: ToolName[] = ["inspect_document", "list_ingestion_jobs", "propose_retry"];
  let streamed = 0;
  let reserved = 0;
  const invoked: ToolName[] = [];
  // Exercise the real SDK session/tool loop, replacing only network model output.
  runtime.streamSimple = model => {
    const name = names[streamed++];
    assert.ok(name, "must not request another model response after proposal");
    const message: AssistantMessage = { role: "assistant", api: model.api, provider: model.provider, model: model.id,
      timestamp: Date.now(), stopReason: "toolUse", content: [{ type: "toolCall", id: randomUUID(), name,
        arguments: { documentId: run.documentIds[0], ...(name === "propose_retry" ? { action: "CHUNK", reason: "Missing chunks" } : {}) } }],
      usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
    const stream = createAssistantMessageEventStream();
    stream.push({ type: "done", reason: "toolUse", message });
    stream.end(message);
    return stream;
  };
  const session = new PiSession(piConfig, runtime);
  try {
    await assert.rejects(session.report(run, async name => {
      invoked.push(name);
      if (name === "propose_retry") { throw new ApprovalPaused(randomUUID()); }
      return observation();
    }, AbortSignal.timeout(5000), async () => { reserved++; }), ApprovalPaused);
    assert.deepEqual(invoked, names);
    assert.equal(streamed, 3);
    assert.equal(reserved, 3);
  } finally { await session.dispose(); }
});
