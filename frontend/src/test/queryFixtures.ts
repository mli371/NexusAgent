import { documentId } from "./fixtures";
import type { Capabilities, Citation, EmbeddingStatus, ParentContext, QueryEvent, QueryRequest, QueryResponse, StageEvent } from "../query/types";

// Synthetic transport fixtures only: no model calls and no real document content.
export const queryModel = "gpt-5.6-luna";
export const traceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const queryRequest: QueryRequest = { sessionId: "test-session", question: "What does the synthetic policy require?",
  scope: "library", documentIds: [], topK: 5, contextBudgetChars: 4000, debug: true };
export const capabilities: Capabilities = { liveQueryReady: true, activeAnswerGenerator: "openai-responses", answerModel: queryModel,
  embedding: { provider: "openai", modelName: "text-embedding-3-small", dimension: 384 },
  queryScopes: ["library", "documents"], maxLibraryDocuments: 200, reason: "Configured; credentials are not probed." };
export const embeddingStatus: EmbeddingStatus = { documentId, childChunkCount: 3, matchingChildChunkCount: 3,
  mismatchedChildChunkCount: 0, complete: true, ...capabilities.embedding };
export const parent: ParentContext = { parentChunkId: "55555555-5555-4555-8555-555555555555", documentId,
  originalFilename: "synthetic-policy.md", charStart: 100, charEnd: 156, text: "Synthetic policy. Approval is required. Scope is tenant.",
  truncated: true, includedChars: 56, childChunkIds: ["66666666-6666-4666-8666-666666666666"] };
export const citation: Citation = { citationIndex: 1, citationMarker: "[C1]", documentId, originalFilename: parent.originalFilename,
  parentChunkId: parent.parentChunkId, childChunkId: parent.childChunkIds[0], chunkIndex: 4, charStart: 118, charEnd: 138,
  previewText: "Approval is required", sectionTitle: null };

export function queryStages(): StageEvent[] {
  const rows: [string, StageEvent["status"]][] = [
    ["access_check", "running"], ["access_check", "succeeded"],
    ["embedding_readiness", "running"], ["embedding_readiness", "succeeded"], ["cache_lookup", "skipped"],
    ["query_embedding", "running"], ["full_text_search", "running"], ["full_text_search", "succeeded"],
    ["query_embedding", "succeeded"], ["vector_search", "running"], ["vector_search", "succeeded"],
    ...["rrf_fusion", "reranking", "parent_expansion", "context_building", "answer_generation", "citation_validation"]
      .flatMap(id => [[id, "running"], [id, "succeeded"]] as [string, StageEvent["status"]][]),
  ];
  return rows.map(([stage, status], index) => ({ sequence: index + 1, stage, status, attempt: 1,
    timestamp: "2026-09-14T00:00:00Z", durationMs: status === "running" ? 0 : 12, summary: { fixture: true } }));
}
export function queryResponse(trace = traceId, scope: QueryRequest["scope"] = "library"): QueryResponse {
  const candidate = { childChunkId: citation.childChunkId, parentChunkId: citation.parentChunkId, documentId,
    chunkIndex: 4, source: "both", rrfScore: 0.032786885, vectorRank: 1, fullTextRank: 1, previewText: citation.previewText };
  return { traceId: trace, answer: "Approval is required [C1]", answerStatus: "answered", answerProvider: "openai", answerModel: queryModel,
    citations: [citation], finalContextText: `[C1] synthetic-policy.md\n${parent.text}`, retrievalCacheStatus: "bypassed", stages: queryStages(),
    scope: { mode: scope, accessibleDocumentCount: scope === "library" ? 3 : 1, searchedDocumentCount: 1,
      excludedDocumentCount: scope === "library" ? 2 : 0, exclusions: scope === "library" ? { CHUNKING_REQUIRED: 1, EMBEDDING_MODEL_MISMATCH: 1 } : {} },
    retrievalDebug: { vectorCandidates: [candidate], fullTextCandidates: [candidate], fusedCandidates: [candidate] },
    contextDebug: { rerankedCandidates: [candidate], selectedChildChunks: [candidate], expandedParentContexts: [parent], citations: [citation],
      debugMetadata: { contextBudgetChars: 4000, usedContextChars: parent.text.length, budgetUnit: "characters" } },
  };
}
export function queryEvents(response = queryResponse()): QueryEvent[] {
  return [{ type: "received", traceId: response.traceId },
    ...response.stages.map(stage => ({ type: "stage" as const, traceId: response.traceId, stage })),
    { type: "message", traceId: response.traceId, message: response.answer }, { type: "completed", traceId: response.traceId, response }];
}
export function sse(events: QueryEvent[]): string {
  return events.map(event => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join("");
}
