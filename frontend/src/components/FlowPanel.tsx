import { Workflow } from "lucide-react";
import type { RunEvent } from "../api/types";
import type { FlowNode } from "../run/flow";
import { Empty, StateIcon, Status, time } from "./shared";

export function FlowPanel({
  nodes,
  selected,
  onSelect,
  events,
  active,
}: {
  nodes: FlowNode[];
  selected?: string;
  onSelect(id: string): void;
  events: RunEvent[];
  active: boolean;
}) {
  return (
    <section className={`panel flow ${active ? "mobile-active" : ""}`}>
      <header className="panel-head">
        <h2>
          <Workflow />
          执行流程
        </h2>
        <span className="note">实际记录 · {nodes.length} 个节点</span>
      </header>
      <div className="graph">
        {nodes.length === 0 ? (
          <Empty>
            <Workflow />
            <h3>尚未选择任务</h3>
            <p>提交文档处理请求，或打开已有 run。</p>
          </Empty>
        ) : (
          <div className="lane">
            {nodes.map((node, index) => (
              <div key={node.id}>
                {index > 0 && <div className="connector" aria-hidden="true" />}
                <button
                  className={`node ${node.status.toLowerCase()}`}
                  aria-pressed={selected === node.id}
                  onClick={() => onSelect(node.id)}
                >
                  <span className="node-icon">
                    <StateIcon value={node.status} />
                  </span>
                  <span className="node-main">
                    <strong>
                      {String(index + 1).padStart(2, "0")} · {node.title}
                    </strong>
                    <code>{node.subtitle}</code>
                    {node.tool?.retryOf && (
                      <small>关联重试 · {node.tool.retryOf.slice(0, 8)}</small>
                    )}
                  </span>
                  <Status
                    value={node.status}
                    label={
                      node.tool?.toolName === "propose_retry" &&
                      node.status === "SUCCEEDED"
                        ? "提议已记录"
                        : undefined
                    }
                  />
                </button>
              </div>
            ))}
            <p className="boundary">
              节点按已记录事件排序。尚未收到的记录不推断为成功；源码映射不代表完整调用栈。
            </p>
          </div>
        )}
      </div>
      <details className="event-log">
        <summary>
          事件记录 <span>{events.length}</span>
        </summary>
        <div>
          {events.map((event) => (
            <details className="event" key={`${event.runId}:${event.sequence}`}>
              <summary>
                <span className="sequence">#{event.sequence}</span>
                <code>{event.eventType}</code>
                <time>{time(event.createdAt)}</time>
              </summary>
              <pre>{JSON.stringify(event.payload, null, 2)}</pre>
            </details>
          ))}
        </div>
      </details>
    </section>
  );
}
