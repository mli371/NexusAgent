import { createParser } from "eventsource-parser";
import { ApiError, BackendClient, responseError } from "./client";
import type { RunEvent } from "./types";

export async function streamEvents(
  api: BackendClient,
  id: string,
  traceId: string,
  after: number,
  signal: AbortSignal,
  onEvent: (event: RunEvent) => void,
): Promise<void> {
  const timeout = new AbortController();
  let timer = setTimeout(() => timeout.abort(), 15000);
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  try {
    const response = await fetch(
      `/api/v1/agent/runs/${id}/events/stream?afterSequence=${after}`,
      {
        headers: {
          ...api.headers(),
          Accept: "text/event-stream",
          "Last-Event-ID": String(after),
        },
        signal: AbortSignal.any([signal, timeout.signal]),
        cache: "no-store",
      },
    );
    if (!response.ok) throw await responseError(response);
    if (
      !response.headers.get("content-type")?.includes("text/event-stream") ||
      !response.body
    ) {
      throw new ApiError(502, "INVALID_STREAM", "未收到 SSE 事件流");
    }
    const parser = createParser({
      onEvent(message) {
        const value = JSON.parse(message.data);
        if (message.event === "error") {
          throw new ApiError(
            value.code === "ACCESS_REVOKED" ? 404 : 503,
            value.code ?? "STREAM_UNAVAILABLE",
            "事件连接已关闭，请确认访问范围后重连",
          );
        }
        if (
          !Number.isSafeInteger(value.sequence) ||
          value.sequence < 1 ||
          message.id !== String(value.sequence) ||
          value.runId !== id ||
          value.traceId !== traceId ||
          value.eventType !== message.event
        ) {
          throw new ApiError(502, "INVALID_STREAM_EVENT", "事件关联信息不匹配");
        }
        onEvent(value as RunEvent);
      },
      onError(error) {
        throw error;
      },
      maxBufferSize: 65536,
    });
    const decoder = new TextDecoder();
    reader = response.body.getReader();
    for (;;) {
      clearTimeout(timer);
      timer = setTimeout(() => timeout.abort(), 60000);
      const { done, value } = await reader.read();
      if (done) {
        parser.feed(decoder.decode());
        break;
      }
      parser.feed(decoder.decode(value, { stream: true }));
    }
  } finally {
    clearTimeout(timer);
    await reader?.cancel().catch(() => undefined);
    reader?.releaseLock();
  }
}
