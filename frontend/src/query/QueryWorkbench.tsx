import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowUp, BookOpen, Files, FileText, MessagesSquare, RefreshCw, ShieldCheck, SlidersHorizontal, Square, Trash2 } from "lucide-react";
import { BackendClient, errorText } from "../api/client";
import { validIdentity, type DocumentInfo, type Identity } from "../api/types";
import { DocumentPanel } from "../components/DocumentPanel";
import { Empty } from "../components/shared";
import { QueryFlow } from "./QueryFlow";
import { AnswerText, QueryInspector } from "./QueryInspector";
import type { StageId } from "./learning";
import type { Capabilities, QueryScope } from "./types";
import { useQuery } from "./useQuery";
import { recentHistory, sameScope } from "./history";
import { ContextCoverage } from "./ContextCoverage";

export function QueryWorkbench({ identity, onIdentity, onManage, onBusy }: {
  identity: Identity; onIdentity(value: Identity): void; onManage(docs: DocumentInfo[]): void; onBusy(busy: boolean): void;
}) {
  const api = useMemo(() => new BackendClient(identity), [identity]);
  const query = useQuery(api);
  const [capabilities, setCapabilities] = useState<Capabilities>();
  const [connectionError, setConnectionError] = useState("");
  const [revision, setRevision] = useState(0);
  const [scope, setScope] = useState<QueryScope>("library");
  const [documents, setDocuments] = useState<DocumentInfo[]>([]);
  const [showDocuments, setShowDocuments] = useState(false), [showIdentity, setShowIdentity] = useState(false);
  const [draftIdentity, setDraftIdentity] = useState(identity), [identityError, setIdentityError] = useState("");
  const [question, setQuestion] = useState(""), [topK, setTopK] = useState(5), [budget, setBudget] = useState(4000);
  const [consent, setConsent] = useState(false);
  const [stage, setStage] = useState<StageId>("access_check"), [citationMarker, setCitationMarker] = useState<string>();
  const [view, setView] = useState("conversation");
  const bottom = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const abort = new AbortController(); setCapabilities(undefined); setConnectionError("");
    void api.queryCapabilities(abort.signal).then(value => { if (!abort.signal.aborted) setCapabilities(value); })
      .catch(error => { if (!abort.signal.aborted) setConnectionError(errorText(error)); });
    return () => abort.abort();
  }, [api, revision]);
  useEffect(() => { onBusy(query.running); return () => onBusy(false); }, [query.running, onBusy]);
  useEffect(() => { bottom.current?.scrollIntoView?.({ block: "nearest" }); }, [query.turns.length, query.current?.status]);
  const ready = capabilities?.liveQueryReady && capabilities.activeAnswerGenerator === "openai-responses"
    && capabilities.embedding?.provider === "openai" && !!capabilities.answerModel
    && capabilities.queryScopes?.includes("library") && capabilities.queryScopes.includes("documents")
    && capabilities.pageFollowUpSupported === true && capabilities.maxHistoryTurns === 3;
  const valid = question.trim().length > 0 && question.length <= 2000 && Number.isInteger(topK) && topK >= 1 && topK <= 50
    && Number.isInteger(budget) && budget >= 1 && budget <= 12000 && (scope === "library" || documents.length > 0);
  const send = () => {
    if (!ready || !valid || !consent || query.running) return;
    setCitationMarker(undefined); setStage("access_check"); setView("conversation");
    const request = { sessionId: query.sessionId, question: question.trim(), scope,
      documentIds: scope === "documents" ? documents.map(d => d.id) : [], topK, contextBudgetChars: budget, debug: true as const };
    void query.submit({ ...request, history: recentHistory(query.turns, request) }, capabilities!.answerModel!);
    setQuestion("");
  };
  const changeIdentity = () => {
    const next = { tenantId: draftIdentity.tenantId.trim(), actorId: draftIdentity.actorId.trim() };
    if (!validIdentity(next)) { setIdentityError("身份只能包含 1–120 个字母、数字及 . _ : -。"); return; }
    if (query.running && !window.confirm("切换身份会停止本地等待并清空问答。远端模型可能已经产生用量，继续吗？")) return;
    if (next.tenantId !== identity.tenantId || next.actorId !== identity.actorId) {
      query.clear(); setDocuments([]); setConsent(false); setQuestion(""); setCitationMarker(undefined); onIdentity(next);
    }
  };
  const changeScope = (next: QueryScope, docs = documents) => {
    if (query.running) return;
    if (!sameScope({ scope, documentIds: documents.map(d => d.id) }, { scope: next, documentIds: docs.map(d => d.id) })) {
      query.clear(); setCitationMarker(undefined); setStage("access_check");
    }
    setScope(next); setDocuments(docs);
  };
  const selectStage = (id: StageId) => { setStage(id); setCitationMarker(undefined); setView("inspector"); };
  const citation = query.current?.response?.citations.find(c => c.citationMarker === citationMarker);
  return <div className="qa-page">
    <header className="topbar">
      <div className="brand"><img src="/workflow.svg" alt="" width="25" height="25" /><h1>NexusAgent</h1></div>
      <span className="brand-caption">知识问答</span>
      <div className="top-actions">
        <button className="identity" onClick={() => setShowIdentity(!showIdentity)} title="切换本地演示身份"><ShieldCheck /><span>{identity.tenantId} / {identity.actorId}</span></button>
        <button onClick={() => setShowDocuments(true)} disabled={query.running}><Files />文档与准备</button>
      </div>
    </header>
    <div className="notice"><ShieldCheck /><span>Header 演示身份，不是生产认证。问题、最近最多 3 轮有界问答与检索片段将发送至 OpenAI；请只使用获准的合成资料。</span></div>
    {showIdentity && <form className="identity-form" onSubmit={e => { e.preventDefault(); changeIdentity(); }}>
      <label>Tenant<input aria-label="Tenant" maxLength={120} value={draftIdentity.tenantId} onChange={e => setDraftIdentity(old => ({ ...old, tenantId: e.target.value }))} /></label>
      <label>Actor<input aria-label="Actor" maxLength={120} value={draftIdentity.actorId} onChange={e => setDraftIdentity(old => ({ ...old, actorId: e.target.value }))} /></label>
      <button type="submit" className="primary">切换身份</button>{identityError && <p role="alert" className="error">{identityError}</p>}
    </form>}
    <div className="qa-toolbar">
      <div className="qa-scope" role="group" aria-label="检索范围">
        <button aria-pressed={scope === "library"} onClick={() => changeScope("library")} disabled={query.running}><BookOpen />整个知识库</button>
        <button aria-pressed={scope === "documents"} onClick={() => changeScope("documents")} disabled={query.running}><Files />指定文档{documents.length > 0 ? ` · ${documents.length}` : ""}</button>
      </div>
      {scope === "documents" && <button className="text-button" onClick={() => setShowDocuments(true)} disabled={query.running}>选择文档</button>}
      <span className="qa-model" title={capabilities?.answerModel ?? ""}><span className={`qa-dot ${ready ? "ready" : ""}`} />{ready ? capabilities?.answerModel : "真实问答未就绪"}</span>
      <button className="icon" title="刷新模型配置" aria-label="刷新模型配置" disabled={query.running} onClick={() => setRevision(old => old + 1)}><RefreshCw /></button>
    </div>
    {!ready && <div className="warning page-error" role="status">{connectionError || (capabilities
      ? "后端未开启当前页面多轮真实问答。需要更新后端，并配置 NEXUS_ANSWER_PROVIDER=openai 与 NEXUS_EMBEDDINGS_PROVIDER=openai；不会自动退回模板。"
      : "正在读取后端模型配置…")}</div>}
    {query.error && <div className="error page-error" role="alert">{query.error}</div>}
    <nav className="mobile-tabs" aria-label="问答工作区">{[["conversation", "对话"], ["flow", "执行流程"], ["inspector", "阶段详情"]].map(([id, label]) =>
      <button key={id} aria-pressed={view === id} onClick={() => setView(id)}>{label}</button>)}</nav>
    <main className="workspace qa-workspace">
      <section className={`panel conversation ${view === "conversation" ? "mobile-active" : ""}`}>
        <header className="panel-head"><h2><MessagesSquare />知识对话</h2><button className="icon" title="清空本页问答" aria-label="清空本页问答" disabled={query.running || !query.turns.length} onClick={() => { query.clear(); setCitationMarker(undefined); }}><Trash2 /></button></header>
        <div className="chat-body qa-chat" aria-live="polite">
          {query.turns.length === 0 ? <Empty><BookOpen /><h3>从知识库提问</h3><p>{scope === "library" ? "范围：当前身份可访问且向量就绪的文档" : `范围：已选 ${documents.length} 份文档`}</p></Empty>
            : query.turns.map((turn, index) => <article className={`qa-turn ${query.current?.traceId === turn.traceId ? "selected" : ""}`} key={turn.traceId}>
              <button className="qa-turn-heading" onClick={() => { query.select(turn.traceId); setCitationMarker(undefined); }} title="查看这次问答的阶段记录"><span>第 {index + 1} 问 · {turn.request.scope === "library" ? "知识库" : "指定文档"}</span><code>{turn.traceId.slice(0, 8)}</code></button>
              <p className="question qa-question">{turn.request.question}</p>
              {turn.response ? <>
                <p className="speaker qa-answer-label">{turn.response.answerStatus === "needs_clarification" ? "请补充问题"
                  : `${turn.response.answerStatus === "answered" ? "回答" : turn.response.answerStatus === "refused" ? "模型拒答" : "证据不足"} · ${turn.response.answerModel}`}</p>
                <AnswerText text={turn.response.answer} citations={turn.response.citations} onCitation={c => { query.select(turn.traceId); setCitationMarker(c.citationMarker); setView("inspector"); }} />
                <div className="qa-citations">{turn.response.citations.map(c => <button key={c.citationMarker} title={c.originalFilename} onClick={() => { query.select(turn.traceId); setCitationMarker(c.citationMarker); setView("inspector"); }}><FileText /><span>{c.citationMarker} {c.originalFilename}</span></button>)}</div>
                <div className="qa-scope-result">{["needs_clarification", "refused"].includes(turn.response.queryResolution?.status ?? "")
                  ? "未执行检索与最终回答生成"
                  : `检索 ${turn.response.scope.searchedDocumentCount} / ${turn.response.scope.accessibleDocumentCount} 份文档 · ${{ hit: "上下文缓存命中（精确）", semantic_hit: "上下文缓存命中（语义）", miss: "上下文缓存未命中", bypassed: "缓存已绕过" }[turn.response.retrievalCacheStatus]}`}</div>
                <ContextCoverage response={turn.response} />
                {turn.response.scope.excludedDocumentCount > 0 && <details className="qa-exclusions"><summary>未纳入检索：{turn.response.scope.excludedDocumentCount} 份</summary>
                  {Object.entries(turn.response.scope.exclusions).map(([reason, count]) => <p key={reason}>{({ CHUNKING_REQUIRED: "待分块", EMBEDDING_INCOMPLETE: "向量不完整", EMBEDDING_MODEL_MISMATCH: "模型不匹配" }[reason] ?? reason)}：{count}</p>)}
                </details>}
              </> : turn.status === "failed" ? <p className="error" role="alert">{turn.error}</p>
                : turn.status === "interrupted" ? <p className="warning">已停止等待，结果未确认。远端模型可能已经产生用量。</p>
                  : <p className="qa-waiting"><span className="qa-dot ready" />{turn.events.filter(e => e.stage).at(-1)?.stage?.stage ?? "等待预检查"}</p>}
            </article>)}
          <div ref={bottom} />
        </div>
        <form className="composer qa-composer" onSubmit={e => { e.preventDefault(); send(); }}>
          <textarea aria-label="知识库问题" placeholder="例如：这些发布会资料有哪些主要差异？" value={question} maxLength={2000} disabled={query.running} onChange={e => setQuestion(e.target.value)} />
          <div className="composer-foot">
            <details className="qa-settings"><summary title="检索参数"><SlidersHorizontal /><span>参数</span></summary><div>
              <label>Top K<input aria-label="Top K" type="number" min={1} max={50} value={topK} disabled={query.running} onChange={e => setTopK(Number(e.target.value))} /></label>
              <label>上下文字符预算<input aria-label="上下文字符预算" type="number" min={1} max={12000} value={budget} disabled={query.running} onChange={e => setBudget(Number(e.target.value))} /></label>
            </div></details>
            <span>{question.length}/2000</span>
            {query.running ? <button type="button" className="icon" title="停止等待" aria-label="停止等待" onClick={query.stop}><Square /></button>
              : <button className="icon primary" title="发送问题" aria-label="发送问题" disabled={!ready || !valid || !consent}><ArrowUp /></button>}
          </div>
          <label className="qa-consent"><input type="checkbox" checked={consent} disabled={query.running} onChange={e => setConsent(e.target.checked)} />允许外发问题、最近最多 3 轮有界问答和检索片段；问题补全可能增加 API 用量</label>
        </form>
      </section>
      <QueryFlow turn={query.current} selection={stage} onSelect={selectStage} active={view === "flow"} />
      <QueryInspector turn={query.current} stageId={stage} citation={citation} active={view === "inspector"} onCloseCitation={() => setCitationMarker(undefined)} />
    </main>
    <footer className="bottom"><span>页面多轮 · 最近 3 轮参与补全 · 刷新或切换范围清空</span><span>启发式重排 · 字符预算 · 引用关联不等于事实性验证</span></footer>
    {showDocuments && <DocumentPanel api={api} selected={documents} queryMode
      onSelect={docs => changeScope("documents", docs)} onClose={() => setShowDocuments(false)}
      onPrepare={docs => { setShowDocuments(false); onManage(docs); }} />}
  </div>;
}
