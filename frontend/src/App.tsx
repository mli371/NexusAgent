import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowUp,
  Files,
  FolderOpen,
  MessagesSquare,
  Plus,
  RefreshCw,
  ShieldCheck,
  Square,
  Workflow,
} from "lucide-react";
import { BackendClient, errorText } from "./api/client";
import {
  isTerminal,
  isUuid,
  validIdentity,
  type DocumentInfo,
  type Identity,
} from "./api/types";
import { useRun } from "./run/useRun";
import { recentRuns, SubmissionKey } from "./run/state";
import { flowNodes } from "./run/flow";
import { DocumentPanel } from "./components/DocumentPanel";
import { FlowPanel } from "./components/FlowPanel";
import { Inspector } from "./components/Inspector";
import { ApprovalPanel } from "./components/ApprovalPanel";
import { Empty, Status } from "./components/shared";
import { QueryWorkbench } from "./query/QueryWorkbench";

export default function App() {
  const [identity, setIdentity] = useState<Identity>({
    tenantId: "default",
    actorId: "anonymous",
  });
  const [mode, setMode] = useState(() => new URLSearchParams(location.search).get("view") === "documents" ? "documents" : "query");
  const [queryBusy, setQueryBusy] = useState(false);
  const [preparedDocuments, setPreparedDocuments] = useState<DocumentInfo[]>([]);
  const changeIdentity = (next: Identity) => {
    setPreparedDocuments([]);
    setIdentity(next);
  };
  const switchMode = (next: string, docs: DocumentInfo[] = []) => {
    if (mode === next) return;
    if (queryBusy && !window.confirm("离开会停止本地等待并清空问答。远端模型可能已产生费用，继续吗？")) return;
    setPreparedDocuments(docs); setMode(next);
    const url = new URL(location.href); url.searchParams.set("view", next); history.replaceState(null, "", url);
  };
  return (
    <>
    <nav className="app-modes" aria-label="应用视图">
      <button aria-pressed={mode === "query"} onClick={() => switchMode("query")}><MessagesSquare />知识问答</button>
      <button aria-pressed={mode === "documents"} onClick={() => switchMode("documents")}><Workflow />文档处理</button>
      <span>学习工作台</span>
    </nav>
    {mode === "query" ? <QueryWorkbench key={`query:${JSON.stringify(identity)}`} identity={identity} onIdentity={changeIdentity}
      onBusy={setQueryBusy} onManage={docs => switchMode("documents", docs)} /> : <Workbench
      key={JSON.stringify(identity)}
      identity={identity}
      onIdentity={changeIdentity}
      initialDocuments={preparedDocuments}
    />}
    </>
  );
}

function Workbench({
  identity,
  onIdentity,
  initialDocuments,
}: {
  identity: Identity;
  onIdentity(value: Identity): void;
  initialDocuments: DocumentInfo[];
}) {
  const api = useMemo(() => new BackendClient(identity), [identity]);
  const lifetime = useMemo(() => new AbortController(), []);
  const keys = useRef(new SubmissionKey());
  const busyRef = useRef(false);
  const [recent, setRecent] = useState(() => recentRuns(identity));
  const [runId, setRunId] = useState(() => initialDocuments.length ? "" : recent[0] ?? ""),
    [runInput, setRunInput] = useState("");
  const [revision, setRevision] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [question, setQuestion] = useState(initialDocuments.length ? "Approve processing: 检查所选文档，并为缺失的分块或 embedding 提出人工审批申请。" : ""),
    [documents, setDocuments] = useState<DocumentInfo[]>(initialDocuments);
  const [runDocuments, setRunDocuments] = useState<DocumentInfo[]>([]);
  const [showDocuments, setShowDocuments] = useState(false),
    [showIdentity, setShowIdentity] = useState(false);
  const [draftIdentity, setDraftIdentity] = useState(identity);
  const [view, setView] = useState("conversation"),
    [selection, setSelection] = useState("");
  const live = useRun(api, runId, revision);
  const nodes = useMemo(
    () => flowNodes(live.run, live.tools, live.events),
    [live.run, live.tools, live.events],
  );
  const chosen =
    nodes.find((n) => n.id === selection) ??
    nodes.find((n) => n.approval?.status === "PENDING") ??
    nodes.at(-1);
  const running = !!runId && (!live.run || !isTerminal(live.run));
  useEffect(() => () => lifetime.abort(), [lifetime]);
  useEffect(() => {
    if (live.denied) {
      setDocuments([]);
      setRunDocuments([]);
    }
  }, [live.denied]);
  useEffect(() => {
    const abort = new AbortController();
    setRunDocuments([]);
    if (live.run?.documentIds) {
      const load = async () => {
        const selected: DocumentInfo[] = [];
        for (const id of live.run!.documentIds!)
          selected.push(await api.document(id, abort.signal));
        if (!abort.signal.aborted) {
          setDocuments(selected);
          setRunDocuments(selected);
        }
      };
      void load().catch((e) => {
        if (!abort.signal.aborted) {
          setDocuments([]);
          setError(errorText(e));
        }
      });
    }
    return () => abort.abort();
  }, [
    api,
    live.run?.runId,
    live.run?.status,
    live.run?.documentIds?.join(","),
  ]);
  const openRun = (id: string) => {
    if (!isUuid(id)) {
      setError("runId 必须是完整 UUID。");
      return;
    }
    setError("");
    setSelection("");
    setRunId(id);
    setRevision((n) => n + 1);
    setRecent(recentRuns(identity, id));
  };
  const start = async () => {
    if (busyRef.current || running) return;
    const text = question.trim(),
      ids = documents.map((d) => d.id);
    if (!text || text.length > 2000 || ids.length < 1 || ids.length > 10) {
      setError("请输入 1–2000 字符的问题，并选择 1–10 份文档。");
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await api.create(
        text,
        ids,
        keys.current.get(text, ids),
        lifetime.signal,
      );
      if (lifetime.signal.aborted) return;
      keys.current.clear();
      setQuestion("");
      openRun(result.runId);
      setView("flow");
    } catch (e) {
      if (!lifetime.signal.aborted)
        setError(
          `${errorText(e)} 未确认结果时，保持相同问题和文档重试会沿用本次幂等键。`,
        );
    } finally {
      if (!lifetime.signal.aborted) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  };
  const mutate = async (action: "APPROVE" | "REJECT" | "CANCEL") => {
    if (busyRef.current || !live.run) return;
    const current = live.run;
    if (
      action === "CANCEL" &&
      !window.confirm(
        "请求取消当前任务？已开始的写操作可能先完成，取消不保证回滚。",
      )
    )
      return;
    busyRef.current = true;
    setBusy(true);
    setError("");
    try {
      if (action === "CANCEL") await api.cancel(current.runId, lifetime.signal);
      else if (current.pendingApproval)
        await api.decide(
          current.runId,
          current.pendingApproval.approvalId,
          action,
          lifetime.signal,
        );
    } catch (e) {
      if (!lifetime.signal.aborted)
        setError(`${errorText(e)} 已重新读取状态；没有自动重试操作。`);
    } finally {
      if (!lifetime.signal.aborted) {
        busyRef.current = false;
        setBusy(false);
        setRevision((n) => n + 1);
      }
    }
  };
  const newTask = () => {
    if (
      running &&
      !window.confirm(
        "只离开当前任务视图，不会取消后端执行或自动批准操作。继续吗？",
      )
    )
      return;
    setRunId("");
    setSelection("");
    setQuestion("");
    setError("");
    setView("conversation");
    keys.current.clear();
  };
  const applyIdentity = () => {
    const next = {
      tenantId: draftIdentity.tenantId.trim(),
      actorId: draftIdentity.actorId.trim(),
    };
    if (!validIdentity(next)) {
      setError("身份只允许 1–120 个字母、数字及 . _ : - 字符。");
      return;
    }
    onIdentity(next);
    setShowIdentity(false);
  };
  const onRevoke = () => {
    setSelection("");
    setDocuments([]);
    setRunDocuments([]);
    setRevision((n) => n + 1);
  };
  return (
    <>
      <header className="topbar">
        <div className="brand">
          <Workflow />
          <h1>NexusAgent</h1>
        </div>
        <span className="brand-caption">学习工作台</span>
        <div className="top-actions">
          <button
            className="identity"
            onClick={() => setShowIdentity((v) => !v)}
            title="切换本地演示身份"
          >
            <ShieldCheck />
            <span>
              {identity.tenantId} / {identity.actorId}
            </span>
          </button>
          <button onClick={() => setShowDocuments(true)}>
            <Files />
            文档 <span className="counter">{documents.length}</span>
          </button>
        </div>
      </header>
      <div className="notice">
        <ShieldCheck />
        <span>
          Header 演示身份，不是生产登录。原始文件留在 MinIO，任务和观察记录由
          PostgreSQL 保存。
        </span>
      </div>
      {showIdentity && (
        <form
          className="identity-form"
          onSubmit={(e) => {
            e.preventDefault();
            applyIdentity();
          }}
        >
          <label>
            Tenant
            <input
              aria-label="Tenant"
              value={draftIdentity.tenantId}
              maxLength={120}
              onChange={(e) =>
                setDraftIdentity((v) => ({ ...v, tenantId: e.target.value }))
              }
            />
          </label>
          <label>
            Actor
            <input
              aria-label="Actor"
              value={draftIdentity.actorId}
              maxLength={120}
              onChange={(e) =>
                setDraftIdentity((v) => ({ ...v, actorId: e.target.value }))
              }
            />
          </label>
          <button type="submit" className="primary">
            切换身份
          </button>
          <span className="note">切换会清空当前视图，不会取消后台任务。</span>
        </form>
      )}
      <div className="toolbar">
        <div className="mode">
          <Workflow />
          文档处理 Agent
        </div>
        <form
          className="open-run"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busyRef.current) openRun(runInput.trim());
          }}
        >
          <input
            aria-label="打开 runId"
            placeholder="输入 runId 恢复查看"
            value={runInput}
            disabled={busy}
            onChange={(e) => setRunInput(e.target.value)}
          />
          <button
            className="icon"
            aria-label="打开任务"
            title="打开任务"
            disabled={busy}
          >
            <FolderOpen />
          </button>
        </form>
        <select
          aria-label="最近任务"
          disabled={busy}
          value={recent.includes(runId) ? runId : ""}
          onChange={(e) => {
            if (e.target.value && !busyRef.current) openRun(e.target.value);
          }}
        >
          <option value="">本身份最近任务</option>
          {recent.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
        <button
          className="icon"
          title="新任务"
          aria-label="新任务"
          onClick={newTask}
          disabled={busy}
        >
          <Plus />
        </button>
      </div>
      <div className="runbar">
        <div>
          {live.run ? (
            <>
              <Status value={live.run.status} />
              <span className="note mono">
                {live.run.executionMode} · {live.run.model}
              </span>
            </>
          ) : (
            <span className="note">
              {runId ? "读取任务状态" : "独立任务 · 无多轮记忆"}
            </span>
          )}
        </div>
        <div>
          <span className="connection">{live.connection}</span>
          {runId && (
            <>
              <button
                className="icon"
                title="重新同步任务"
                aria-label="重新同步任务"
                onClick={() => setRevision((n) => n + 1)}
                disabled={busy}
              >
                <RefreshCw />
              </button>
              <button
                className="icon"
                title="请求取消"
                aria-label="请求取消"
                onClick={() => void mutate("CANCEL")}
                disabled={
                  !live.run ||
                  isTerminal(live.run) ||
                  live.run.cancellationRequested ||
                  busy
                }
              >
                <Square />
              </button>
            </>
          )}
        </div>
      </div>
      {(error || live.error) && (
        <div className="error page-error" role="alert">
          {error || live.error}
        </div>
      )}
      {live.run?.cancellationPending && (
        <div className="warning page-error">
          取消已请求，等待后端确认；不代表正在进行的操作已经回滚。
        </div>
      )}
      <nav className="mobile-tabs" aria-label="工作区">
        {[
          ["conversation", "对话"],
          ["flow", "执行流程"],
          ["inspector", "调用详情"],
        ].map(([id, name]) => (
          <button
            key={id}
            aria-pressed={view === id}
            onClick={() => setView(id)}
          >
            {name}
          </button>
        ))}
      </nav>
      <main className="workspace">
        <section
          className={`panel conversation ${view === "conversation" ? "mobile-active" : ""}`}
        >
          <header className="panel-head">
            <h2>
              <MessagesSquare />
              对话与审批
            </h2>
            <span className="note">一次提交，一个任务</span>
          </header>
          <div className="chat-body">
            {live.run ? (
              <>
                <div className="message">
                  <p className="speaker">{identity.actorId}</p>
                  <p className="question">
                    {live.run.question ??
                      "当前后端未返回问题，请确认已重启到新版本。"}
                  </p>
                  {runDocuments.map((doc) => (
                    <div className="attachment" key={doc.id}>
                      <Files />
                      <span>
                        {doc.originalFilename}
                        <small>
                          {doc.visibility} · {doc.status}
                        </small>
                      </span>
                    </div>
                  ))}
                </div>
                {live.run.pendingApproval && (
                  <ApprovalPanel
                    approval={live.run.pendingApproval}
                    busy={busy}
                    onDecision={(d) => void mutate(d)}
                    filename={
                      runDocuments.find(
                        (d) => d.id === live.run?.pendingApproval?.documentId,
                      )?.originalFilename
                    }
                  />
                )}
                {live.run.report ? (
                  <div className="report">
                    <p className="speaker">
                      {live.run.executionMode === "scripted"
                        ? "Scripted 诊断报告 · 未调用模型"
                        : "Pi 诊断报告"}
                    </p>
                    <p>{live.run.report.summary}</p>
                    {live.run.report.findings.map((finding, i) => (
                      <section key={`${finding.documentId}-${i}`}>
                        <h3>{finding.condition}</h3>
                        <code>{finding.documentId}</code>
                        <p>{finding.explanation}</p>
                        {finding.proposedNextAction && (
                          <p className="note">
                            建议下一步：{finding.proposedNextAction}
                          </p>
                        )}
                      </section>
                    ))}
                    {live.run.report.unresolved.length > 0 && (
                      <>
                        <h3>未解决项</h3>
                        <ul>
                          {live.run.report.unresolved.map((s, i) => (
                            <li key={i}>{s}</li>
                          ))}
                        </ul>
                      </>
                    )}
                  </div>
                ) : (
                  !live.run.pendingApproval && (
                    <p className="boundary">
                      {isTerminal(live.run)
                        ? `任务结束，没有最终报告。${live.run.errorCode ?? ""}`
                        : "系统状态：等待执行记录或报告。审批、模型建议和实际执行结果分别展示。"}
                    </p>
                  )
                )}
              </>
            ) : (
              <Empty>
                <MessagesSquare />
                <h3>从一份文档开始</h3>
                <p>选择或上传测试文档，再创建诊断任务。</p>
                <button onClick={() => setShowDocuments(true)}>
                  <Files />
                  选择文档
                </button>
              </Empty>
            )}
          </div>
          <form
            className="composer"
            onSubmit={(e) => {
              e.preventDefault();
              void start();
            }}
          >
            <div className="preset-row">
              <button
                type="button"
                disabled={running || busy}
                onClick={() =>
                  setQuestion(
                    "检查所选文档的处理状态，给出诊断报告，不提议修改。",
                  )
                }
              >
                填入只读诊断
              </button>
              <button
                type="button"
                disabled={running || busy}
                onClick={() =>
                  setQuestion(
                    "Approve processing: 检查所选文档，并为缺失的分块或 embedding 提出人工审批申请。",
                  )
                }
              >
                填入处理申请
              </button>
            </div>
            <textarea
              aria-label="任务问题"
              placeholder="描述需要检查的文档；每次发送是独立任务…"
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              maxLength={2000}
              disabled={busy || running}
            />
            <div className="composer-foot">
              <button
                type="button"
                className="text-button"
                onClick={() => setShowDocuments(true)}
              >
                <Files />
                {documents.length} 份文档
              </button>
              <span>{question.length}/2000</span>
              <button
                className="primary icon"
                type="submit"
                aria-label="提交任务"
                title="提交任务"
                disabled={
                  busy || running || !question.trim() || !documents.length
                }
              >
                <ArrowUp />
              </button>
            </div>
            <p className="note">
              Pi / scripted 模式由服务端配置。处理操作仍需逐次人工批准。
            </p>
          </form>
        </section>
        <FlowPanel
          nodes={nodes}
          selected={chosen?.id}
          events={live.events}
          active={view === "flow"}
          onSelect={(id) => {
            setSelection(id);
            setView("inspector");
          }}
        />
        <Inspector
          api={api}
          node={chosen}
          run={live.run}
          active={view === "inspector"}
          revoke={onRevoke}
        />
      </main>
      <footer className="bottom">
        <span>
          文档处理记录 · 问答使用独立视图 · 源码讲解不是完整调用栈
        </span>
        {live.run && (
          <code title={live.run.traceId}>traceId: {live.run.traceId}</code>
        )}
      </footer>
      {showDocuments && (
        <DocumentPanel
          api={api}
          selected={documents}
          onSelect={setDocuments}
          onClose={() => setShowDocuments(false)}
        />
      )}
    </>
  );
}
