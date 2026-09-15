import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendClient } from "../api/client";
import { streamQuery } from "./client";
import { queryEvents, queryModel, queryRequest, queryResponse, semanticResponse, sse, traceId, withResolution } from "../test/queryFixtures";
import type { QueryEvent, QueryResponse } from "./types";

const api = new BackendClient({ tenantId: "tenant-a", actorId: "owner-a" });
const run = (callback = vi.fn()) => streamQuery(api, queryRequest, traceId, queryModel, new AbortController().signal, callback);
function transport(events: QueryEvent[], split = 137) {
  const bytes = new TextEncoder().encode(sse(events));
  const body = new ReadableStream<Uint8Array>({ start(controller) {
    for (let i = 0; i < bytes.length; i += split) controller.enqueue(bytes.slice(i, i + split));
    controller.close();
  } });
  const fetcher = vi.fn().mockResolvedValue(new Response(body, { headers: { "Content-Type": "text/event-stream" } }));
  vi.stubGlobal("fetch", fetcher); return fetcher;
}
afterEach(() => vi.unstubAllGlobals());
describe("real query SSE transport", () => {
  it("accepts follow-up resolution and clarification only with matching history metadata and skipped downstream stages", async () => {
    const request = { ...queryRequest, question: "Which one?", history: [{ question: "Policy?", answer: "Earlier hints", answerTruncated: false }] };
    const callback = vi.fn();
    transport(queryEvents(withResolution(queryResponse(), request, "Which policy requires approval?")));
    await streamQuery(api, request, traceId, queryModel, new AbortController().signal, callback);
    expect(callback.mock.calls.at(-1)?.[0].response.queryResolution.status).toBe("rewritten");
    transport(queryEvents(withResolution(queryResponse(), request, "", "Which policy do you mean?")));
    await expect(streamQuery(api, request, traceId, queryModel, new AbortController().signal, callback)).resolves.toBeUndefined();
    expect(callback.mock.calls.at(-1)?.[0].response.citations).toEqual([]);
    const invalid = withResolution(queryResponse(), request, "", "Which policy?");
    invalid.stages.find(s => s.stage === "answer_generation")!.status = "succeeded";
    transport(queryEvents(invalid));
    await expect(streamQuery(api, request, traceId, queryModel, new AbortController().signal, callback)).rejects.toMatchObject({ code: "INVALID_QUERY_STREAM" });
  });
  it("rejects another original question or missing resolution for a history-bearing request", async () => {
    const request = { ...queryRequest, history: [{ question: "Policy?", answer: "Prior answer", answerTruncated: false }] };
    const value = withResolution(queryResponse(), request); value.queryResolution!.originalQuestion = "different user question";
    for (const response of [value, queryResponse()]) {
      transport(queryEvents(response));
      await expect(streamQuery(api, request, traceId, queryModel, new AbortController().signal, vi.fn())).rejects.toMatchObject({ code: "INVALID_QUERY_STREAM" });
    }
  });
  it.each(["hit", "semantic_hit", "miss", "bypassed"] as const)("accepts validated %s cache responses without repeating the POST", async status => {
    const response = queryResponse(); response.retrievalCacheStatus = status;
    const fetcher = transport(queryEvents(response));
    await expect(run()).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("accepts semantic lookup events and preserves the correlated final response", async () => {
    const response = semanticResponse(), callback = vi.fn();
    transport(queryEvents(response));
    await run(callback);
    expect(callback.mock.calls.at(-1)?.[0].response.retrievalCacheStatus).toBe("semantic_hit");
  });
  it("handles split frames with one scoped POST; holds message until correlated completion", async () => {
    const fetcher = transport(queryEvents()), callback = vi.fn();
    await run(callback);
    expect(fetcher).toHaveBeenCalledTimes(1);
    const init = fetcher.mock.calls[0][1];
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "X-Tenant-Id": "tenant-a", "X-Actor-Id": "owner-a", "X-Trace-Id": traceId });
    expect(callback.mock.calls.some(([e]) => e.type === "message")).toBe(false);
    expect(callback.mock.calls.at(-1)?.[0].response.answer).toContain("[C1]");
  });
  it("does not retry a stream that ends before completed and never publishes its message", async () => {
    const fetcher = transport(queryEvents().slice(0, -1)), callback = vi.fn();
    await expect(run(callback)).rejects.toMatchObject({ code: "QUERY_STREAM_INTERRUPTED" });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(callback.mock.calls.every(([e]) => e.type !== "message" && e.type !== "completed")).toBe(true);
  });
  it("rejects wrong trace and nonsequential stages", async () => {
    const events = queryEvents(); events[1] = { ...events[1], traceId: "another-trace" };
    transport(events); await expect(run()).rejects.toMatchObject({ code: "INVALID_QUERY_STREAM" });
    const outOfOrder = queryEvents(); outOfOrder[1].stage!.sequence = 9;
    transport(outOfOrder); await expect(run()).rejects.toMatchObject({ code: "INVALID_QUERY_STREAM" });
  });
  it.each([
    ["unknown cache status", (r: QueryResponse) => { Object.assign(r, { retrievalCacheStatus: "magic" }); }],
    ["template provider", (r: QueryResponse) => { Object.assign(r, { answerProvider: "local-template" }); }],
    ["unknown marker", (r: QueryResponse) => { r.answer = "Claim [C99]"; }],
    ["missing selected child", (r: QueryResponse) => { r.contextDebug.selectedChildChunks = []; }],
    ["missing parent", (r: QueryResponse) => { r.contextDebug.expandedParentContexts = []; }],
    ["invalid scope count", (r: QueryResponse) => { r.scope.excludedDocumentCount = 99; }],
    ["budget overflow", (r: QueryResponse) => { r.contextDebug.debugMetadata.usedBudgetChars = 4001; }],
    ["fake coverage count", (r: QueryResponse) => { r.contextDebug.debugMetadata.selectedChildChunkCount = 5; }],
    ["missing allocation details", (r: QueryResponse) => { r.contextDebug.debugMetadata = {}; }],
    ["truncated child evidence", (r: QueryResponse) => { r.contextDebug.expandedParentContexts[0] = { ...r.contextDebug.expandedParentContexts[0], charEnd: 130 }; }],
  ])("rejects %s without exposing completed answer", async (_name, mutate) => {
    const response = structuredClone(queryResponse()); mutate(response);
    transport(queryEvents(response)); const callback = vi.fn();
    await expect(run(callback)).rejects.toMatchObject({ code: "INVALID_QUERY_STREAM" });
    expect(callback.mock.calls.some(([e]) => e.type === "completed")).toBe(false);
  });
  it("accepts explicit insufficient evidence with no citations or fabricated tokens", async () => {
    const response = queryResponse(); response.answerStatus = "insufficient_context"; response.answer = "Insufficient context."; response.citations = [];
    transport(queryEvents(response)); await expect(run()).resolves.toBeUndefined();
  });
  it("keeps preflight errors HTTP and exposes access change as a denied result", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "LIBRARY_NOT_READY", message: "No ready documents" }), { status: 409 })));
    await expect(run()).rejects.toMatchObject({ status: 409, code: "LIBRARY_NOT_READY" });
    transport([{ type: "error", traceId, code: "DOCUMENT_NOT_ACCESSIBLE", message: "Access changed" }]);
    await expect(run()).rejects.toMatchObject({ accessDenied: true });
  });
});
