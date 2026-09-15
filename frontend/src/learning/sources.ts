import type { FlowNode } from "../run/flow";
export interface Lesson {
  why: string;
  boundary: string;
  sources: string[];
}
const lessons: Record<string, Lesson> = {
  request: {
    why: "先校验本次文档的访问范围，再持久化任务。每次发送是独立任务，不会自动附加聊天历史。",
    boundary: "创建成功不表示 worker 已领取，也不表示文档已经处理。",
    sources: [
      "agentrun/api/AgentRunController.java · create()",
      "agentrun/application/AgentRunService.java · create()",
    ],
  },
  worker: {
    why: "Worker 领取 Java 后端保存的任务，并取得只针对当前执行的 claim。",
    boundary: "领取时使用的内部凭证不会返回浏览器。",
    sources: [
      "agentrun/application/AgentRunService.java · claim()",
      "workers/pi-worker/src/worker.ts",
    ],
  },
  inspect_document: {
    why: "从持久化文档、chunks 和 embedding 元数据读取真实状态，避免模型猜测。",
    boundary:
      "HEALTHY 只说明处理元数据满足当前检查，不是检索质量或业务验收结论。",
    sources: [
      "agentrun/tools/DocumentDiagnosticsService.java · inspect()",
      "workers/pi-worker/src/backend-client.ts · tool()",
    ],
  },
  list_ingestion_jobs: {
    why: "将当前状态与过去的任务结果分开观察。没有结构化错误证据时，不臆测历史失败原因。",
    boundary: "最多返回最近十条任务摘要，不展示原始异常堆栈。",
    sources: [
      "agentrun/tools/DocumentDiagnosticsService.java · jobs()",
      "workers/pi-worker/src/backend-client.ts · tool()",
    ],
  },
  propose_retry: {
    why: "模型或 scripted worker 只能提议；Java 检查访问范围、已有观察和处理条件，再保存规范化参数。",
    boundary:
      "提议工具成功只表示生成了待审批申请，不表示 CHUNK 或 EMBED_MISSING 已执行。",
    sources: [
      "agentrun/approval/ApprovalService.java · propose()",
      "agentrun/approval/RetryPolicy.java · inspect()",
      "agentrun/approval/ApprovalRepository.java · create() / pause()",
    ],
  },
  approval: {
    why: "人决定是否允许保存的具体操作。后端再次核对所有者、审批状态和有效期。",
    boundary:
      "批准文档处理，不等于批准文档中的业务采购。APPROVED 也不等于执行成功。",
    sources: [
      "agentrun/api/AgentRunController.java · decide()",
      "agentrun/approval/ApprovalService.java · decide()",
    ],
  },
  action: {
    why: "Worker 请求执行已批准的动作，由 Java 服务使用保存的参数执行，并记录 ingestion job。",
    boundary:
      "审批和执行是分开的状态。取消请求也不保证正在进行的写操作立即回滚。",
    sources: [
      "agentrun/approval/ApprovedRetryService.java · execute()",
      "agentrun/application/RunLifecycleService.java · actionFinished()",
    ],
  },
  result: {
    why: "最终报告关联工具观察；异常、取消或恢复需要人工检查时应保留真实终态。",
    boundary: "scripted 报告不是模型回答；pi 模型报告也不能替代工具执行结果。",
    sources: [
      "agentrun/application/AgentReportValidator.java · validate()",
      "agentrun/application/AgentRunService.java · complete()",
    ],
  },
};
export function lessonFor(node: FlowNode): Lesson {
  return (
    lessons[node.tool?.toolName ?? node.kind] ?? {
      why: "后端记录的工作流步骤。",
      boundary: "尚无该工具的静态源码讲解，不推断内部调用。",
      sources: [],
    }
  );
}
