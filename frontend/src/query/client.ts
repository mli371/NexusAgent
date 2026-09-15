import { createParser } from "eventsource-parser";
import { ApiError, BackendClient, responseError } from "../api/client";
import { isUuid } from "../api/types";
import type { QueryEvent, QueryRequest, QueryResponse, StageEvent } from "./types";

const invalid = () => new ApiError(502, "INVALID_QUERY_STREAM", "问答事件不完整或关联信息不匹配；没有自动重发请求");
const stages = new Set(["access_check", "embedding_readiness", "query_resolution", "cache_lookup", "semantic_cache_lookup", "query_embedding", "vector_search",
  "full_text_search", "rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building", "answer_generation", "citation_validation"]);

function validStage(stage: StageEvent): boolean {
  return !!stage && stages.has(stage.stage) && Number.isSafeInteger(stage.sequence) && stage.sequence > 0
    && stage.attempt === 1 && ["running", "succeeded", "skipped", "failed", "cancelled"].includes(stage.status)
    && Number.isFinite(Date.parse(stage.timestamp)) && Number.isFinite(stage.durationMs) && stage.durationMs >= 0;
}
function validResponse(value: QueryResponse, traceId: string, request: QueryRequest, model: string): boolean {
  if (!value || value.traceId !== traceId || value.answerProvider !== "openai" || value.answerModel !== model
    || !["answered", "insufficient_context", "refused", "needs_clarification"].includes(value.answerStatus)
    || typeof value.answer !== "string" || value.answer.length > 16000 || !value.answer.trim()
    || !Array.isArray(value.citations) || value.citations.length > 50 || !Array.isArray(value.stages)
    || value.stages.length > 48 || !value.stages.every(validStage) || !["hit", "semantic_hit", "miss", "bypassed"].includes(value.retrievalCacheStatus)
    || typeof value.finalContextText !== "string" || !Array.isArray(value.contextDebug?.expandedParentContexts)
    || !Array.isArray(value.contextDebug?.selectedChildChunks) || !Array.isArray(value.contextDebug?.rerankedCandidates)
    || value.scope?.mode !== request.scope || !validCoverage(value, request)) return false;
  const resolution = value.queryResolution, historyCount = request.history?.length ?? 0;
  if (historyCount > 0 && !resolution) return false;
  if (resolution) {
    if (resolution.originalQuestion !== request.question.trim() || resolution.historyTurnsUsed !== historyCount
      || resolution.modelCalled !== (historyCount > 0) || resolution.resolverModel !== (historyCount > 0 ? model : null)
      || resolution.resolverVersion !== "page-follow-up-v1") return false;
    const known = { unchanged: ["STANDALONE"], rewritten: ["RESOLVED_REFERENCES"],
      needs_clarification: ["AMBIGUOUS_REFERENCES", "UNSUPPORTED_FOLLOW_UP"], refused: ["MODEL_REFUSED"] };
    if (!known[resolution.status]?.includes(resolution.reasonCode)) return false;
    if (historyCount === 0 && resolution.status !== "unchanged") return false;
    const terminal = resolution.status === "needs_clarification" || resolution.status === "refused";
    if (terminal) {
      if (resolution.resolvedQuestion !== null || value.answerStatus !== resolution.status || value.citations.length
        || value.finalContextText !== "" || value.retrievalCacheStatus !== "bypassed"
        || value.contextDebug.expandedParentContexts.length || value.contextDebug.selectedChildChunks.length
        || value.contextDebug.rerankedCandidates.length
        || (resolution.status === "needs_clarification" && value.answer.length > 300)) return false;
      for (const stage of stages) {
        if (["access_check", "embedding_readiness", "query_resolution"].includes(stage)) continue;
        const events = value.stages.filter(s => s.stage === stage);
        if (events.length !== 1 || events[0].status !== "skipped") return false;
      }
    } else if (typeof resolution.resolvedQuestion !== "string" || !resolution.resolvedQuestion.trim()
      || resolution.resolvedQuestion.length > 2000 || value.answerStatus === "needs_clarification"
      || (resolution.status === "unchanged" && resolution.resolvedQuestion !== request.question.trim())) return false;
    const last = value.stages.filter(s => s.stage === "query_resolution").at(-1);
    if (last?.status !== (historyCount > 0 ? "succeeded" : "skipped")) return false;
  } else if (value.answerStatus === "needs_clarification") return false;
  const scope = value.scope;
  if (![scope.accessibleDocumentCount, scope.searchedDocumentCount, scope.excludedDocumentCount].every(n => Number.isInteger(n) && n >= 0)
    || scope.accessibleDocumentCount > 200 || scope.searchedDocumentCount < 1
    || scope.searchedDocumentCount + scope.excludedDocumentCount !== scope.accessibleDocumentCount
    || !scope.exclusions || typeof scope.exclusions !== "object" || Array.isArray(scope.exclusions)
    || !Object.values(scope.exclusions).every(n => Number.isInteger(n) && n >= 0)
    || Object.values(scope.exclusions).reduce((sum, n) => sum + n, 0) !== scope.excludedDocumentCount) return false;
  const markers = new Set<string>();
  for (const c of value.citations) {
    if (!isUuid(c.documentId) || !isUuid(c.childChunkId) || !isUuid(c.parentChunkId)
      || !/^\[C\d+\]$/.test(c.citationMarker) || markers.has(c.citationMarker)
      || typeof c.originalFilename !== "string" || typeof c.previewText !== "string"
      || !Number.isInteger(c.charStart) || !Number.isInteger(c.charEnd) || c.charStart < 0 || c.charEnd <= c.charStart
      || !Number.isInteger(c.chunkIndex) || c.chunkIndex < 0
      || (request.scope === "documents" && !request.documentIds.includes(c.documentId))) return false;
    markers.add(c.citationMarker);
    if (!value.contextDebug.selectedChildChunks.some(child => child.childChunkId === c.childChunkId
      && child.parentChunkId === c.parentChunkId && child.documentId === c.documentId)) return false;
    const parent = value.contextDebug.expandedParentContexts.find(p => p.parentChunkId === c.parentChunkId && p.documentId === c.documentId);
    if (!parent || typeof parent.text !== "string" || !Array.isArray(parent.childChunkIds) || !parent.childChunkIds.includes(c.childChunkId)
      || !Number.isInteger(parent.charStart) || !Number.isInteger(parent.charEnd) || parent.charEnd - parent.charStart !== parent.text.length
      || parent.charStart > c.charStart || parent.charEnd < c.charEnd) return false;
  }
  const inline = new Set(value.answer.match(/\[C[^\]\r\n]*\]/g) ?? []);
  return value.answerStatus === "answered"
    ? markers.size > 0 && inline.size === markers.size && [...inline].every(marker => markers.has(marker))
    : markers.size === 0 && inline.size === 0;
}

function validCoverage(value: QueryResponse, request: QueryRequest): boolean {
  const m = value.contextDebug.debugMetadata;
  if (m?.allocationStrategy !== "child-first-v1") {
    return !value.stages.some(s => s.stage === "child_selection" && (s.status === "succeeded" || s.summary?.reason === "cache_reuse"));
  }
  if (!Array.isArray(m.allocations) || m.allocations.length > 50
    || ![m.rerankedCandidateCount, m.selectedChildChunkCount, m.expandedParentContextCount, m.representativeChildCount,
      m.appliedBudgetChars, m.usedBudgetChars, m.reservedChildChars, m.skippedDuplicateParentCount, m.skippedBudgetCount,
      m.trimmedParentCount].every(n => typeof n === "number" && Number.isInteger(n) && n >= 0)
    || m.appliedBudgetChars !== request.contextBudgetChars || m.usedBudgetChars! > request.contextBudgetChars
    || m.reservedChildChars! > m.usedBudgetChars!) return false;
  const included = m.allocations.filter(a => a.status === "INCLUDED");
  const parents = value.contextDebug.expandedParentContexts, children = value.contextDebug.selectedChildChunks;
  if (included.length !== m.selectedChildChunkCount || included.length !== parents.length || included.length !== children.length
    || included.length !== m.expandedParentContextCount
    || new Set(included.map(a => a.parentChunkId)).size !== included.length
    || m.allocations.filter(a => a.status === "DUPLICATE_PARENT").length !== m.skippedDuplicateParentCount
    || m.allocations.filter(a => a.status === "CHILD_EXCEEDS_REMAINING_BUDGET").length !== m.skippedBudgetCount
    || m.representativeChildCount !== included.length + m.skippedBudgetCount!
    || m.rerankedCandidateCount! < m.allocations.length
    || included.filter(a => a.parentTruncated).length !== m.trimmedParentCount
    || included.reduce((sum, a) => sum + a.allocatedChars, 0) !== m.usedBudgetChars) return false;
  for (const a of m.allocations) {
    if (!isUuid(a.documentId) || !isUuid(a.childChunkId) || !isUuid(a.parentChunkId) || typeof a.originalFilename !== "string"
      || (request.scope === "documents" && !request.documentIds.includes(a.documentId))
      || ![a.rerankedRank, a.childChars, a.parentChars].every(n => Number.isInteger(n) && n > 0)
      || !Number.isInteger(a.allocatedChars) || a.allocatedChars < 0 || a.allocatedChars > a.parentChars
      || typeof a.parentTruncated !== "boolean"
      || !["INCLUDED", "DUPLICATE_PARENT", "CHILD_EXCEEDS_REMAINING_BUDGET"].includes(a.status)) return false;
    if (a.status !== "INCLUDED") { if (a.allocatedChars !== 0 || a.parentTruncated) return false; continue; }
    const child = children.find(c => c.childChunkId === a.childChunkId);
    const parent = parents.find(p => p.parentChunkId === a.parentChunkId);
    if (!child || !parent || child.documentId !== a.documentId || child.parentChunkId !== a.parentChunkId
      || parent.documentId !== a.documentId || parent.originalFilename !== a.originalFilename
      || typeof child.charStart !== "number" || typeof child.charEnd !== "number" || child.charEnd - child.charStart !== a.childChars
      || parent.charStart > child.charStart || parent.charEnd < child.charEnd || !parent.childChunkIds.includes(a.childChunkId)
      || parent.text.length !== a.allocatedChars || parent.includedChars !== a.allocatedChars
      || parent.charEnd - parent.charStart !== a.allocatedChars || parent.truncated !== a.parentTruncated
      || a.parentTruncated !== (a.allocatedChars < a.parentChars)) return false;
  }
  return true;
}

/** One POST only. A disconnected stream is an unknown result, never an automatic retry. */
export async function streamQuery(api: BackendClient, request: QueryRequest, traceId: string, model: string,
  signal: AbortSignal, onEvent: (event: QueryEvent) => void): Promise<void> {
  const timeout = new AbortController();
  let timer = setTimeout(() => timeout.abort(), 60000);
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  let terminal = false, received = false, sequence = 0, count = 0, bytes = 0;
  let answer: string | undefined;
  try {
    const response = await fetch("/api/v1/query/stream", {
      method: "POST", cache: "no-store",
      headers: { ...api.headers(), "Content-Type": "application/json", Accept: "text/event-stream", "X-Trace-Id": traceId },
      body: JSON.stringify(request), signal: AbortSignal.any([signal, timeout.signal, AbortSignal.timeout(360000)]),
    });
    if (!response.ok) throw await responseError(response);
    if (!response.body || !response.headers.get("content-type")?.includes("text/event-stream")) throw invalid();
    const parser = createParser({ maxBufferSize: 1048576,
      onError() { throw invalid(); },
      onEvent(message) {
        if (signal.aborted) return;
        if (terminal || ++count > 64) throw invalid();
        let event: QueryEvent;
        try { event = JSON.parse(message.data); } catch { throw invalid(); }
        if (!event || event.traceId !== traceId || event.type !== message.event) throw invalid();
        if (event.type === "error") {
          terminal = true;
          throw new ApiError(event.code === "DOCUMENT_NOT_ACCESSIBLE" ? 404 : 503,
            event.code?.slice(0, 120) ?? "QUERY_FAILED", event.message?.slice(0, 400) ?? "本次问答失败");
        }
        if (event.type === "received") {
          if (received || count !== 1) throw invalid();
          received = true;
        } else if (!received) throw invalid();
        else if (event.type === "stage") {
          if (!event.stage || !validStage(event.stage) || event.stage.sequence !== sequence + 1 || answer !== undefined) throw invalid();
          sequence = event.stage.sequence;
        } else if (event.type === "message") {
          if (answer !== undefined || typeof event.message !== "string" || event.message.length > 16000) throw invalid();
          answer = event.message;
          return; // Hold the answer until completed confirms the complete, correlated response.
        } else if (event.type === "completed") {
          if (!event.response || !validResponse(event.response, traceId, request, model) || event.response.answer !== answer
            || event.response.stages.at(-1)?.sequence !== sequence) throw invalid();
          terminal = true;
        } else throw invalid();
        onEvent(event);
      },
    });
    reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8", { fatal: true });
    while (!terminal) {
      clearTimeout(timer);
      timer = setTimeout(() => timeout.abort(), 180000);
      const chunk = await reader.read();
      if (chunk.done) { parser.feed(decoder.decode()); break; }
      bytes += chunk.value.byteLength;
      if (bytes > 2 * 1024 * 1024) throw invalid();
      parser.feed(decoder.decode(chunk.value, { stream: true }));
    }
    if (!terminal && !signal.aborted) throw new ApiError(502, "QUERY_STREAM_INTERRUPTED", "连接已断开，结果未确认；没有自动重发请求");
  } finally {
    clearTimeout(timer);
    await reader?.cancel().catch(() => undefined);
    reader?.releaseLock();
  }
}
