import { useEffect, useState } from "react";
import { Check, X } from "lucide-react";
import type { Approval } from "../api/types";
import { JsonView, Status, time } from "./shared";

export function ApprovalPanel({
  approval,
  busy,
  onDecision,
  filename,
}: {
  approval: Approval;
  busy: boolean;
  onDecision(decision: "APPROVE" | "REJECT"): void;
  filename?: string;
}) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const expired = Date.parse(approval.expiresAt) <= now;
  return (
    <section className="approval" aria-label="人工审批">
      <Status
        value={approval.status}
        label={approval.status === "PENDING" ? "待人工确认" : undefined}
      />
      <h3>
        {approval.action === "CHUNK"
          ? "生成父子分块"
          : "补齐缺失的子块 embedding"}
      </h3>
      <p className="filename">{filename ?? approval.documentId}</p>
      <p>{approval.reason}</p>
      <JsonView value={approval.arguments} />
      <p className="note">
        有效期至 {time(approval.expiresAt)}
        {expired ? " · 已到期，请刷新状态" : ""}
      </p>
      <div className="approval-actions">
        <button
          className="primary"
          disabled={busy || expired || approval.status !== "PENDING"}
          onClick={() => onDecision("APPROVE")}
        >
          <Check />
          批准此操作
        </button>
        <button
          disabled={busy || expired || approval.status !== "PENDING"}
          onClick={() => onDecision("REJECT")}
        >
          <X />
          拒绝
        </button>
      </div>
      <p className="note">批准的是上述处理动作，不是文档中的采购或业务流程。</p>
    </section>
  );
}
