import type { Assignment, Observation, Report, ToolName, ProposalArguments } from "./protocol.js";
import { WorkerError } from "./protocol.js";

export type InvokeTool = (name: ToolName, documentId: string, proposal?: ProposalArguments) => Promise<Observation>;
export interface DiagnosticSession {
  report(run: Assignment, invoke: InvokeTool, signal: AbortSignal, reserveRound?: () => Promise<void>): Promise<unknown>;
  repair?(signal: AbortSignal): Promise<unknown>;
  dispose(): Promise<void>;
}

export class ScriptedSession implements DiagnosticSession {
  async report(run: Assignment, invoke: InvokeTool, signal: AbortSignal): Promise<Report> {
    const findings: Report["findings"] = [];
    for (const documentId of run.documentIds) {
      signal.throwIfAborted();
      const inspect = await invoke("inspect_document", documentId);
      const jobs = await invoke("list_ingestion_jobs", documentId);
      if (inspect.status !== "SUCCEEDED" || jobs.status !== "SUCCEEDED") { throw new WorkerError("TOOL_FAILED"); }
      const condition = String(inspect.data?.condition);
      const action = condition === "CHUNKING_REQUIRED" ? "CHUNK" : condition === "EMBEDDING_INCOMPLETE" ? "EMBED_MISSING" : undefined;
      const attempted = run.continuation?.actionResults.some(result => result.documentId === documentId && result.action === action);
      if (run.question.startsWith("Approve processing:") && action && !attempted) {
        // Explicit fixture convention only. The caller must still approve the Java proposal.
        await invoke("propose_retry", documentId, { action, reason: `Scripted proposal based on ${condition}.` });
      }
      findings.push({ documentId, observationIds: [inspect.observationId, jobs.observationId], condition,
        explanation: `Scripted diagnostic observation: ${condition}. Historical failures without structured codes remain unknown.`,
        proposedNextAction: condition === "HEALTHY" ? "NONE" : condition === "CHUNKING_REQUIRED" ? "CHUNK"
          : condition === "EMBEDDING_INCOMPLETE" ? "EMBED_MISSING" : "MANUAL_REVIEW" });
    }
    return { schemaVersion: 1, summary: "Scripted diagnostic report; no model was called. See server actionResults for any approved processing outcomes.",
      findings, unresolved: findings.filter(finding => finding.condition !== "HEALTHY")
        .map(finding => `${finding.documentId}: ${finding.condition}`) };
  }

  async dispose(): Promise<void> {}
}
