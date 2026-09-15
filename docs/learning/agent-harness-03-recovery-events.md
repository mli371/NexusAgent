# 第三步学习笔记：恢复、取消和可重放 SSE

## 这一步解决的问题

任务状态不能只存在 worker 内存里。进程退出、HTTP 响应丢失、用户断开页面，
都不代表后端的业务写入没有发生。恢复的核心不是“再调用一次”，而是先判断哪些事实已保存。

## 四种身份各自负责什么

| 标识 | 作用 |
| --- | --- |
| runId / traceId | 整个任务与日志关联；跨恢复保持不变 |
| attempt / claimToken | 当前 worker 的执行权；新领取会换 token，数据库只存 hash |
| invocationId / observationId | 一次工具调用及其保存的证据；响应丢失时可读取同一次结果 |
| approvalId / executionId / ingestionJobId | 人的许可、执行记录、业务 job 分开；一一关联防止重复派发 |

lease 是有期限的执行权，不是事务锁。短事务只保护状态转换，不包住模型或 MinIO I/O。
工具/模型预算跨 attempt 累计；恢复不是获得一份新的无限额度。

## Read 与 Write 的恢复为什么不同

只读工具读取 metadata，重复读取一般不会产生业务副作用，因此 lease 丢失时可以在
原预算内重新诊断。新会话能看到已保存的历史观察，但最终报告必须使用当前 attempt 的新观察。

写操作有三个窗口：

1. execution=PENDING：还没有派发；恢复后可用同一个批准做首次派发，仍需重新检查状态。
2. execution=RUNNING/UNKNOWN：可能已经写了，不允许再执行。查精确关联 job 和当前保存状态。
3. execution=SUCCEEDED/FAILED/SKIPPED：已知结果直接复用，继续诊断，不重复消费 approval。

PostgreSQL 的唯一约束防止相同 approval 重复预留；它不能让 PostgreSQL、MinIO、模型提供商
成为一个事务。因此只能说“重复派发抑制与保守恢复”，不能说分布式 exactly-once。

## RECOVERY_REQUIRED 的含义

它不是普通失败，也不是成功，而是系统承认**还不知道写操作的结果**。
只关联同一个 execution 的 job，不能用最新的相似 job 代替证据。
job 终态和当前状态足够时保存 action_reconciled，然后重新诊断；否则等待人工调查。
已知 FAILED 也不等于数据库全回滚，embedding 批次等操作可能留下部分合法结果。

## 取消的含义

只读/等待状态可以立即关闭。写操作开始后，先记 cancel_requested_at，拒绝后续工具/模型调用，
但让当前业务 I/O 的结果收尾。`cancellationPending=true` 表示还不能确认任务已停止。
确定写入结果后保存 action result，再把 run 设为 CANCELLED。取消不会删已生成 chunks。

HTTP/SSE 客户端断线只是传输中断，不自动调用取消接口。旧 worker 不能用过期 claim
覆盖新 attempt 的状态。worker 收到受控停止时也不再上报一个误导性的 WORKER_ERROR。

## SSE 的设计

事件持久化在 `(run_id, sequence)` 主键表，状态转换和对应事件同事务提交。
使用 GET SSE，把 sequence 放在 id，把 event_type 放在 event。
历史与未来事件都按 `sequence > cursor ORDER BY sequence` 从同一张表分页读取。
没有“先回放、再注册内存订阅”之间的漏事件窗口。

默认每秒轮询，最多一页 100 条；没有新事件时发无 id 的 keep-alive 注释。
每页重查 tenant/actor 与文档权限。断线重连传 Last-Event-ID，由客户端对最后已处理事件负责。
有序回放不是客户端副作用的 exactly-once，消费者仍应去重。

## Interview Defense

**Q: Why not just retry the whole run?**

I separate repeatable diagnostic reads from possibly committed writes. A lost read-only lease can
requeue the same run within cumulative budgets. An uncertain approved write enters RECOVERY_REQUIRED;
I reconcile the exact linked ingestion job before continuing. I never infer rollback from a timeout.

**Q: How do you fence the old worker?**

Each claim has a new random token whose hash is stored in PostgreSQL. Mutating protocol requests
check the current claim, active lease and cancellation state under the run row lock. A new attempt
invalidates the previous token. This is application-level fencing, not a distributed transaction.

**Q: What does cancellation guarantee?**

It prevents new work and records intent. If a write is already in flight, the API reports cancellation
pending until its outcome is known. I preserve the actual action result and do not claim rollback.

**Q: How do you avoid losing events at the replay/live boundary?**

Both paths read the same PostgreSQL event log by sequence. The next page always starts after the
last emitted sequence, so there is no separate subscription handoff. Clients reconnect with their
last processed event ID and deduplicate by run ID and sequence.

**Q: Is this production durable execution?**

No. It is a small, tested harness with lazy lease recovery and conservative reconciliation. It lacks
production identity, queue admission, retention, and a general operator recovery workflow. The model
is not trusted to authorize writes or declare their outcome.

## 运行与已知限制

Review/本地命令见 [第三步 Review](../review/agent-harness-phase-3.md)。
测试记录见 [验收记录](../verification/agent-harness-phase-3.md)。
恢复上限默认 3；job 无法确认时不自动修复；状态维护按访问触发；SSE 为有界分页轮询。
本阶段测试不需要外部 API key，不证明模型质量或生产负载能力。
