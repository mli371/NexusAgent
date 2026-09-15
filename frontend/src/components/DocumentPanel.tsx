import { useEffect, useRef, useState } from "react";
import {
  ChevronLeft,
  ChevronRight,
  FileText,
  RefreshCw,
  Upload,
  X,
  RotateCw,
  Workflow,
} from "lucide-react";
import { BackendClient, errorText } from "../api/client";
import type { DocumentInfo } from "../api/types";
import type { EmbeddingStatus } from "../query/types";
import { Empty } from "./shared";

export function DocumentPanel({
  api,
  selected,
  onSelect,
  onClose,
  queryMode = false,
  onPrepare,
}: {
  api: BackendClient;
  selected: DocumentInfo[];
  onSelect(docs: DocumentInfo[]): void;
  onClose(): void;
  queryMode?: boolean;
  onPrepare?(docs: DocumentInfo[]): void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [offset, setOffset] = useState(0),
    [revision, setRevision] = useState(0);
  const [rows, setRows] = useState<DocumentInfo[]>([]),
    [error, setError] = useState("");
  const [loading, setLoading] = useState(false),
    [uploading, setUploading] = useState(false);
  const [visibility, setVisibility] = useState("TENANT");
  const [statuses, setStatuses] = useState<Record<string, EmbeddingStatus>>({});
  const [statusErrors, setStatusErrors] = useState<Record<string, string>>({});
  const [rebuilding, setRebuilding] = useState("");
  const uploadAbort = useRef<AbortController | undefined>(undefined);
  useEffect(() => {
    dialog.current?.showModal();
    return () => uploadAbort.current?.abort();
  }, []);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError("");
    setRows([]);
    api
      .listDocuments(offset, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) setRows(value);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(errorText(e));
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [api, offset, revision]);
  useEffect(() => {
    const abort = new AbortController();
    setStatuses({}); setStatusErrors({});
    if (queryMode) for (const row of rows) {
      void api.embeddingStatus(row.id, abort.signal).then(status => {
        if (!abort.signal.aborted) setStatuses(old => ({ ...old, [row.id]: status }));
      }).catch(failure => {
        if (!abort.signal.aborted) setStatusErrors(old => ({ ...old, [row.id]: errorText(failure) }));
      });
    }
    return () => abort.abort();
  }, [api, rows, queryMode]);
  const rebuild = async (doc: DocumentInfo) => {
    if (rebuilding || uploading || !window.confirm(`为 ${doc.originalFilename} 重建向量？这会向 OpenAI 发送所有 child 文本，替换当前向量并产生费用。不会重新分块。只使用已获授权的合成资料。`)) return;
    const abort = new AbortController(); uploadAbort.current = abort;
    setRebuilding(doc.id); setError("");
    try {
      const result = await api.rebuildEmbeddings(doc.id, abort.signal);
      if (!abort.signal.aborted) setStatuses(old => ({ ...old, [doc.id]: result }));
    } catch (failure) {
      if (!abort.signal.aborted) setError(`${errorText(failure)} 请求未自动重试；先刷新状态确认结果。`);
    } finally { if (!abort.signal.aborted) setRebuilding(""); }
  };
  const toggle = (doc: DocumentInfo) => {
    if (selected.some((d) => d.id === doc.id))
      onSelect(selected.filter((d) => d.id !== doc.id));
    else if (selected.length >= 10) setError("每次任务最多选择十份文档。");
    else onSelect([...selected, doc]);
  };
  const upload = async (files: FileList | null) => {
    if (!files?.length || uploading) return;
    const abort = new AbortController();
    uploadAbort.current = abort;
    setUploading(true);
    setError("");
    try {
      for (const file of Array.from(files)) {
        if (!/\.(md|txt)$/i.test(file.name)) throw new Error("unsupported");
        await api.upload(file, visibility, abort.signal);
        if (abort.signal.aborted) return;
      }
      setOffset(0);
      setRevision((n) => n + 1);
    } catch (e) {
      if (!abort.signal.aborted)
        setError(
          e instanceof Error && e.message === "unsupported"
            ? "首版只处理 .md 和 .txt；此前成功上传的文件会保留。"
            : `${errorText(e)} 上传不会自动重试，请先刷新列表确认文件是否已存在。`,
        );
    } finally {
      if (!abort.signal.aborted) {
        setUploading(false);
        setRevision((n) => n + 1);
      }
    }
  };
  return (
    <dialog
      ref={dialog}
      onCancel={onClose}
      className="document-dialog"
      aria-labelledby="document-title"
    >
      <header className="panel-head">
        <h2 id="document-title">文档 · 已选 {selected.length}/10</h2>
        <button
          className="icon"
          onClick={onClose}
          title="关闭"
          aria-label="关闭文档面板"
        >
          <X />
        </button>
      </header>
      <div className="document-body">
        <div className="upload-bar">
          <label>
            上传范围
            <select
              aria-label="上传可见性"
              value={visibility}
              onChange={(e) => setVisibility(e.target.value)}
            >
              <option value="TENANT">TENANT · 同租户</option>
              <option value="PRIVATE">PRIVATE · 所有者</option>
            </select>
          </label>
          <label className={`button upload ${uploading ? "disabled" : ""}`}>
            <Upload />
            {uploading ? "上传中" : "上传文档"}
            <input
              aria-label="上传文档"
              type="file"
              multiple
              accept=".md,.txt,text/plain,text/markdown"
            disabled={uploading || !!rebuilding}
              onChange={(e) => {
                void upload(e.target.files);
                e.target.value = "";
              }}
            />
          </label>
          <button
            className="icon"
            onClick={() => setRevision((n) => n + 1)}
            title="刷新文档"
            aria-label="刷新文档"
          >
            <RefreshCw />
          </button>
        </div>
        <p className="note">
          UTF-8 Markdown / TXT。默认上限 25
          MiB，实际以后端配置为准。上传不会自动触发分块或审批。
        </p>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="document-list" aria-busy={loading}>
          {loading ? (
            <Empty>读取文档中…</Empty>
          ) : rows.length === 0 ? (
            <Empty>
              <FileText />
              <p>当前身份暂无可见文档</p>
            </Empty>
          ) : (
            rows.map((doc) => queryMode ? (
              <div className="document-row qa-document" key={doc.id}>
                <label className="qa-document-select">
                  <input type="checkbox" aria-label={`选择 ${doc.originalFilename}`}
                    checked={selected.some(d => d.id === doc.id)}
                    disabled={!selected.some(d => d.id === doc.id) && (!statuses[doc.id]?.complete || statuses[doc.id]?.provider !== "openai")}
                    onChange={() => toggle(doc)} />
                  <span><strong>{doc.originalFilename}</strong><small>{doc.visibility} · {doc.status}</small><code>{doc.id}</code></span>
                </label>
                <div className="qa-readiness">
                  {statuses[doc.id] ? <>
                    <span className={`status ${statuses[doc.id].complete && statuses[doc.id].provider === "openai" ? "good" : "waiting"}`}>
                      {statuses[doc.id].provider !== "openai" ? "本地向量模式" : statuses[doc.id].complete ? "问答就绪" : statuses[doc.id].childChunkCount === 0 ? "待分块" : statuses[doc.id].mismatchedChildChunkCount > 0 ? "模型不匹配" : "待生成向量"}
                    </span>
                    <span className="note">{statuses[doc.id].matchingChildChunkCount}/{statuses[doc.id].childChunkCount} · {statuses[doc.id].modelName}</span>
                    <div className="qa-doc-actions">
                      {!statuses[doc.id].complete && <button disabled={!!rebuilding || uploading} onClick={() => onPrepare?.([doc])}><Workflow />处理文档</button>}
                      {statuses[doc.id].provider === "openai" && statuses[doc.id].childChunkCount > 0 && <button disabled={!!rebuilding || uploading} onClick={() => void rebuild(doc)}><RotateCw />{rebuilding === doc.id ? "重建中" : "重建向量"}</button>}
                    </div>
                  </> : <span className="note">{statusErrors[doc.id] ?? "读取向量状态…"}</span>}
                </div>
              </div>
            ) : (
              <label className="document-row" key={doc.id}>
                <input
                  type="checkbox"
                  checked={selected.some((d) => d.id === doc.id)}
                  onChange={() => toggle(doc)}
                />
                <FileText />
                <span>
                  <strong>{doc.originalFilename}</strong>
                  <small>
                    {doc.status} · {doc.visibility} ·{" "}
                    {(doc.sizeBytes / 1024).toFixed(1)} KiB
                  </small>
                  <code>{doc.id}</code>
                </span>
              </label>
            ))
          )}
        </div>
        <footer className="document-footer">
          <button
            className="icon"
            aria-label="上一页"
            title="上一页"
            disabled={offset === 0 || loading}
            onClick={() => setOffset((n) => Math.max(0, n - 25))}
          >
            <ChevronLeft />
          </button>
          <span>第 {offset / 25 + 1} 页</span>
          <button
            className="icon"
            aria-label="下一页"
            title="下一页"
            disabled={rows.length < 25 || loading}
            onClick={() => setOffset((n) => n + 25)}
          >
            <ChevronRight />
          </button>
          <button className="primary" onClick={onClose}>
            完成选择
          </button>
        </footer>
      </div>
    </dialog>
  );
}
