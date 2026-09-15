import { randomUUID } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import type { Config } from "./config.js";
import { BackendError, WorkerError, validateAssignment } from "./protocol.js";
import type { Assignment, Observation, ToolName, ProposalArguments, ActionResult } from "./protocol.js";

export class BackendClient {
  constructor(private readonly config: Config, private readonly fetcher: typeof fetch = fetch) {}

  private async request<T>(path: string, method: string, token: string, worker: boolean,
                           signal: AbortSignal, body?: unknown, timeoutMs = 15_000): Promise<T | undefined> {
    const response = await this.fetcher(`${this.config.apiUrl}/internal/agent-worker${path}`, {
      method, redirect: "error", signal: timeoutMs === 0 ? signal : AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]),
      headers: { "Content-Type": "application/json", [worker ? "X-Worker-Token" : "X-Claim-Token"]: token },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 204) { return undefined; }
    const reader = response.body?.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    if (reader) {
      while (true) {
        const { value, done } = await reader.read();
        if (done) { break; }
        length += value.length;
        if (length > 65_536) { await reader.cancel(); throw new WorkerError("WORKER_ERROR"); }
        chunks.push(value);
      }
    }
    const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!response.ok) { throw new BackendError(response.status, typeof data.code === "string" ? data.code : "BACKEND_ERROR"); }
    return data as T;
  }

  async claim(signal: AbortSignal): Promise<Assignment | undefined> {
    const assignment = await this.request<Assignment>("/claims", "POST", this.config.workerToken, true, signal);
    return assignment ? validateAssignment(assignment) : undefined;
  }

  heartbeat(run: Assignment, signal: AbortSignal): Promise<unknown> {
    return this.request(`/runs/${run.runId}/heartbeat`, "POST", run.claimToken, false, signal);
  }

  complete(run: Assignment, report: unknown, signal: AbortSignal): Promise<unknown> {
    return this.request(`/runs/${run.runId}/complete`, "POST", run.claimToken, false, signal, report);
  }

  fail(run: Assignment, code: string, signal: AbortSignal): Promise<unknown> {
    return this.request(`/runs/${run.runId}/fail`, "POST", run.claimToken, false, signal, { code });
  }

  async reserveRound(run: Assignment, signal: AbortSignal): Promise<void> {
    const path = `/runs/${run.runId}/model-rounds/${randomUUID()}`;
    try { await this.request(path, "POST", run.claimToken, false, signal); }
    catch (error) {
      if (error instanceof BackendError && error.status < 500) { throw error; }
      await this.request(path, "POST", run.claimToken, false, signal);
    }
  }

  async executeApproved(run: Assignment, signal: AbortSignal): Promise<ActionResult> {
    if (!run.pendingApprovalId) { throw new WorkerError("WORKER_ERROR"); }
    const path = `/runs/${run.runId}/approved-retries/${run.pendingApprovalId}`;
    // Re-delivery only reads the same durable execution once Java has reserved it.
    // It never manufactures another approval or invocation after an ambiguous response.
    for (;;) {
      let result: ActionResult | undefined;
      try {
        // The run deadline still applies; do not impose the short read/RPC timeout on ingestion.
        result = await this.request<ActionResult>(path, "POST", run.claimToken, false, signal, undefined, 0);
      }
      catch (error) {
        if (error instanceof BackendError && error.status < 500) { throw error; }
        signal.throwIfAborted();
      }
      if (result && result.status !== "RUNNING" && result.status !== "PENDING") { return result; }
      await delay(1000, undefined, { signal });
    }
  }

  async tool(run: Assignment, name: ToolName, documentId: string, signal: AbortSignal,
             retryOf?: string, proposal?: ProposalArguments): Promise<Observation> {
    if (!run.documentIds.includes(documentId)) { throw new WorkerError("TOOL_FAILED"); }
    const invocationId = randomUUID();
    const body = { schemaVersion: 1, invocationId, toolName: name, arguments: { documentId, ...proposal }, ...(retryOf ? { retryOf } : {}) };
    const path = `/runs/${run.runId}/tools`;
    let result: Observation | undefined;
    try {
      result = await this.request<Observation>(path, "POST", run.claimToken, false, signal, body);
    } catch (error) {
      if (error instanceof BackendError && error.status < 500) { throw error; }
      signal.throwIfAborted();
      try {
        result = await this.request<Observation>(`${path}/${invocationId}`, "GET", run.claimToken, false, signal);
      } catch (lookupError) {
        if (!(lookupError instanceof BackendError) || lookupError.status !== 404) { throw lookupError; }
        // The request may never have arrived. Reuse its identity, never create a duplicate.
        result = await this.request<Observation>(path, "POST", run.claimToken, false, signal, body);
      }
    }
    for (let polls = 0; result?.status === "RUNNING" && polls < 30; polls++) {
      await delay(500, undefined, { signal });
      result = await this.request<Observation>(`${path}/${invocationId}`, "GET", run.claimToken, false, signal);
    }
    if (!result || result.schemaVersion !== 1 || result.invocationId !== invocationId || result.status === "RUNNING") {
      throw new WorkerError("TOOL_FAILED");
    }
    if (name !== "propose_retry" && result.status === "FAILED" && result.error?.retryable && !retryOf) {
      return this.tool(run, name, documentId, signal, invocationId);
    }
    return result;
  }
}
