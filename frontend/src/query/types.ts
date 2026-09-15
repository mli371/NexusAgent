import type { Json } from "../api/types";

export type QueryScope = "library" | "documents";
export interface ConversationTurn { question: string; answer: string; answerTruncated: boolean }
export interface QueryResolution {
  originalQuestion: string;
  resolvedQuestion: string | null;
  status: "unchanged" | "rewritten" | "needs_clarification" | "refused";
  reasonCode: string;
  historyTurnsUsed: number;
  modelCalled: boolean;
  resolverModel: string | null;
  resolverVersion: string;
}
export interface QueryRequest {
  sessionId: string;
  question: string;
  scope: QueryScope;
  documentIds: string[];
  topK: number;
  contextBudgetChars: number;
  debug: true;
  history?: ConversationTurn[];
}
export interface Capabilities {
  liveQueryReady: boolean;
  activeAnswerGenerator: string;
  answerModel: string | null;
  embedding: { provider: string; modelName: string; dimension: number };
  queryScopes?: QueryScope[];
  maxLibraryDocuments?: number;
  pageFollowUpSupported?: boolean;
  maxHistoryTurns?: number;
  reason: string;
}
export interface EmbeddingStatus {
  documentId: string;
  childChunkCount: number;
  matchingChildChunkCount: number;
  mismatchedChildChunkCount: number;
  complete: boolean;
  provider: string;
  modelName: string;
  dimension: number;
}
export interface StageEvent {
  sequence: number;
  stage: string;
  attempt: number;
  status: "running" | "succeeded" | "skipped" | "failed" | "cancelled";
  timestamp: string;
  durationMs: number;
  summary?: Record<string, Json>;
}
export interface Citation {
  citationIndex: number;
  citationMarker: string;
  documentId: string;
  originalFilename: string;
  parentChunkId: string;
  childChunkId: string;
  chunkIndex: number;
  sectionTitle?: string | null;
  charStart: number;
  charEnd: number;
  previewText: string;
}
export interface ParentContext {
  parentChunkId: string;
  documentId: string;
  originalFilename: string;
  charStart: number;
  charEnd: number;
  text: string;
  truncated: boolean;
  includedChars: number;
  childChunkIds: string[];
}
export interface QueryResponse {
  traceId: string;
  answer: string;
  answerStatus: "answered" | "insufficient_context" | "refused" | "needs_clarification";
  queryResolution?: QueryResolution;
  answerProvider: "openai";
  answerModel: string;
  citations: Citation[];
  finalContextText: string;
  retrievalCacheStatus: "hit" | "semantic_hit" | "miss" | "bypassed";
  stages: StageEvent[];
  limitations?: string[];
  scope: {
    mode: QueryScope;
    accessibleDocumentCount: number;
    searchedDocumentCount: number;
    excludedDocumentCount: number;
    exclusions: Record<string, number>;
  };
  retrievalDebug?: {
    vectorCandidates: Record<string, Json>[];
    fullTextCandidates: Record<string, Json>[];
    fusedCandidates: Record<string, Json>[];
  };
  contextDebug: {
    rerankedCandidates: Record<string, Json>[];
    selectedChildChunks: Record<string, Json>[];
    expandedParentContexts: ParentContext[];
    citations: Citation[];
    debugMetadata: Record<string, Json> & Partial<CoverageMetadata>;
  };
}
export type ContextAllocation = {
  documentId: string; originalFilename: string; parentChunkId: string; childChunkId: string;
  rerankedRank: number; status: "INCLUDED" | "DUPLICATE_PARENT" | "CHILD_EXCEEDS_REMAINING_BUDGET";
  childChars: number; parentChars: number; allocatedChars: number; parentTruncated: boolean;
};
export type CoverageMetadata = {
  allocationStrategy: string; fusedCandidateCount: number; rerankedCandidateCount: number;
  selectedChildChunkCount: number; expandedParentContextCount: number; representativeChildCount: number;
  appliedBudgetChars: number; usedBudgetChars: number; reservedChildChars: number;
  skippedDuplicateParentCount: number; skippedBudgetCount: number; trimmedParentCount: number;
  allocations: ContextAllocation[];
};
export interface QueryEvent {
  type: "received" | "stage" | "message" | "completed" | "error";
  traceId: string;
  stage?: StageEvent;
  response?: QueryResponse;
  message?: string;
  code?: string;
}
export interface QueryTurn {
  traceId: string;
  request: QueryRequest;
  status: "running" | "completed" | "failed" | "interrupted";
  events: QueryEvent[];
  response?: QueryResponse;
  error?: string;
  historyTurnsSent?: number;
}
