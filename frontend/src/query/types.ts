import type { Json } from "../api/types";

export type QueryScope = "library" | "documents";
export interface QueryRequest {
  sessionId: string;
  question: string;
  scope: QueryScope;
  documentIds: string[];
  topK: number;
  contextBudgetChars: number;
  debug: true;
}
export interface Capabilities {
  liveQueryReady: boolean;
  activeAnswerGenerator: string;
  answerModel: string | null;
  embedding: { provider: string; modelName: string; dimension: number };
  queryScopes?: QueryScope[];
  maxLibraryDocuments?: number;
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
  answerStatus: "answered" | "insufficient_context" | "refused";
  answerProvider: "openai";
  answerModel: string;
  citations: Citation[];
  finalContextText: string;
  retrievalCacheStatus: "hit" | "miss" | "bypassed";
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
    debugMetadata: Record<string, Json>;
  };
}
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
}
