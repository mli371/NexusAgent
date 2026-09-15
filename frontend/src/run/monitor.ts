import { ApiError, BackendClient } from "../api/client";
import { streamEvents } from "../api/stream";
import {
  isTerminal,
  type Run,
  type RunEvent,
  type ToolSummary,
} from "../api/types";

export interface MonitorCallbacks {
  snapshot(run: Run, tools: ToolSummary[]): void;
  events(events: RunEvent[]): void;
  connection(status: string): void;
  error(error: unknown, denied: boolean): void;
}
export const reconnectDelay = (failures: number) =>
  Math.min(1000 * 2 ** (failures - 1), 10000);
const delay = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });

export async function monitorRun(
  api: BackendClient,
  id: string,
  outer: AbortSignal,
  callbacks: MonitorCallbacks,
  stream = streamEvents,
): Promise<void> {
  const stopped = new AbortController();
  const signal = AbortSignal.any([outer, stopped.signal]);
  let cursor = 0,
    failures = 0,
    current: Run | undefined;
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  let refreshing: Promise<void> | undefined;
  const fail = (error: unknown) => {
    if (signal.aborted) return;
    const denied = error instanceof ApiError && error.accessDenied;
    callbacks.error(error, denied);
    if (denied) {
      callbacks.connection("访问不可用");
      stopped.abort();
    }
  };
  const refresh = async () => {
    if (refreshing) return refreshing;
    signal.throwIfAborted();
    refreshing = (async () => {
      const run = await api.run(id, signal);
      const tools = await api.tools(id, signal);
      if (!signal.aborted) {
        current = run;
        callbacks.snapshot(run, tools);
      }
    })();
    try {
      await refreshing;
    } finally {
      refreshing = undefined;
    }
  };
  const scheduleRefresh = () => {
    if (refreshTimer !== undefined || signal.aborted) return;
    refreshTimer = setTimeout(() => {
      refreshTimer = undefined;
      void refresh().catch(fail);
    }, 150);
  };
  const history = async () => {
    for (let page = 0; page < 20; page++) {
      const result = await api.events(id, cursor, signal);
      if (signal.aborted) return;
      if (result.runId !== id || result.traceId !== current?.traceId)
        throw new ApiError(502, "INVALID_HISTORY", "历史事件关联不匹配");
      if (
        result.events.some(
          (e, index) =>
            e.sequence !== cursor + index + 1 ||
            typeof e.eventType !== "string" ||
            !e.payload ||
            typeof e.payload !== "object",
        )
      )
        throw new ApiError(502, "INVALID_HISTORY", "历史事件不连续或格式无效");
      const events = result.events.map((e) => ({
        ...e,
        runId: id,
        traceId: result.traceId,
      }));
      const next = events.length ? events[events.length - 1].sequence : cursor;
      if (result.nextSequence !== next || (result.hasMore && next <= cursor))
        throw new ApiError(502, "INVALID_CURSOR", "历史事件游标无效");
      callbacks.events(events);
      cursor = next;
      if (!result.hasMore) return;
    }
    throw new ApiError(502, "EVENT_PAGE_LIMIT", "事件数量超过界面读取范围");
  };
  try {
    while (!signal.aborted) {
      try {
        callbacks.connection(failures ? "重新连接中" : "连接中");
        await refresh();
        await history();
        if (isTerminal(current!)) {
          callbacks.connection("已结束");
          return;
        }
        callbacks.connection("实时连接");
        await stream(api, id, current!.traceId, cursor, signal, (event) => {
          if (signal.aborted) return;
          failures = 0;
          callbacks.connection("实时连接");
          if (event.sequence <= cursor) return;
          if (event.sequence !== cursor + 1)
            throw new ApiError(502, "EVENT_GAP", "事件缺失，需要重新补齐");
          cursor = event.sequence;
          callbacks.events([event]);
          scheduleRefresh();
        });
        await refresh();
        await history();
        if (isTerminal(current!)) {
          callbacks.connection("已结束");
          return;
        }
        throw new ApiError(
          503,
          "STREAM_CLOSED",
          "连接已结束，正在重新确认任务状态",
        );
      } catch (error) {
        if (signal.aborted) return;
        fail(error);
        if (signal.aborted) return;
        if (++failures >= 5) {
          callbacks.connection("连接中断");
          return;
        }
        callbacks.connection("等待重连");
        await delay(reconnectDelay(failures), signal);
      }
    }
  } catch (error) {
    if (!signal.aborted) fail(error);
  } finally {
    stopped.abort();
    clearTimeout(refreshTimer);
  }
}
