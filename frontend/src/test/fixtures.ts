import type {
  Approval,
  DocumentInfo,
  Run,
  RunEvent,
  ToolDetail,
} from "../api/types";
export const runId = "11111111-1111-4111-8111-111111111111";
export const documentId = "22222222-2222-4222-8222-222222222222";
export const observationId = "33333333-3333-4333-8333-333333333333";
export const approvalId = "44444444-4444-4444-8444-444444444444";
export const document: DocumentInfo = {
  id: documentId,
  originalFilename: "synthetic-policy.md",
  contentType: "text/markdown",
  sizeBytes: 1200,
  status: "STORED",
  tenantId: "default",
  ownerId: "anonymous",
  visibility: "TENANT",
};
export const approval: Approval = {
  approvalId,
  documentId,
  action: "CHUNK",
  reason: "Missing chunks",
  status: "PENDING",
  expiresAt: "2099-01-01T00:00:00Z",
  arguments: { documentId, action: "CHUNK", force: false },
  stateFingerprint: "synthetic-fingerprint",
  requestedBy: "anonymous",
  decidedBy: null,
};
export const run: Run = {
  runId,
  traceId: "test-trace",
  status: "WAITING_APPROVAL",
  executionMode: "scripted",
  provider: "scripted",
  model: "fixtures-v1",
  createdAt: "2026-09-11T00:00:00Z",
  updatedAt: "2026-09-11T00:00:01Z",
  question: "Approve processing: inspect this document",
  documentIds: [documentId],
  recoveryCount: 0,
  cancellationRequested: false,
  cancellationPending: false,
  approvals: [approval],
  pendingApproval: approval,
  actionResults: [],
};
export const tool: ToolDetail = {
  schemaVersion: 1,
  runId,
  traceId: run.traceId,
  observationId,
  invocationId: observationId,
  toolName: "inspect_document",
  attempt: 1,
  retryOf: null,
  status: "SUCCEEDED",
  createdAt: run.createdAt,
  finishedAt: run.updatedAt,
  arguments: { documentId },
  result: {
    status: "SUCCEEDED",
    data: { documentId, condition: "CHUNKING_REQUIRED", childChunkCount: 0 },
  },
  payloadOmitted: false,
  omittedFields: [],
};
export const event = (
  sequence: number,
  eventType = "queued",
  payload: RunEvent["payload"] = {},
): RunEvent => ({
  runId,
  traceId: run.traceId,
  sequence,
  eventType,
  payload,
  createdAt: run.createdAt,
});
