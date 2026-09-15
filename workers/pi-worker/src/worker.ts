import { setTimeout as delay } from "node:timers/promises";
import { BackendClient } from "./backend-client.js";
import type { Config } from "./config.js";
import { ApprovalPaused, BackendError, WorkerError, RunStopped } from "./protocol.js";
import type { Assignment } from "./protocol.js";
import type { DiagnosticSession } from "./scripted-session.js";

export async function executeRun(run: Assignment, backend: BackendClient,
                                 session: DiagnosticSession, outerSignal: AbortSignal): Promise<boolean> {
  const controller = new AbortController();
  const signal = AbortSignal.any([outerSignal, controller.signal,
    AbortSignal.timeout(Math.max(1, Date.parse(run.deadlineAt) - Date.now()))]);
  let beating = false;
  const heartbeat = setInterval(() => {
    if (beating || signal.aborted) { return; }
    beating = true;
    void backend.heartbeat(run, signal).catch(error => controller.abort(
      error instanceof BackendError && ["RUN_CANCELLED", "CLAIM_UNAUTHORIZED", "CLAIM_INACTIVE"].includes(error.code)
        ? new RunStopped(error.code) : new WorkerError("WORKER_ERROR")))
      .finally(() => { beating = false; });
  }, Math.max(100, Math.min(10_000, run.leaseSeconds * 1000 / 3)));
  let tools = run.toolsUsed ?? 0;
  try {
    if (run.pendingApprovalId) {
      const outcome = await backend.executeApproved(run, signal);
      if (outcome.status === "UNKNOWN" || ["RECOVERY_REQUIRED", "CANCELLED"].includes(outcome.runStatus ?? "")) {
        throw new RunStopped(outcome.runStatus ?? "RECOVERY_REQUIRED");
      }
      if (run.continuation) {
        run.continuation.actionResults = run.continuation.actionResults
          .map(result => result.approvalId === outcome.approvalId ? outcome : result);
      }
    }
    const report = await session.report(run, async (name, documentId, proposal) => {
      if (++tools > run.maxTools) { throw new WorkerError("BUDGET_EXCEEDED"); }
      const result = await backend.tool(run, name, documentId, signal, undefined, proposal);
      if (name === "propose_retry" && result.status === "SUCCEEDED" && result.data?.status === "PENDING") {
        throw new ApprovalPaused(String(result.data.approvalId));
      }
      return result;
    }, signal, () => backend.reserveRound(run, signal));
    signal.throwIfAborted();
    try { await backend.complete(run, report, signal); }
    catch (error) {
      if (error instanceof BackendError && error.status === 400 && session.repair) {
        await backend.complete(run, await session.repair(signal), signal);
      } else if (!(error instanceof BackendError) || error.status >= 500) {
        // Completion is idempotent; retry exactly the same report after a lost response.
        await backend.complete(run, report, signal);
      } else { throw error; }
    }
    console.info(JSON.stringify({ event: "run_completed", runId: run.runId, traceId: run.traceId, mode: run.executionMode }));
    return true;
  } catch (error) {
    if (error instanceof ApprovalPaused) {
      console.info(JSON.stringify({ event: "waiting_approval", runId: run.runId, traceId: run.traceId, approvalId: error.approvalId }));
      return true;
    }
    const stopped = error instanceof RunStopped ? error.code : signal.reason instanceof RunStopped ? signal.reason.code
      : error instanceof BackendError && ["RUN_CANCELLED", "CLAIM_UNAUTHORIZED", "CLAIM_INACTIVE"].includes(error.code)
        ? error.code : outerSignal.aborted ? "WORKER_STOPPED" : undefined;
    if (stopped) {
      console.info(JSON.stringify({ event: "run_stopped", runId: run.runId, traceId: run.traceId, reason: stopped }));
      return false;
    }
    const code = error instanceof WorkerError ? error.code : signal.aborted ? "RUN_DEADLINE"
      : error instanceof BackendError && error.status === 400 ? "INVALID_REPORT" : "WORKER_ERROR";
    await backend.fail(run, code, AbortSignal.timeout(5000)).catch(() => {
      console.warn(JSON.stringify({ event: "failure_report_unavailable", runId: run.runId }));
    });
    console.warn(JSON.stringify({ event: "run_failed", runId: run.runId, traceId: run.traceId, code }));
    return false;
  } finally {
    clearInterval(heartbeat);
    controller.abort();
    await session.dispose();
  }
}

export async function work(config: Config, backend: BackendClient,
                           factory: () => Promise<DiagnosticSession>, signal: AbortSignal, once: boolean): Promise<void> {
  let backoff = 2000;
  do {
    try {
      const run = await backend.claim(signal);
      if (run) {
        if (run.executionMode !== config.mode || (config.mode === "pi" && (run.provider !== config.provider || run.model !== config.model))) {
          await backend.fail(run, "CONFIGURATION_ERROR", signal);
        } else {
          try { await executeRun(run, backend, await factory(), signal); }
          catch {
            await backend.fail(run, "WORKER_ERROR", signal).catch(() => {});
          }
        }
      }
      backoff = 2000;
    } catch (error) {
      if (signal.aborted) { break; }
      if (error instanceof BackendError && [401, 404].includes(error.status)) { throw new WorkerError("CONFIGURATION_ERROR"); }
      console.warn(JSON.stringify({ event: "claim_unavailable" }));
      backoff = Math.min(backoff * 2, 30_000);
    }
    if (!once) { await delay(backoff, undefined, { signal }).catch(() => {}); }
  } while (!once && !signal.aborted);
}
