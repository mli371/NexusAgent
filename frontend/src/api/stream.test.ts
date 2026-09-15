import { afterEach, expect, it, vi } from "vitest";
import { BackendClient } from "./client";
import { streamEvents } from "./stream";
import { event, runId, run } from "../test/fixtures";
const api = new BackendClient({ tenantId: "tenant-a", actorId: "alice" });
afterEach(() => vi.unstubAllGlobals());
function mockStream(text: string, step = 7) {
  const bytes = new TextEncoder().encode(text);
  const body = new ReadableStream({
    start(controller) {
      for (let i = 0; i < bytes.length; i += step)
        controller.enqueue(bytes.slice(i, i + step));
      controller.close();
    },
  });
  const mock = vi
    .fn()
    .mockResolvedValue(
      new Response(body, { headers: { "content-type": "text/event-stream" } }),
    );
  vi.stubGlobal("fetch", mock);
  return mock;
}
it("parses fragmented UTF-8, multiline data, comments, and sends identity/cursor", async () => {
  const value = event(2, "tool_completed", { explanation: "真实状态" });
  const mock = mockStream(
    ": keep-alive\n\nid: 2\nevent: tool_completed\ndata: " +
      JSON.stringify(value, null, 2).split("\n").join("\ndata: ") +
      "\n\n",
  );
  const received = vi.fn();
  await streamEvents(
    api,
    runId,
    run.traceId,
    1,
    new AbortController().signal,
    received,
  );
  expect(received).toHaveBeenCalledWith(value);
  expect(mock.mock.calls[0][1].headers).toMatchObject({
    "X-Tenant-Id": "tenant-a",
    "X-Actor-Id": "alice",
    "Last-Event-ID": "1",
  });
});
it("does not confuse an SSE access error with a completed business event", async () => {
  mockStream('event: error\ndata: {"code":"ACCESS_REVOKED"}\n\n');
  const received = vi.fn();
  await expect(
    streamEvents(
      api,
      runId,
      run.traceId,
      0,
      new AbortController().signal,
      received,
    ),
  ).rejects.toMatchObject({ status: 404 });
  expect(received).not.toHaveBeenCalled();
});
it("rejects events from another run or trace", async () => {
  mockStream(
    "id: 1\nevent: queued\ndata: " +
      JSON.stringify({ ...event(1), traceId: "wrong" }) +
      "\n\n",
  );
  await expect(
    streamEvents(
      api,
      runId,
      run.traceId,
      0,
      new AbortController().signal,
      vi.fn(),
    ),
  ).rejects.toMatchObject({ code: "INVALID_STREAM_EVENT" });
});
it("bounds an incomplete event instead of buffering forever", async () => {
  mockStream("data: " + "x".repeat(70000), 16000);
  await expect(
    streamEvents(
      api,
      runId,
      run.traceId,
      0,
      new AbortController().signal,
      vi.fn(),
    ),
  ).rejects.toBeTruthy();
});
