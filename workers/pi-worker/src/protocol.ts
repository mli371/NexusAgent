export type ToolName = "inspect_document" | "list_ingestion_jobs" | "propose_retry";
export interface ProposalArguments { action: "CHUNK" | "EMBED_MISSING"; reason: string }
export interface ActionResult {
  executionId: string; approvalId: string; documentId: string; action: string;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "SKIPPED" | "UNKNOWN";
  ingestionJobId?: string | null; errorCode?: string | null;
  runStatus?: string;
}

export interface Assignment {
  schemaVersion: 1;
  runId: string;
  traceId: string;
  question: string;
  documentIds: string[];
  claimToken: string;
  executionMode: "scripted" | "pi";
  provider: string;
  model: string;
  deadlineAt: string;
  leaseSeconds: number;
  maxTools: number;
  maxRounds: number;
  promptVersion: "diagnostics-v1";
  toolsUsed?: number;
  roundsUsed?: number;
  recoveryCount?: number;
  pendingApprovalId?: string;
  continuation?: { schemaVersion: 1; observations: Observation[]; approvals: unknown[]; actionResults: ActionResult[] };
}

export interface Observation {
  schemaVersion: 1;
  invocationId: string;
  observationId: string;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  observedAt: string;
  data?: Record<string, unknown>;
  error?: { code: string; message: string; retryable: boolean };
}

export interface Report {
  schemaVersion: 1;
  summary: string;
  findings: Array<{
    documentId: string;
    observationIds: string[];
    condition: string;
    explanation: string;
    proposedNextAction?: "NONE" | "CHUNK" | "EMBED_MISSING" | "MANUAL_REVIEW";
  }>;
  unresolved: string[];
}

export class WorkerError extends Error {
  constructor(public readonly code: string) { super(code); }
}

export class ApprovalPaused extends Error {
  constructor(public readonly approvalId: string) { super("WAITING_APPROVAL"); }
}

export class RunStopped extends Error {
  constructor(public readonly code: string) { super(code); }
}

export class BackendError extends Error {
  constructor(public readonly status: number, public readonly code: string) { super(code); }
}

export function validateAssignment(value: Assignment): Assignment {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (value.schemaVersion !== 1 || value.promptVersion !== "diagnostics-v1" || !uuid.test(value.runId)
      || !Array.isArray(value.documentIds) || value.documentIds.length < 1 || value.documentIds.length > 10
      || value.documentIds.some(id => !uuid.test(id)) || typeof value.question !== "string"
      || value.question.length > 2000 || !["scripted", "pi"].includes(value.executionMode)
      || !Number.isFinite(Date.parse(value.deadlineAt)) || !Number.isInteger(value.maxRounds)
      || value.maxRounds < 1 || value.maxRounds > 50 || !Number.isInteger(value.maxTools)
      || value.maxTools < 2 || value.maxTools > 100 || typeof value.claimToken !== "string"
      || !Number.isFinite(value.leaseSeconds) || value.leaseSeconds <= 0
      || !Number.isInteger(value.toolsUsed ?? 0) || (value.toolsUsed ?? 0) < 0 || (value.toolsUsed ?? 0) > value.maxTools
      || !Number.isInteger(value.roundsUsed ?? 0) || (value.roundsUsed ?? 0) < 0 || (value.roundsUsed ?? 0) > value.maxRounds
      || !Number.isInteger(value.recoveryCount ?? 0) || (value.recoveryCount ?? 0) < 0 || (value.recoveryCount ?? 0) > 10
      || (value.pendingApprovalId !== undefined && !uuid.test(value.pendingApprovalId))
      || (value.continuation !== undefined && (value.continuation.schemaVersion !== 1
          || !Array.isArray(value.continuation.observations) || !Array.isArray(value.continuation.actionResults)
          || !Array.isArray(value.continuation.approvals) || Buffer.byteLength(JSON.stringify(value.continuation)) > 32768))) {
    throw new WorkerError("CONFIGURATION_ERROR");
  }
  return value;
}
