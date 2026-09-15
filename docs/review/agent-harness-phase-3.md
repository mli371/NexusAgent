# Agent Harness 第三步 Review

## 本次看什么

本阶段补上**有限恢复、取消协调、可重放 SSE**。前两步的诊断和人工审批继续复用，
没有改 RAG，没有做前端、登录或新增模型调用。后续 review 说明优先使用中文。

你可以先读本文，再看 [学习笔记](../learning/agent-harness-03-recovery-events.md)。
测试实际结果记录在 [验收记录](../verification/agent-harness-phase-3.md)。

## 先记住这三个区别

1. **恢复诊断不等于重跑写操作。** 读取状态可以再做，但已经预留的 CHUNK/EMBED_MISSING
   不能因为 worker 失联就再执行一次。
2. **收到取消请求不等于写入已取消。** 写操作已经开始时，先记录请求，等待结果确认。
   已经写入的 chunks 不会因为取消 run 被删除。
3. **SSE 回放不等于重新执行任务。** 回放只读取 PostgreSQL 事件表，不调用工具或模型。

## 状态转换

| 场景 | 处理 | 不会做什么 |
| --- | --- | --- |
| 只读阶段 lease 超时，active deadline 尚未耗尽 | 同一个 run 重新 QUEUED；recoveryCount 加 1 | 不重置工具/模型总预算 |
| 已用完单次 active deadline | FAILED / RUN_DEADLINE | 不靠循环恢复无限续时 |
| 达到恢复上限或剩余预算不足 | FAILED / RECOVERY_EXHAUSTED 或 BUDGET_EXCEEDED | 不无限自动重试 |
| 写操作 RUNNING 时失联 | RECOVERY_REQUIRED；execution 标记 UNKNOWN | 不新建一个写操作重跑 |
| 精确关联 job 已成功，当前保存状态也支持完成 | 保存已确认结果，重新排队诊断 | 不重复执行已消费的 approval |
| 精确关联 job 已失败 | 保存已知失败，重新诊断或完成待处理取消 | 不把失败解释成所有业务写入都回滚 |
| job 仍 RUNNING、缺失、成功但当前状态不匹配 | 保持 RECOVERY_REQUIRED | 不用“最近一个相似 job”猜结果 |
| QUEUED / WAITING_APPROVAL / 只读 RUNNING 收到取消 | CANCELLED，关闭未完成诊断，跳过未开始写入 | 不再允许旧 claim 提交结果 |
| 在途写操作收到取消 | cancellationPending=true；先等待确认 | 不宣称已经停止或回滚 |

默认最多恢复 3 次，可用 `NEXUS_AGENT_MAX_RECOVERIES` 配置为 0-10。
一次恢复会创建新的 claim/attempt 和新的 Pi 会话，但保留 runId、traceId、累计预算，
以及有界的 version 1 continuation。旧 claim 不能再发起工具、续租或完成任务。

**容易误解的一点：** approval 已批准、execution 仍为 PENDING 时，尚未完成“预留 execution +
关联 job”的原子事务，也没有开始业务写入。这种情况下恢复可以保留原 approval 做**首次派发**，
执行前仍重查权限和文档指纹。RUNNING/UNKNOWN/SUCCEEDED/FAILED 不会走第二次派发。

## 为什么能够核对写入结果

第二步已经把 executionId 与 ingestionJobId 在短事务中关联起来。
第三步只用 `ingestion_jobs.agent_execution_id` 找这个确切 job：

- CHUNK 成功：关联 job 必须 SUCCEEDED，当前 parent/child 数量必须非零。
- EMBED_MISSING 成功：关联 job 必须 SUCCEEDED，当前诊断为 HEALTHY。
- job FAILED：可以确认这次操作的失败结果，但仍可能有部分业务写入。
- Java 原操作稍后返回时，可以提交自身的真实结果；不会让它覆盖已经核对好的结果。

这些是当前 MVP 的状态证据，不是跨系统事务或全局 exactly-once 保证。
人工走原有 ingestion API 并发修改文档时，状态观察仍可能变旧。

## 新接口

所有接口都需要原 run 创建者的 `X-Tenant-Id` 和 `X-Actor-Id`，且会重新核对文档访问权限。
这里仍是可伪造的本地 header 身份，不是生产认证。

### 取消

```bash
export RUN_ID='REPLACE_WITH_RUN_UUID'
curl --fail-with-body -X POST "http://localhost:8080/api/v1/agent/runs/$RUN_ID/cancel" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

响应 HTTP 200 表示取消请求已处理，**必须同时看 status 和 cancellationPending**。
重复请求不重复记录取消事件。已经终态的 run 不改写成另一个终态。

### SSE 与断点续传

```bash
bash scripts/agent-events.sh "$RUN_ID"
# Ctrl-C 只断开查看。记录最后一个已处理事件的 id，例如 5：
bash scripts/agent-events.sh "$RUN_ID" 5
# 等价 Makefile 命令：
make agent-run-events RUN_ID="$RUN_ID" AFTER=5
```

`GET /api/v1/agent/runs/{runId}/events/stream` 返回 `text/event-stream`，每个持久事件包含：

```text
id: 6
event: recovery_queued
data: {"sequence":6,"eventType":"recovery_queued","payload":{"reason":"WORKER_LOST","recoveryCount":1},"runId":"...","traceId":"...","executionMode":"scripted","createdAt":"..."}
```

`Last-Event-ID` 优先于 `afterSequence`，只返回更大的序号。非法/负数/超过现有历史的
游标返回 400。历史和新事件从同一张表分批读取，每页最多 100 条，默认轮询间隔 1 秒。
空闲时发 keep-alive 注释，不写数据库、不占事件序号。终态历史发完后正常关闭。

开始响应前检查权限，错误仍是 HTTP 4xx/5xx；流已经打开后，权限撤销或存储错误会发送
不带 id 的安全 `error` 事件并关闭。每次读取页都重查权限，但这不是与权限变更原子同步的
撤销机制，已发送的数据无法收回。客户端应按 `(runId, id)` 去重，保存最后**处理完成**的游标。

这个接口是 GET SSE，但原生浏览器 EventSource 不能直接附加当前所需的身份 headers。
现阶段使用 curl/fetch；后续前端与真实认证设计再一起处理。原 `/query/stream` 不受影响。

## 本地验证顺序

先完成 [第二步人工审批演示](../agent-harness.md#human-approval-demo-phase-2) 的环境准备。
两边进程显式使用 scripted 模式，避免 `.env` 中真实 Pi 配置触发计费：

```bash
docker compose up -d
NEXUS_AGENT_ENABLED=true NEXUS_AGENT_WORKER_MODE=scripted mvn spring-boot:run
# 另一个终端：
NEXUS_AGENT_WORKER_MODE=scripted npm --prefix workers/pi-worker start
```

1. 用虚构文档创建 run；打开 SSE，观察 queued/started/tool_* 等事件。
2. 等到 WAITING_APPROVAL，检查取消接口；应直接 CANCELLED，不创建新 ingestion job。
3. 新建 run，按第二步方式批准一次操作；检查 actionResults 与精确关联 job。
4. 对仍在进行的 SSE 按 Ctrl-C，重新带最后 id 连接；不重复历史、不重新运行任务。
5. worker 中断通常太快，不必依赖人工抢时机；自动化测试会在诊断中暂停 worker，
   真正终止它，推进测试时钟，然后启动另一个 worker 验证同一 run 接管成功。
6. 自动化测试还模拟“预留已提交、业务/结果确认中断”，验证未知写入不会自动派发。

```bash
npm --prefix workers/pi-worker test
mvn -Dapi.version=1.44 test
bash -n scripts/agent-demo.sh
bash -n scripts/agent-events.sh
git diff --check
```

Docker 必须运行；没有 Docker 时 integration tests 的 skipped 不算通过。
`api.version` 是当前 Docker 29 与旧测试客户端的兼容参数，不是生产配置。
Java 测试中的 extraction 使用合成文本 fixture；不把它说成真实 MinIO/PDF 端到端验收。

## 代码 Review 顺序

1. `V9__agent_recovery_and_cancellation.sql`：新增状态、恢复计数、取消时间，不改旧 migration。
2. `RunLifecycleService`：maintain、recover、reconcile、requestCancel、actionFinished。
3. `ApprovedRetryService`：业务执行仍在预留事务之外，晚到结果交给 lifecycle 收尾。
4. `AgentRunService`：旧 claim 隔离、取消接口、心跳和状态响应。
5. `RunEventStreamService` / `RunEventStreamController`：同表回放与分页鉴权。
6. worker `executeRun`：受控停止不误报失败，不开始多余的模型会话。
7. `AgentRunIntegrationTest` / `AgentApprovalIntegrationTest` 与 worker tests：看反例。

## 保留限制

- 恢复由 worker claim 或公开 run 读取触发；无人访问时不自动推进。
- 不会自动修复遗留 RUNNING job，也没有人工强制改成成功的公开接口。
- 老第二步产生的 FAILED/ACTION_OUTCOME_UNKNOWN 不自动迁移成可恢复任务。
- 单 RUNNING 限制不是系统级互斥锁；RECOVERY_REQUIRED 中原 I/O 可能仍在收尾。
- 无跨系统 exactly-once、自动退款/补偿、任务保留策略或队列流控。
- 远端模型可能在客户端中止后继续计费；取消不是成本保证。
- SSE 是数据库轮询，不是高吞吐事件基础设施；没有生产压测结论。
- 本次不做真实模型收费测试、前端、登录或新产品功能。请 review 后再决定下一项。
