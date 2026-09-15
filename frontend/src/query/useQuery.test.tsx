import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ApiError, BackendClient } from "../api/client";
import { queryModel, queryRequest, queryResponse } from "../test/queryFixtures";
import { streamQuery } from "./client";
import { useQuery } from "./useQuery";
vi.mock("./client", () => ({ streamQuery: vi.fn() }));
const stream = vi.mocked(streamQuery);
const api = new BackendClient({ tenantId: "default", actorId: "anonymous" });
beforeEach(() => { stream.mockReset(); });

it("synchronously prevents double submits and ignores late events after local stop", async () => {
  let finish!: () => void;
  stream.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const { result } = renderHook(() => useQuery(api));
  let pending!: Promise<void>;
  act(() => { pending = result.current.submit(queryRequest, queryModel); void result.current.submit(queryRequest, queryModel); });
  expect(stream).toHaveBeenCalledTimes(1);
  const args = stream.mock.calls[0];
  act(() => result.current.stop());
  expect(args[4].aborted).toBe(true);
  await act(async () => { args[5]({ type: "completed", traceId: args[2], response: queryResponse(args[2]) }); finish(); await pending; });
  expect(result.current.turns[0].status).toBe("interrupted");
  expect(result.current.turns[0].response).toBeUndefined();
});
it("aborts when the view unmounts", () => {
  stream.mockImplementation(() => new Promise(() => undefined));
  const { result, unmount } = renderHook(() => useQuery(api));
  act(() => { void result.current.submit(queryRequest, queryModel); });
  unmount(); expect(stream.mock.calls[0][4].aborted).toBe(true);
});
it("keeps at most twelve turns and clears all previous evidence on denied access", async () => {
  stream.mockImplementation(async (_api, _request, trace, _model, _signal, onEvent) => {
    onEvent({ type: "completed", traceId: trace, response: queryResponse(trace) });
  });
  const { result } = renderHook(() => useQuery(api));
  for (let i = 0; i < 13; i++) await act(() => result.current.submit(queryRequest, queryModel));
  expect(result.current.turns).toHaveLength(12);
  stream.mockRejectedValueOnce(new ApiError(404, "DOCUMENT_NOT_ACCESSIBLE", "Hidden"));
  await act(() => result.current.submit(queryRequest, queryModel));
  expect(result.current.turns).toEqual([]); expect(result.current.error).toContain("已清空");
});
