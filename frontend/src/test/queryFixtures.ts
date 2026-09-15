import { documentId } from "./fixtures";
import type { Capabilities, Citation, EmbeddingStatus, ParentContext, QueryEvent, QueryRequest, QueryResponse, StageEvent } from "../query/types";

// Synthetic transport fixtures only: no model calls and no real document content.
export const queryModel = "gpt-5.6-luna";
export const traceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const queryRequest: QueryRequest = { sessionId: "test-session", question: "What does the synthetic policy require?",
  scope: "library", documentIds: [], topK: 5, contextBudgetChars: 4000, debug: true };
export const capabilities: Capabilities = { liveQueryReady: true, activeAnswerGenerator: "openai-responses", answerModel: queryModel,
  embedding: { provider: "openai", modelName: "text-embedding-3-small", dimension: 384 },
  queryScopes: ["library", "documents"], maxLibraryDocuments: 200, pageFollowUpSupported: true, maxHistoryTurns: 3,
  reason: "Configured; credentials are not probed." };
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
    ...["rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building", "answer_generation", "citation_validation"]
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
    contextDebug: { rerankedCandidates: [candidate], selectedChildChunks: [{ ...candidate, charStart: citation.charStart, charEnd: citation.charEnd }], expandedParentContexts: [parent], citations: [citation],
      debugMetadata: { allocationStrategy: "child-first-v1", fusedCandidateCount: 1, rerankedCandidateCount: 1,
        selectedChildChunkCount: 1, expandedParentContextCount: 1, representativeChildCount: 1, appliedBudgetChars: 4000,
        usedBudgetChars: parent.text.length, reservedChildChars: 20, skippedDuplicateParentCount: 0, skippedBudgetCount: 0,
        trimmedParentCount: 1, allocations: [{ documentId, originalFilename: parent.originalFilename, parentChunkId: parent.parentChunkId,
          childChunkId: citation.childChunkId, rerankedRank: 1, status: "INCLUDED", childChars: 20, parentChars: 100,
          allocatedChars: parent.text.length, parentTruncated: true }] } },
  };
}
export function queryEvents(response = queryResponse()): QueryEvent[] {
  return [{ type: "received", traceId: response.traceId },
    ...response.stages.map(stage => ({ type: "stage" as const, traceId: response.traceId, stage })),
    { type: "message", traceId: response.traceId, message: response.answer }, { type: "completed", traceId: response.traceId, response }];
}

export function coverageResponse(trace = traceId, excludeLast = false): QueryResponse {
  const response = queryResponse(trace);
  const years = [2026, 2022, 2024, 2023, 2025];
  const count = excludeLast ? 4 : 5, size = 4000 / count;
  const rows = years.map((year, i) => ({ documentId: `11111111-1111-4111-8111-${String(i).padStart(12, "0")}`,
    parentChunkId: `55555555-5555-4555-8555-${String(i).padStart(12, "0")}`,
    childChunkId: `66666666-6666-4666-8666-${String(i).padStart(12, "0")}`,
    originalFilename: `apple-${year}.md`, charStart: 200, charEnd: 600, chunkIndex: 0,
    previewText: `Synthetic ${year} evidence`, rerankedRank: i + 1 }));
  const chosen = rows.slice(0, count);
  response.scope = { mode: "library", accessibleDocumentCount: 7, searchedDocumentCount: 5,
    excludedDocumentCount: 2, exclusions: { CHUNKING_REQUIRED: 1, EMBEDDING_MODEL_MISMATCH: 1 } };
  response.retrievalDebug = { vectorCandidates: rows, fullTextCandidates: rows, fusedCandidates: rows };
  response.contextDebug.rerankedCandidates = rows;
  response.contextDebug.selectedChildChunks = chosen;
  response.contextDebug.expandedParentContexts = chosen.map(row => ({ ...row,
    charStart: 100, charEnd: 100 + size, text: "a".repeat(100) + row.previewText.padEnd(400, ".") + "z".repeat(size - 500),
    truncated: true, includedChars: size, childChunkIds: [row.childChunkId] }));
  response.contextDebug.citations = chosen.map((row, i) => ({ ...row, citationIndex: i + 1, citationMarker: `[C${i + 1}]` }));
  response.citations = response.contextDebug.citations.slice(0, 1);
  response.answer = "Synthetic comparison [C1]";
  response.finalContextText = response.contextDebug.expandedParentContexts.map((p, i) => `[C${i + 1}] ${p.originalFilename}\n${p.text}`).join("\n\n");
  response.contextDebug.debugMetadata = { allocationStrategy: "child-first-v1", fusedCandidateCount: 5, rerankedCandidateCount: 5,
    representativeChildCount: 5, selectedChildChunkCount: count, expandedParentContextCount: count,
    appliedBudgetChars: 4000, usedBudgetChars: 4000, reservedChildChars: count * 400,
    trimmedParentCount: count, skippedBudgetCount: 5 - count, skippedDuplicateParentCount: 0,
    allocations: rows.map((row, i) => ({ ...row, status: i < count ? "INCLUDED" : "CHILD_EXCEEDS_REMAINING_BUDGET",
      childChars: i < count ? 400 : 5000, parentChars: i < count ? 2000 : 6000, allocatedChars: i < count ? size : 0,
      parentTruncated: i < count })) };
  return response;
}
export function semanticResponse(trace = traceId, scope: QueryRequest["scope"] = "library"): QueryResponse {
  const response = queryResponse(trace, scope), original = response.stages;
  const base = original.find(s => s.stage === "cache_lookup")!;
  response.retrievalCacheStatus = "semantic_hit";
  response.stages = [
    ...original.filter(s => ["access_check", "embedding_readiness"].includes(s.stage)),
    { ...base, status: "succeeded" as const, summary: { cacheStatus: "miss", reason: "not_found" } },
    ...original.filter(s => s.stage === "query_embedding"),
    { ...base, stage: "semantic_cache_lookup", status: "running" as const, durationMs: 0 },
    { ...base, stage: "semantic_cache_lookup", status: "succeeded" as const,
      summary: { cacheStatus: "semantic_hit", similarity: 0.98, threshold: 0.96, reason: "validated", dataOrigin: "cached_source_query" } },
    ...original.filter(s => ["vector_search", "full_text_search", "rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building"].includes(s.stage)
      && s.status === "succeeded").map(s => ({ ...s, status: "skipped" as const, durationMs: 0, summary: { reason: "cache_reuse" } })),
    ...original.filter(s => ["answer_generation", "citation_validation"].includes(s.stage)),
  ].map((s, index) => ({ ...s, sequence: index + 1 }));
  return response;
}
export function sse(events: QueryEvent[]): string {
  return events.map(event => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join("");
}

export function withResolution(response: QueryResponse, request: QueryRequest, resolved = request.question, clarification?: string): QueryResponse {
  const count = request.history?.length ?? 0;
  response.queryResolution = { originalQuestion: request.question, resolvedQuestion: clarification ? null : resolved,
    status: clarification ? "needs_clarification" : resolved === request.question ? "unchanged" : "rewritten",
    reasonCode: clarification ? "AMBIGUOUS_REFERENCES" : resolved === request.question ? "STANDALONE" : "RESOLVED_REFERENCES",
    historyTurnsUsed: count, modelCalled: count > 0, resolverModel: count > 0 ? queryModel : null, resolverVersion: "page-follow-up-v1" };
  const base = response.stages[0];
  const summary = { status: response.queryResolution.status, historyTurnsUsed: count, modelCalled: count > 0 };
  const before = response.stages.filter(s => ["access_check", "embedding_readiness"].includes(s.stage));
  const resolve: StageEvent[] = count ? [{ ...base, stage: "query_resolution", status: "running", durationMs: 0, summary },
    { ...base, stage: "query_resolution", status: "succeeded", durationMs: 15, summary }]
    : [{ ...base, stage: "query_resolution", status: "skipped", durationMs: 0, summary: { reason: "no_history" } }];
  let after = response.stages.filter(s => !["access_check", "embedding_readiness", "query_resolution"].includes(s.stage));
  if (clarification) {
    response.answerStatus = "needs_clarification"; response.answer = clarification; response.citations = [];
    response.finalContextText = ""; response.retrievalCacheStatus = "bypassed"; response.retrievalDebug = undefined;
    response.contextDebug = { rerankedCandidates: [], selectedChildChunks: [], expandedParentContexts: [], citations: [], debugMetadata: {} };
    after = ["cache_lookup", "semantic_cache_lookup", "query_embedding", "vector_search", "full_text_search", "rrf_fusion", "reranking", "child_selection",
      "parent_expansion", "context_building", "answer_generation", "citation_validation"]
      .map(stage => ({ ...base, stage, status: "skipped", durationMs: 0, summary: { reason: "needs_clarification" } }));
  }
  response.stages = [...before, ...resolve, ...after].map((s, index) => ({ ...s, sequence: index + 1 }));
  return response;
}
