import { afterEach, expect, it, vi } from "vitest";
import { ApiError, BackendClient } from "../api/client";
import { monitorRun, reconnectDelay } from "./monitor";
import { event, run, runId } from "../test/fixtures";
function setup() {
  const api = new BackendClient({ tenantId: "a", actorId: "b" });
  vi.spyOn(api, "run").mockResolvedValue({ ...run, status: "RUNNING" });
  vi.spyOn(api, "tools").mockResolvedValue([]);
  vi.spyOn(api, "events").mockImplementation(async (_id, after) => ({
    runId,
    traceId: run.traceId,
    events: [],
    nextSequence: after,
    hasMore: false,
  }));
  const callbacks = {
    snapshot: vi.fn(),
    events: vi.fn(),
    connection: vi.fn(),
    error: vi.fn(),
  };
  return { api, callbacks };
}
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});
it("a terminal run loads history without opening another stream", async () => {
  const { api, callbacks } = setup();
  vi.mocked(api.run).mockResolvedValue({ ...run, status: "SUCCEEDED" });
  const stream = vi.fn();
  await monitorRun(api, runId, new AbortController().signal, callbacks, stream);
  expect(stream).not.toHaveBeenCalled();
  expect(callbacks.connection).toHaveBeenLastCalledWith("已结束");
});
it("access denial clears the view and never reconnects", async () => {
  const { api, callbacks } = setup();
  vi.mocked(api.run).mockRejectedValue(
    new ApiError(404, "NOT_FOUND", "hidden"),
  );
  await monitorRun(
    api,
    runId,
    new AbortController().signal,
    callbacks,
    vi.fn(),
  );
  expect(callbacks.error).toHaveBeenCalledWith(expect.any(ApiError), true);
  expect(api.run).toHaveBeenCalledTimes(1);
});
it("transport failures stop after five attempts, without manufacturing a failed run", async () => {
  vi.useFakeTimers();
  const { api, callbacks } = setup();
  const stream = vi.fn().mockRejectedValue(new Error("offline"));
  const done = monitorRun(
    api,
    runId,
    new AbortController().signal,
    callbacks,
    stream,
  );
  await vi.runAllTimersAsync();
  await done;
  expect(stream).toHaveBeenCalledTimes(5);
  expect(callbacks.connection).toHaveBeenLastCalledWith("连接中断");
  expect(
    callbacks.snapshot.mock.calls.every(([r]) => r.status === "RUNNING"),
  ).toBe(true);
  expect(reconnectDelay(5)).toBe(10000);
});
it("ignores duplicate events and reconnects using the last processed sequence", async () => {
  vi.useFakeTimers();
  const { api, callbacks } = setup();
  const cursors: number[] = [];
  const stream = vi.fn(async (_api, _id, _trace, after, _signal, receive) => {
    cursors.push(after);
    if (cursors.length === 1) {
      receive(event(1));
      receive(event(1));
      throw new Error("offline");
    }
    vi.mocked(api.run).mockResolvedValue({ ...run, status: "SUCCEEDED" });
  });
  const done = monitorRun(
    api,
    runId,
    new AbortController().signal,
    callbacks,
    stream,
  );
  await vi.runAllTimersAsync();
  await done;
  expect(cursors).toEqual([0, 1]);
  expect(
    callbacks.events.mock.calls.flatMap(([events]) => events),
  ).toHaveLength(1);
});
it("does not publish a snapshot that arrives after identity unmount", async () => {
  const { api, callbacks } = setup();
  const abort = new AbortController();
  let resolve!: (value: typeof run) => void;
  vi.mocked(api.run).mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  const done = monitorRun(api, runId, abort.signal, callbacks, vi.fn());
  abort.abort();
  resolve(run);
  await done;
  expect(callbacks.snapshot).not.toHaveBeenCalled();
  expect(callbacks.events).not.toHaveBeenCalled();
});
it("does not publish or skip over a gap in persisted history", async () => {
  vi.useFakeTimers();
  const { api, callbacks } = setup();
  const stream = vi.fn();
  vi.mocked(api.events).mockResolvedValue({
    runId,
    traceId: run.traceId,
    events: [event(2)],
    nextSequence: 2,
    hasMore: false,
  });
  const done = monitorRun(
    api,
    runId,
    new AbortController().signal,
    callbacks,
    stream,
  );
  await vi.runAllTimersAsync();
  await done;
  expect(callbacks.events).not.toHaveBeenCalled();
  expect(stream).not.toHaveBeenCalled();
  expect(callbacks.error).toHaveBeenCalledWith(
    expect.objectContaining({ code: "INVALID_HISTORY" }),
    false,
  );
  expect(
    vi.mocked(api.events).mock.calls.every(([, after]) => after === 0),
  ).toBe(true);
});
