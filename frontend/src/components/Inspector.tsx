import { useEffect, useState } from "react";
import { Code2, RefreshCw } from "lucide-react";
import { ApiError, BackendClient, errorText } from "../api/client";
import type { Run, ToolDetail } from "../api/types";
import type { FlowNode } from "../run/flow";
import { lessonFor } from "../learning/sources";
import { Empty, JsonView, Status, time } from "./shared";

export function Inspector({
  api,
  node,
  run,
  active,
  revoke,
}: {
  api: BackendClient;
  node?: FlowNode;
  run?: Run;
  active: boolean;
  revoke(): void;
}) {
  const [tab, setTab] = useState("input"),
    [detail, setDetail] = useState<ToolDetail>();
  const [error, setError] = useState(""),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    setDetail(undefined);
    setError("");
    const abort = new AbortController();
    if (node?.tool && run)
      api
        .detail(run.runId, node.id, abort.signal)
        .then((value) => {
          if (!abort.signal.aborted) setDetail(value);
        })
        .catch((e) => {
          if (!abort.signal.aborted) {
            setError(errorText(e));
            if (e instanceof ApiError && e.accessDenied) revoke();
          }
        });
    return () => abort.abort();
  }, [api, run?.runId, node?.id, node?.status, revision]);
  const lesson = node && lessonFor(node);
  const input = node?.tool
    ? detail?.arguments
    : node?.approval
      ? node.approval
      : node?.action
        ? run?.approvals?.find((a) => a.approvalId === node.action?.approvalId)
            ?.arguments
        : node?.kind === "request"
          ? { question: run?.question, documentIds: run?.documentIds }
          : null;
  const output = node?.tool
    ? detail?.result
    : node?.action
      ? node.action
      : node?.approval
        ? node.approval.status === "PENDING"
          ? null
          : { status: node.approval.status, decidedBy: node.approval.decidedBy }
        : node?.kind === "result"
          ? (run?.report ?? { status: run?.status, errorCode: run?.errorCode })
          : { status: node?.status };
  return (
    <aside className={`panel inspector ${active ? "mobile-active" : ""}`}>
      <header className="panel-head">
        <h2>
          <Code2 />
          步骤详情
        </h2>
        {node?.tool && (
          <button
            className="icon"
            title="刷新调用详情"
            aria-label="刷新调用详情"
            onClick={() => setRevision((n) => n + 1)}
          >
            <RefreshCw />
          </button>
        )}
      </header>
      {!node || !run ? (
        <Empty>
          <Code2 />
          <p>选择一个流程节点</p>
        </Empty>
      ) : (
        <div className="inspect-body">
          <Status value={node.status} />
          <h2>{node.title}</h2>
          <p className="note mono">{node.subtitle}</p>
          <div className="tabs" role="tablist" aria-label="步骤内容">
            {[
              ["input", "输入"],
              ["output", "输出"],
              ["source", "源码职责"],
            ].map(([value, title]) => (
              <button
                key={value}
                role="tab"
                aria-selected={tab === value}
                aria-controls="detail-content"
                onClick={() => setTab(value)}
              >
                {title}
              </button>
            ))}
          </div>
          <div id="detail-content" role="tabpanel">
            {tab === "source" ? (
              <>
                <p className="note">
                  静态映射 · Java 路径相对于 src/main/java/com/nexusagent/
                </p>
                <ol className="source-list">
                  {lesson?.sources.map((s) => (
                    <li key={s}>
                      <code>{s}</code>
                    </li>
                  ))}
                </ol>
              </>
            ) : error ? (
              <p className="error" role="alert">
                {error}
              </p>
            ) : node.tool && !detail ? (
              <p className="note">读取调用详情…</p>
            ) : (
              <JsonView value={tab === "input" ? input : output} />
            )}
          </div>
          {detail?.payloadOmitted && (
            <p className="warning">
              部分字段因范围或大小限制已省略：{detail.omittedFields.join(", ")}
            </p>
          )}
          <section className="explanation">
            <h3>为什么有这一步</h3>
            <p>{lesson?.why}</p>
            <p className="boundary">{lesson?.boundary}</p>
          </section>
          <dl className="facts">
            <dt>runId</dt>
            <dd>
              <code>{run.runId}</code>
            </dd>
            <dt>traceId</dt>
            <dd>
              <code>{run.traceId}</code>
            </dd>
            {node.tool && (
              <>
                <dt>observationId</dt>
                <dd>
                  <code>{node.tool.observationId}</code>
                </dd>
                <dt>invocationId</dt>
                <dd>
                  <code>{node.tool.invocationId}</code>
                </dd>
                <dt>retryOf</dt>
                <dd>
                  <code>{node.tool.retryOf ?? "无"}</code>
                </dd>
                <dt>开始记录</dt>
                <dd>{time(node.tool.createdAt)}</dd>
                <dt>完成记录</dt>
                <dd>{time(node.tool.finishedAt)}</dd>
              </>
            )}
            <dt>数据来源</dt>
            <dd>后端持久化记录</dd>
          </dl>
        </div>
      )}
    </aside>
  );
}
