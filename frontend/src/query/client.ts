import { createParser } from "eventsource-parser";
import { ApiError, BackendClient, responseError } from "../api/client";
import { isUuid } from "../api/types";
import type { QueryEvent, QueryRequest, QueryResponse, StageEvent } from "./types";

const invalid = () => new ApiError(502, "INVALID_QUERY_STREAM", "问答事件不完整或关联信息不匹配；没有自动重发请求");
const stages = new Set(["access_check", "embedding_readiness", "cache_lookup", "query_embedding", "vector_search",
  "full_text_search", "rrf_fusion", "reranking", "parent_expansion", "context_building", "answer_generation", "citation_validation"]);

function validStage(stage: StageEvent): boolean {
  return !!stage && stages.has(stage.stage) && Number.isSafeInteger(stage.sequence) && stage.sequence > 0
    && stage.attempt === 1 && ["running", "succeeded", "skipped", "failed", "cancelled"].includes(stage.status)
    && Number.isFinite(Date.parse(stage.timestamp)) && Number.isFinite(stage.durationMs) && stage.durationMs >= 0;
}
function validResponse(value: QueryResponse, traceId: string, request: QueryRequest, model: string): boolean {
  if (!value || value.traceId !== traceId || value.answerProvider !== "openai" || value.answerModel !== model
    || !["answered", "insufficient_context", "refused"].includes(value.answerStatus)
    || typeof value.answer !== "string" || value.answer.length > 16000 || !value.answer.trim()
    || !Array.isArray(value.citations) || value.citations.length > 50 || !Array.isArray(value.stages)
    || value.stages.length > 48 || !value.stages.every(validStage) || !["hit", "miss", "bypassed"].includes(value.retrievalCacheStatus)
    || typeof value.finalContextText !== "string" || !Array.isArray(value.contextDebug?.expandedParentContexts)
    || !Array.isArray(value.contextDebug?.selectedChildChunks) || !Array.isArray(value.contextDebug?.rerankedCandidates)
    || value.scope?.mode !== request.scope) return false;
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
      || Math.min(parent.charEnd, c.charEnd) <= Math.max(parent.charStart, c.charStart)) return false;
  }
  const inline = new Set(value.answer.match(/\[C[^\]\r\n]*\]/g) ?? []);
  return value.answerStatus === "answered"
    ? markers.size > 0 && inline.size === markers.size && [...inline].every(marker => markers.has(marker))
    : markers.size === 0 && inline.size === 0;
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
