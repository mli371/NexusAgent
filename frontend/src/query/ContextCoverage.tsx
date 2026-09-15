import type { ContextAllocation, CoverageMetadata, QueryResponse } from "./types";

export function coverageOf(response?: QueryResponse): CoverageMetadata | undefined {
  const metadata = response?.contextDebug.debugMetadata;
  return metadata?.allocationStrategy === "child-first-v1" && Array.isArray(metadata.allocations)
    ? metadata as CoverageMetadata : undefined;
}

const reason = (status: ContextAllocation["status"]) => ({ INCLUDED: "已纳入", DUPLICATE_PARENT: "同父块去重",
  CHILD_EXCEEDS_REMAINING_BUDGET: "剩余预算不足以保留完整 child" }[status]);

export function ContextCoverage({ response, detail = false }: { response: QueryResponse; detail?: boolean }) {
  const coverage = coverageOf(response);
  if (!coverage) return null;
  const reused = ["hit", "semantic_hit"].includes(response.retrievalCacheStatus);
  const excluded = coverage.allocations.filter(a => a.status !== "INCLUDED");
  const selected = coverage.allocations.filter(a => a.status === "INCLUDED");
  return <section className={`qa-coverage ${detail ? "detailed" : ""}`} aria-label="上下文覆盖">
    <p className="qa-coverage-summary">{reused ? "缓存分配 · " : ""}证据纳入 {coverage.selectedChildChunkCount} / {coverage.representativeChildCount} 个父块
      · 裁剪 {coverage.trimmedParentCount} 个 · 预算未纳入 {coverage.skippedBudgetCount} 个</p>
    <p className="note">正文 {coverage.usedBudgetChars} / {coverage.appliedBudgetChars} 字符 · 答案引用 {response.citations.length} 项</p>
    {detail && <>
      <p className="note">候选 {coverage.rerankedCandidateCount} · 完整 child 预留 {coverage.reservedChildChars} 字符 · 同父块去重 {coverage.skippedDuplicateParentCount}</p>
      {selected.map(row => {
        const parent = response.contextDebug.expandedParentContexts.find(p => p.parentChunkId === row.parentChunkId);
        const child = response.contextDebug.selectedChildChunks.find(c => c.childChunkId === row.childChunkId);
        const start = Number(child?.charStart), end = Number(child?.charEnd);
        const text = parent && start >= parent.charStart && end <= parent.charEnd
          ? parent.text.slice(start - parent.charStart, end - parent.charStart) : undefined;
        return <details className="qa-allocation" key={row.childChunkId}>
          <summary><strong>{row.originalFilename}</strong><span>child {row.childChars} → parent {row.allocatedChars} / {row.parentChars} 字符 · {row.parentTruncated ? "已裁剪" : "完整父块"}</span></summary>
          <p className="note">完整 child 证据</p><div className="qa-evidence">{text ?? "证据范围不可用"}</div>
          <p className="note">Child <code>{row.childChunkId}</code><br />Parent <code>{row.parentChunkId}</code></p>
          {parent && <details><summary>周边 parent [{parent.charStart}, {parent.charEnd})</summary><div className="qa-evidence">{parent.text}</div></details>}
        </details>;
      })}
    </>}
    {excluded.length > 0 && <details className="qa-exclusions"><summary>未纳入候选明细：{excluded.length} 项</summary>
      {excluded.map(row => <div className="qa-allocation" key={`${row.rerankedRank}-${row.childChunkId}`}>
        <strong>{row.originalFilename}</strong><p>{reason(row.status)} · child {row.childChars} 字符 · 排名 {row.rerankedRank}</p>
        {detail && <code>{row.childChunkId}</code>}
      </div>)}
    </details>}
  </section>;
}
