import type {
  ActionResult,
  Approval,
  Run,
  RunEvent,
  ToolSummary,
} from "../api/types";
export interface FlowNode {
  id: string;
  kind: "request" | "worker" | "tool" | "approval" | "action" | "result";
  title: string;
  subtitle: string;
  status: string;
  sequence: number;
  tool?: ToolSummary;
  approval?: Approval;
  action?: ActionResult;
  time?: string;
}
export const toolTitles: Record<string, string> = {
  inspect_document: "检查文档处理状态",
  list_ingestion_jobs: "读取历史处理任务",
  propose_retry: "提议处理，校验策略",
};
export function flowNodes(
  run: Run | undefined,
  tools: ToolSummary[],
  events: RunEvent[],
): FlowNode[] {
  if (!run) return [];
  const sequence = (key: string, id: string, type?: string) =>
    events.find((e) => e.payload[key] === id && (!type || e.eventType === type))
      ?.sequence ?? Number.MAX_SAFE_INTEGER;
  const nodes: FlowNode[] = [
    {
      id: "request",
      kind: "request",
      title: "任务已保存",
      subtitle: "AgentRunService.create()",
      status: "SUCCEEDED",
      sequence: 1,
      time: run.createdAt,
    },
  ];
  events
    .filter((e) => e.eventType === "started")
    .forEach((e) =>
      nodes.push({
        id: `worker-${e.sequence}`,
        kind: "worker",
        title: "Worker 领取任务",
        subtitle: "AgentRunService.claim()",
        status: "SUCCEEDED",
        sequence: e.sequence,
        time: e.createdAt,
      }),
    );
  tools.forEach((tool) =>
    nodes.push({
      id: tool.observationId,
      kind: "tool",
      title: toolTitles[tool.toolName] ?? tool.toolName,
      subtitle: `${tool.toolName} · attempt ${tool.attempt}`,
      status: tool.status,
      tool,
      time: tool.createdAt,
      sequence: sequence("observationId", tool.observationId, "tool_started"),
    }),
  );
  (run.approvals ?? []).forEach((approval) =>
    nodes.push({
      id: approval.approvalId,
      kind: "approval",
      title: `人工审批 · ${approval.action}`,
      subtitle: approval.documentId,
      status: approval.status,
      approval,
      sequence: sequence(
        "approvalId",
        approval.approvalId,
        "approval_requested",
      ),
    }),
  );
  (run.actionResults ?? []).forEach((action) =>
    nodes.push({
      id: action.executionId,
      kind: "action",
      title: `执行 · ${action.action}`,
      subtitle: "ApprovedRetryService.execute()",
      status: action.status,
      action,
      sequence: sequence("approvalId", action.approvalId, "action_started"),
    }),
  );
  if (
    ["SUCCEEDED", "FAILED", "CANCELLED", "RECOVERY_REQUIRED"].includes(
      run.status,
    )
  ) {
    nodes.push({
      id: "result",
      kind: "result",
      title: run.report ? "诊断报告" : "任务终态",
      subtitle: run.errorCode ?? run.status,
      status: run.status,
      sequence: Number.MAX_SAFE_INTEGER,
      time: run.updatedAt,
    });
  }
  return nodes.sort(
    (a, b) =>
      a.sequence - b.sequence || (a.time ?? "").localeCompare(b.time ?? ""),
  );
}
