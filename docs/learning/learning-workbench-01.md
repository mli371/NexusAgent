# 学习笔记：把 Agent Harness 的执行过程变成可观察界面

## 本次要理解什么

页面不是另一套调度器。Java 仍然决定访问、审批、状态和执行；worker 只通过既有协议工作；React 读取事实并接收人的决定。UI 可以刷新或关闭，后台已保存的任务不依赖浏览器继续存在。

```text
选择/上传文档
  -> POST /agent/runs（独立问题、文档 ID、幂等键）
  -> GET run + 历史 events + 工具摘要
  -> fetch SSE（事件序号、runId、traceId）
  -> 真实流程节点与工具详情
  -> 人批准一个具体 approvalId
  -> Java 执行已批准动作并关联 ingestion job
  -> 新观察 / 下一次审批 / 最终报告
```

## 三个不能混淆的状态

1. `propose_retry` 成功：申请已保存，文档还不一定发生变化。
2. `APPROVED`：人同意规范化参数，worker 还要继续领取并请求 Java 执行。
3. action `SUCCEEDED`：实际处理结果已保存。最终报告再通过观察记录给出诊断。

前端不能把 HTTP 200、绿色的提议节点或模型说“完成了”当成写操作成功。取消也只先表达请求，实际状态以服务端确认和精确 job 结果为准。

## 推荐源码顺序

| 文件 | 学习重点 |
| --- | --- |
| `frontend/src/App.tsx` | 独立任务、身份切换、提交与决定；页面状态不是工作流真相 |
| `frontend/src/api/client.ts` | 同源请求、身份 header、超时、错误解析 |
| `frontend/src/run/monitor.ts` | 快照、历史补齐、SSE 重连、终态与访问撤销 |
| `frontend/src/api/stream.ts` | 已有 SSE parser 处理分包/多行；序号和关联校验 |
| `frontend/src/run/state.ts` | 事件去重、仅 ID 的 sessionStorage、临时幂等键 |
| `frontend/src/run/flow.ts` | 从真实记录投影流程，不生成假步骤 |
| `frontend/src/components/Inspector.tsx` | 输入/输出与静态源码说明分离 |
| `agentrun/observation/ToolObservationService.java` | 复用 `runs.owned` 校验，然后查询当前 run 的观察 |
| `agentrun/observation/ToolObservationProjection.java` | 白名单、大小边界、省略信息 |
| `src/test/java/com/nexusagent/agentrun/WorkbenchBrowserIT.java` | 临时基础设施上的跨进程浏览器验证 |

Java 简写路径相对于 `src/main/java/com/nexusagent/`。源码说明是静态表，函数改名需要同步维护；它不是 tracing，也不展示模型隐藏推理。

## 为什么不直接返回 worker 的内部数据

浏览器不需要 workerToken、claimToken、数据库凭证或原始文档全文。新增接口先检查 run 的 tenant/actor 和文档可见性，再按明确字段投影。列表不查询 JSON 载荷，点击详情才取单条输入/输出。

详情最多 32 KiB，长字段和未知工具载荷会省略并标注，避免截断成非法 JSON。API 返回 no-store；这不是认证替代品，也不是清除任意敏感字符串的 DLP。当前 header 仍可伪造，因此只能说是本地演示访问边界。

## 为什么不用原生 EventSource

既有 GET SSE 需要 `X-Tenant-Id` 和 `X-Actor-Id`，原生 EventSource 不方便带自定义 header。本次使用 fetch + `eventsource-parser`，同时处理 UTF-8 分包、多行 data、保活注释、Last-Event-ID 和断线。

客户端先补历史，再从最后序号续订。同一 run + sequence 只展示一次；历史缺口会报错重试，不跳过当作成功。断流只改变连接提示，不擅自把后端 RUNNING 改成 FAILED。终态快照仍须补完事件。

## 为什么不把完整会话放在浏览器

sessionStorage 只保存每个演示身份最近 20 个 runId。恢复时重新向 Java 校验权限并读取任务。切换身份用独立组件生命周期，取消旧请求；迟到响应不能写回新身份页面。页面没有 durable conversation memory。

创建任务的幂等键只在当前页内存保留，避免超时后同一提交重复创建。审批不自动重试；冲突需要重新读后端状态。上传没有请求幂等机制，超时后可能已成功，因此不做自动重传。

## 测试与取舍

单元测试验证 SSE 分包、关联与序号、重连、去重、过期审批、身份隔离和详情投影。浏览器合成测试验证布局与冲突交互。单独的真实集成测试使用临时 PostgreSQL/MinIO、scripted worker 和 Chrome，检查上传、两次审批和数据库结果；合成测试不能替代它。

前端没有新增阻塞客户端。Java 观察读操作使用 R2DBC，沿用现有 MinIO/file 操作的 boundedElastic 隔离。UI 不新增轮询 worker 或后台执行政策，只从服务端持久记录同步状态。

完整命令见 [运行与测试](../learning-workbench.md#5-测试)。本次保留本机 Vite 代理，未提供生产前端托管。未验证真实 Pi 模型驱动的审批效果，也没有新增模型调用。

## 面试答辩

“I built a learning workbench over an existing durable agent backend. The UI renders persisted events and scoped tool observations, but it does not own execution state or approval policy. I separated proposal success, human approval, and actual action completion. Fetch-based SSE supports identity headers, replay cursors, deduplication, and bounded reconnects. Tool details use an allowlisted projection instead of exposing internal worker credentials. I validated the browser-to-backend path with isolated PostgreSQL and MinIO plus a model-free scripted worker. It remains a local learning tool, not a production authentication or observability platform.”

继续学习前应能回答：为什么断线不等于失败？为什么 APPROVED 不等于成功？前端关闭后任务为什么还能继续？为什么工具观察与源码职责要分开？身份切换时如何防止旧请求迟到造成数据混淆？
