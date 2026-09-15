export type Json =
  | null
  | boolean
  | number
  | string
  | Json[]
  | { [key: string]: Json };
export interface Identity {
  tenantId: string;
  actorId: string;
}
export interface DocumentInfo {
  id: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  status: string;
  visibility: "PRIVATE" | "TENANT";
  ownerId: string;
  tenantId: string;
}
export interface Approval {
  approvalId: string;
  documentId: string;
  action: string;
  reason: string;
  status: string;
  expiresAt: string;
  arguments: Record<string, Json>;
  stateFingerprint: string;
  requestedBy: string;
  decidedBy: string | null;
}
export interface ActionResult {
  executionId: string;
  approvalId: string;
  documentId: string;
  action: string;
  status: string;
  errorCode: string | null;
  ingestionJobId: string | null;
}
export interface Report {
  summary: string;
  findings: {
    documentId: string;
    condition: string;
    explanation: string;
    observationIds: string[];
    proposedNextAction?: string;
  }[];
  unresolved: string[];
}
export interface Run {
  runId: string;
  traceId: string;
  status: string;
  executionMode: "scripted" | "pi";
  provider: string;
  model: string;
  createdAt: string;
  updatedAt: string;
  recoveryCount: number;
  cancellationRequested: boolean;
  cancellationPending: boolean;
  question?: string;
  documentIds?: string[];
  approvals?: Approval[];
  pendingApproval?: Approval;
  actionResults?: ActionResult[];
  report?: Report;
  errorCode?: string;
}
export interface RunEvent {
  sequence: number;
  eventType: string;
  createdAt: string;
  payload: Record<string, Json>;
  runId: string;
  traceId: string;
}
export interface ToolSummary {
  observationId: string;
  invocationId: string;
  toolName: string;
  attempt: number;
  retryOf: string | null;
  status: string;
  createdAt: string;
  finishedAt: string | null;
}
export interface ToolDetail extends ToolSummary {
  schemaVersion: number;
  runId: string;
  traceId: string;
  arguments: Json;
  result: Json;
  payloadOmitted: boolean;
  omittedFields: string[];
}
export const isUuid = (value: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export const isTerminal = (run: Run) =>
  ["SUCCEEDED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(
    run.status,
  );
export const validIdentity = (id: Identity) =>
  [id.tenantId, id.actorId].every((v) => /^[A-Za-z0-9._:-]{1,120}$/.test(v));
