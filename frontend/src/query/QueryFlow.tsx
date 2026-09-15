import { Check, Circle, Clock3, LoaderCircle, SkipForward, Workflow, X } from "lucide-react";
import { lessons, stageData, type StageId } from "./learning";
import type { QueryTurn } from "./types";

export function QueryFlow({ turn, selection, onSelect, active }: {
  turn?: QueryTurn; selection: StageId; onSelect(id: StageId): void; active: boolean;
}) {
  const semantic = stageData("semantic_cache_lookup", turn).stage;
  const semanticPath = semantic && semantic.status !== "skipped";
  const node = (id: StageId) => {
    const { stage } = stageData(id, turn);
    const status = stage?.status ?? "pending";
    const interrupted = status === "running" && turn?.status !== "running";
    const reused = status === "skipped" && stage?.summary?.reason === "cache_reuse";
    const cacheStatus = (id === "cache_lookup" || id === "semantic_cache_lookup") && stage?.summary?.cacheStatus;
    const title = reused ? "缓存复用" : cacheStatus === "hit" ? "精确命中" : cacheStatus === "semantic_hit" ? "语义命中" : cacheStatus === "miss" ? "未命中"
      : interrupted ? "结果未确认" : ({ running: "进行中", succeeded: "完成", skipped: "跳过", failed: "失败", cancelled: "取消", pending: "未执行" }[status]);
    const Icon = interrupted ? Clock3 : ({ running: LoaderCircle, succeeded: Check, skipped: SkipForward, failed: X, cancelled: X, pending: Circle }[status]);
    return <button className={`qa-node ${status} ${interrupted ? "interrupted" : ""}`} data-stage={id} data-state={status}
      aria-pressed={selection === id} onClick={() => onSelect(id)} title={lessons[id].method}>
      <Icon className={status === "running" && !interrupted ? "spin" : ""} />
      <span><strong>{lessons[id].title}</strong><small>{id}</small></span>
      <span className="qa-node-state">{title}{stage && status !== "running" && <small>{status === "skipped" ? "未执行" : `${stage.durationMs} ms`}</small>}</span>
    </button>;
  };
  const series = (ids: StageId[]) => ids.map(id => <div className="qa-flow-step" key={id}>{node(id)}</div>);
  return <section className={`panel flow qa-flow ${active ? "mobile-active" : ""}`}>
    <header className="panel-head"><h2><Workflow />执行流程</h2><span className="note">{turn ? `${turn.events.filter(e => e.stage).length} 条阶段记录` : "等待请求"}</span></header>
    <div className="graph qa-graph">
      <div className="qa-lane">
        {series(["access_check", "embedding_readiness", "query_resolution", "cache_lookup"])}
        {series(semanticPath ? ["query_embedding", "semantic_cache_lookup"] : ["semantic_cache_lookup"])}
        <div className="qa-parallel" aria-label="并行检索分支">
          <div>{series(semanticPath ? ["vector_search"] : ["query_embedding", "vector_search"])}</div>
          <div>{series(["full_text_search"])}</div>
        </div>
        {series(["rrf_fusion", "reranking", "child_selection", "parent_expansion", "context_building", "answer_generation", "citation_validation"])}
        {turn && turn.status !== "running" && <p className={`qa-terminal ${turn.status}`}>
          {turn.status === "completed" ? ["needs_clarification", "refused"].includes(turn.response?.queryResolution?.status ?? "")
            ? "完成 · 未执行检索与最终回答生成" : "完成 · 最终权限复查通过"
            : turn.status === "failed" ? "请求失败 · 未返回已验证答案" : "已停止等待 · 远端结果未确认"}
        </p>}
      </div>
    </div>
    <details className="event-log"><summary>实际事件 <span>{turn?.events.length ?? 0}</span></summary>
      {turn?.events.map((event, index) => <details className="event" key={index}><summary>#{index + 1} {event.stage?.stage ?? event.type} · {event.stage?.status ?? ""}</summary>
        <pre>{JSON.stringify(event.type === "completed" ? { type: event.type, traceId: event.traceId, answerStatus: event.response?.answerStatus } : event, null, 2)}</pre></details>)}
    </details>
  </section>;
}
