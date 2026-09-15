import { useState } from "react";
import { Code2, FileText } from "lucide-react";
import { Empty, JsonView } from "../components/shared";
import { lessons, stageData, type StageId } from "./learning";
import type { Citation, ParentContext, QueryTurn } from "./types";

export function evidenceParts(parent: ParentContext, citation: Citation) {
  const start = Math.max(parent.charStart, citation.charStart), end = Math.min(parent.charEnd, citation.charEnd);
  if (end <= start) return { before: parent.text, match: "", after: "" };
  return { before: parent.text.slice(0, start - parent.charStart), match: parent.text.slice(start - parent.charStart, end - parent.charStart), after: parent.text.slice(end - parent.charStart) };
}
export function AnswerText({ text, citations, onCitation }: { text: string; citations: Citation[]; onCitation(c: Citation): void }) {
  const byMarker = new Map(citations.map(c => [c.citationMarker, c]));
  return <div className="qa-answer-text">{text.split(/(\[C\d+\])/g).map((part, index) => {
    const citation = byMarker.get(part);
    return citation ? <button className="citation-marker" key={index} onClick={() => onCitation(citation)} title={citation.originalFilename}>{part}</button> : <span key={index}>{part}</span>;
  })}</div>;
}

export function QueryInspector({ turn, stageId, citation, active, onCloseCitation }: {
  turn?: QueryTurn; stageId: StageId; citation?: Citation; active: boolean; onCloseCitation(): void;
}) {
  const [tab, setTab] = useState("output");
  const { stage, output } = stageData(stageId, turn), lesson = lessons[stageId];
  const parent = citation && turn?.response?.contextDebug.expandedParentContexts.find(p => p.parentChunkId === citation.parentChunkId);
  const parts = citation && parent ? evidenceParts(parent, citation) : undefined;
  return <aside className={`panel inspector qa-inspector ${active ? "mobile-active" : ""}`}>
    <header className="panel-head"><h2>{citation ? <FileText /> : <Code2 />}{citation ? "引用证据" : "阶段详情"}</h2>
      {citation && <button className="text-button" onClick={onCloseCitation}>返回阶段</button>}</header>
    <div className="inspect-body">
      {citation && parent && parts ? <>
        <span className="qa-eyebrow">{citation.citationMarker} · {parent.truncated ? "父块已裁剪" : "完整父块"}</span>
        <h2>{citation.originalFilename}</h2>
        <p className="note">当前片段 [{parent.charStart}, {parent.charEnd}) · child 命中范围高亮</p>
        <div className="qa-evidence" data-testid="citation-evidence">{parts.before}<mark>{parts.match}</mark>{parts.after}</div>
        <dl className="facts">
          {Object.entries({ documentId: citation.documentId, parentChunkId: citation.parentChunkId, childChunkId: citation.childChunkId,
            chunkIndex: citation.chunkIndex, childOffsets: `[${citation.charStart}, ${citation.charEnd})`, section: citation.sectionTitle ?? "未提供" }).map(([key, value]) => <div className="qa-fact-row" key={key}><dt>{key}</dt><dd><code>{value}</code></dd></div>)}
        </dl>
      </> : <>
        <span className="qa-eyebrow">{stage ? `${stage.status} · ${stage.durationMs} ms · attempt ${stage.attempt}` : "未收到阶段记录"}</span>
        <h2>{lesson.title}</h2><code className="qa-method">{lesson.method}</code>
        <div className="tabs" role="tablist" aria-label="问答步骤内容">{[["input", "输入范围"], ["output", "实际输出"], ["source", "源码职责"]].map(([id, name]) =>
          <button role="tab" key={id} aria-selected={tab === id} aria-controls="query-detail" onClick={() => setTab(id)}>{name}</button>)}</div>
        <div role="tabpanel" id="query-detail">
          {tab === "source" ? <><p className="note">静态源码映射 · 不是运行时调用栈或模型推理</p>
            <code className="qa-source">src/main/java/com/nexusagent/{lesson.file}</code><p>{lesson.why}</p></>
            : tab === "input" ? <JsonView value={turn ? { question: turn.request.question, scope: turn.request.scope, documentIds: turn.request.documentIds,
                topK: turn.request.topK, contextBudgetChars: turn.request.contextBudgetChars, ...(turn.response ? { resolvedScope: turn.response.scope } : {}) } : null} />
              : output === undefined ? <Empty><Code2 /><p>{stage ? "阶段状态已记录，尚无可展示的输出" : "此步骤尚未执行"}</p></Empty>
                : <><p className="note">{stage?.summary?.reason === "cache_reuse" ? "来自上下文缓存 · 本次未执行此阶段 · 已复查权限与证据"
                  : turn?.response ? "最终授权响应中的阶段数据" : "实时阶段摘要 · 不含证据原文"}</p><JsonView value={output} /></>}
        </div>
        {tab !== "source" && <section className="explanation"><h3>职责</h3><p>{lesson.why}</p></section>}
      </>}
      <dl className="facts"><dt>traceId</dt><dd><code>{turn?.traceId ?? "尚未创建"}</code></dd><dt>数据来源</dt><dd>本次请求 · 服务端事件/响应</dd></dl>
    </div>
  </aside>;
}
