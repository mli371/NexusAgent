# Agent Harness 第三阶段实施计划

状态：沿用已批准的三阶段设计，第二阶段已通过用户 review；第三阶段实现与测试完成，等待 review。
后续给项目所有者 review 的说明、验收步骤与学习笔记优先使用中文。

## 范围与决定

1. 只读阶段租约失效后自动重新排队，默认最多恢复 3 次；工具/模型总预算不重置。
   单次执行时限耗尽直接失败，不靠恢复无限延长任务。
2. 已预留的写操作不重跑。结果不明进入 RECOVERY_REQUIRED；只读取精确关联的
   ingestion job 和当前文档状态。只有 job 的终态和保存的证据足够时才能确认结果，
   再继续诊断。job 仍 RUNNING、丢失或无法证明完成时保持待核对，不猜测成功。
3. 取消排队、等待审批、只读任务可立即完成。写操作在途时只记录取消请求，保留
   心跳和结果收尾；没有确认结果前不能宣称已取消，也不能宣称回滚。
4. GET /api/v1/agent/runs/{runId}/events/stream 从 PostgreSQL 事件表读取历史和
   后续事件，用事件序号作为 SSE id，支持 Last-Event-ID。断开查看不取消任务。
5. 使用已有 version=1 的有界 continuation，创建新 Pi 会话，不恢复隐藏推理。
6. 保留 header 身份演示边界；本阶段不加前端、登录、模型调用或新的写操作。

## 实施顺序与验收

- [x] V9：恢复计数、取消请求时间、RECOVERY_REQUIRED 状态与取消审批状态。
- [x] RunLifecycleService：集中管理租约丢失、证据核对、取消和写入结果收尾。
- [x] SSE 服务与接口：鉴权、分页轮询、断点续传、错误清理、按需求发送与终态结束。
- [x] worker：识别取消/待核对的结束原因，不把受控停止覆盖成失败，不重复写入。
- [x] 测试：恢复上限、旧 claim 隔离、取消竞态、未知写入、已完成操作不重复执行、
  实际 worker 被终止后的接管、SSE 历史/实时衔接和权限撤销。
- [x] 中文 review 文档、学习笔记和演示脚本；全量 Java 188 / worker 26 测试通过，停下等待 review。

交付入口：[中文 Review](../../review/agent-harness-phase-3.md)、[验收记录](../../verification/agent-harness-phase-3.md)。

## 明确保留的限制

恢复检查由 worker 领取或公开读取触发，不新增调度平台；无人访问时状态检查延后。
历史第二阶段 FAILED/ACTION_OUTCOME_UNKNOWN 不自动改写。没有人工强制标记成功接口，
没有全局事务、分布式 exactly-once 或生产认证保证。仍在 RUNNING 的遗留 job 可能
需要人工调查，不能仅因为超时就把它当作没有执行过。
