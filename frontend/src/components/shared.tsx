import { Check, Circle, Clock3, LoaderCircle, X } from "lucide-react";
import type { ReactNode } from "react";

const names: Record<string, string> = {
  QUEUED: "排队中",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  WAITING_APPROVAL: "等待审批",
  PENDING: "待处理",
  APPROVED: "已批准",
  REJECTED: "已拒绝",
  EXPIRED: "已过期",
  CANCELLED: "已取消",
  RECOVERY_REQUIRED: "需人工检查",
};
export function Status({ value, label }: { value: string; label?: string }) {
  const good = ["SUCCEEDED", "APPROVED"].includes(value);
  const bad = ["FAILED", "REJECTED", "RECOVERY_REQUIRED"].includes(value);
  return (
    <span
      className={`status ${good ? "good" : bad ? "bad" : "waiting"}`}
      title={value}
    >
      {good ? (
        <Check />
      ) : bad ? (
        <X />
      ) : value === "RUNNING" ? (
        <LoaderCircle />
      ) : (
        <Clock3 />
      )}
      {label ?? names[value] ?? value}
    </span>
  );
}
export function StateIcon({ value }: { value: string }) {
  return ["SUCCEEDED", "APPROVED"].includes(value) ? (
    <Check />
  ) : ["FAILED", "REJECTED", "RECOVERY_REQUIRED"].includes(value) ? (
    <X />
  ) : ["PENDING", "WAITING_APPROVAL", "QUEUED"].includes(value) ? (
    <Clock3 />
  ) : (
    <Circle />
  );
}
export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}
export function JsonView({ value }: { value: unknown }) {
  return (
    <pre className="json">
      {value === null || value === undefined
        ? "尚无结果"
        : JSON.stringify(value, null, 2)}
    </pre>
  );
}
export function time(value?: string | null) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "尚未记录";
}
