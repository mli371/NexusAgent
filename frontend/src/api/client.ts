import type {
  Approval,
  DocumentInfo,
  Identity,
  Run,
  RunEvent,
  ToolDetail,
  ToolSummary,
} from "./types";
import type { Capabilities, EmbeddingStatus } from "../query/types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
  get accessDenied() {
    return this.status === 401 || this.status === 403 || this.status === 404;
  }
}
export async function responseError(response: Response): Promise<ApiError> {
  let value: { code?: string; message?: string } = {};
  try {
    value = await response.json();
  } catch {
    /* Proxy errors may not contain JSON. */
  }
  return new ApiError(
    response.status,
    typeof value.code === "string"
      ? value.code.slice(0, 120)
      : "REQUEST_FAILED",
    typeof value.message === "string"
      ? value.message.slice(0, 400)
      : `请求未完成（HTTP ${response.status}）`,
  );
}
export const errorText = (error: unknown) =>
  error instanceof ApiError
    ? `${error.message} · ${error.code}`
    : "连接未完成，请检查本地服务；结果不确定时先刷新状态。";

export class BackendClient {
  constructor(readonly identity: Identity) {}
  headers(): Record<string, string> {
    return {
      "X-Tenant-Id": this.identity.tenantId,
      "X-Actor-Id": this.identity.actorId,
    };
  }
  async request<T>(
    path: string,
    signal: AbortSignal,
    init: RequestInit = {},
  ): Promise<T> {
    const response = await fetch(`/api/v1${path}`, {
      ...init,
      cache: "no-store",
      headers: { ...this.headers(), ...init.headers },
      signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    });
    if (!response.ok) throw await responseError(response);
    return response.json();
  }
  listDocuments(offset: number, signal: AbortSignal) {
    return this.request<DocumentInfo[]>(
      `/documents?limit=25&offset=${offset}`,
      signal,
    );
  }
  document(id: string, signal: AbortSignal) {
    return this.request<DocumentInfo>(`/documents/${id}`, signal);
  }
  queryCapabilities(signal: AbortSignal) {
    return this.request<Capabilities>("/query/capabilities", signal);
  }
  embeddingStatus(id: string, signal: AbortSignal) {
    return this.request<EmbeddingStatus>(`/documents/${id}/embedding-status`, signal);
  }
  async rebuildEmbeddings(id: string, signal: AbortSignal) {
    const response = await fetch(`/api/v1/documents/${id}/embed?replaceExisting=true`, {
      method: "POST", headers: this.headers(), cache: "no-store",
      signal: AbortSignal.any([signal, AbortSignal.timeout(300000)]),
    });
    if (!response.ok) throw await responseError(response);
    return response.json() as Promise<EmbeddingStatus>;
  }
  upload(file: File, visibility: string, signal: AbortSignal) {
    const form = new FormData();
    form.append("file", file);
    return this.request<DocumentInfo>(
      `/documents?visibility=${visibility}`,
      signal,
      { method: "POST", body: form },
    );
  }
  create(
    question: string,
    documentIds: string[],
    key: string,
    signal: AbortSignal,
  ) {
    return this.request<Run>("/agent/runs", signal, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: JSON.stringify({ question, documentIds }),
    });
  }
  run(id: string, signal: AbortSignal) {
    return this.request<Run>(`/agent/runs/${id}`, signal);
  }
  events(id: string, after: number, signal: AbortSignal) {
    return this.request<{
      runId: string;
      traceId: string;
      events: RunEvent[];
      nextSequence: number;
      hasMore: boolean;
    }>(`/agent/runs/${id}/events?afterSequence=${after}`, signal);
  }
  async tools(id: string, signal: AbortSignal): Promise<ToolSummary[]> {
    const tools: ToolSummary[] = [];
    let offset = 0;
    for (let page = 0; page < 4; page++) {
      const result = await this.request<{
        tools: ToolSummary[];
        nextOffset: number;
        hasMore: boolean;
      }>(`/agent/runs/${id}/tools?limit=50&offset=${offset}`, signal);
      tools.push(...result.tools);
      if (!result.hasMore) return tools;
      if (result.nextOffset <= offset) break;
      offset = result.nextOffset;
    }
    throw new ApiError(
      502,
      "TOOL_PAGE_LIMIT",
      "工具记录超过界面读取范围，未展示不完整结果",
    );
  }
  detail(run: string, observation: string, signal: AbortSignal) {
    return this.request<ToolDetail>(
      `/agent/runs/${run}/tools/${observation}`,
      signal,
    );
  }
  decide(
    run: string,
    approval: string,
    decision: "APPROVE" | "REJECT",
    signal: AbortSignal,
  ) {
    return this.request<Approval>(
      `/agent/runs/${run}/approvals/${approval}`,
      signal,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ decision }),
      },
    );
  }
  cancel(run: string, signal: AbortSignal) {
    return this.request<Run>(`/agent/runs/${run}/cancel`, signal, {
      method: "POST",
    });
  }
}
