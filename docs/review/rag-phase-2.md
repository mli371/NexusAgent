# 第二步 Review：真实问答后端与 SSE

## 本次交付

后端接线已完成，等待本轮 review。没有修改前端、Pi worker 协议、审批规则、用户 `.env` 或现有文档/向量；没有付费 API 调用，没有 Git 提交。

- `/api/v1/query` 和 `/query/stream` 可以显式使用真实 OpenAI 管线。
- 默认 local 模式与旧 `/agent/query` 保持离线模板行为。
- 真正的模型空间就绪检查、tenant/PRIVATE 访问检查及返回前复查。
- 只返回模型实际引用的 citations；空证据拒答，超时/模型失败不假装成功。
- 实际服务边界产生的 stage 事件，REST/SSE 不重复执行。
- Live context cache 完全 bypass；session/status/tool 只保存 scoped、有界摘要。
- SSE 建流前错误即使收到 Accept=text/event-stream，也明确使用 JSON 错误体。

## 建议按这个顺序 Review

1. `LiveQueryConfiguration` 与 `QueryController`：真实模式必须主动开启，不能只填 key 就开始调用模型。
2. `LiveQueryInput` 与 `LiveQueryGuard`：选中文档范围、模型覆盖率、PRIVATE 权限、生成前/后复查。
3. `LiveQueryService.prepare/pipeline/stream`：预检查只执行一次；真正开流后失败发 error，不发 completed。
4. `AnswerCitationValidator`：正文 marker、声明 marker、selected child、expanded parent 四者一致；返回使用子集。
5. `StageObservation`、`QueryTrace`、三个 RetrievalService 和 ContextBuilder：图上的阶段来自实际调用；并发分支不能强行显示为假串行。
6. `LiveQueryIntegrationTest`：用真实数据库检查权限隔离、SSE、缓存绕过、撤权和重切块后的旧引用阻断。

这些源码均在 `src/main/java/com/nexusagent/` 下，测试在对应的 `src/test/java/com/nexusagent/query/live/` 下。

## 文件变更范围

- 新增 `query/live/`：配置、输入校验、Guard、Service、引用校验、Trace、安全失败类型。
- 新增 `common/observation/StageObservation`、`query/api/QueryStageEvent`。
- 修改 QueryController、QueryCapabilitiesController、QueryResponse、QueryStreamEvent、QueryProperties、两种 answer generator 的装配/说明。
- 在 Semantic/FullText/HybridRetrievalService、ContextBuilder 接入可选观察器；rank 计数器改为每次订阅重新创建。
- ParentContextExpansionService 先确认文档访问，再加载 chunks，并确认 child 的 parent 关联。
- GlobalExceptionHandler 保留 live query traceId，并显式声明 JSON 错误响应。
- 原 QueryOrchestrationService 不再缓存超时产生的空 context。
- 更新 `.env.example`、`application.yml`、README、ROADMAP、architecture、API、limitations、interview-defense、学习工作台说明与批准设计的当前状态。
- 新增本 review、中文学习笔记、实施计划，以及 query/live 测试组；加强原超时缓存回归。

工作区还有之前 Harness、前端和第一步的未提交修改；本清单不是整个 git diff 的归属声明。没有删除或重置这些变更。

## 验证记录

2026-09-14 本机验证结果：

| 检查 | 结果 |
| --- | --- |
| `mvn -Dapi.version=1.44 test` | 272 项通过，0 失败，0 错误，0 跳过；包含 9 项新的真实 PgVector query 集成测试 |
| `npm --prefix workers/pi-worker test` | 26 项通过，0 失败，0 跳过 |
| `git diff --check` | 通过 |

相对第一步增加 42 项后端测试，并加强原离线超时不写缓存测试。覆盖输入边界、debug 隐藏、引用子集/关联、空证据、超时、安全错误、Redis 不可用、scoped key、SSE 单次执行/取消、并行阶段、真实 tenant/PRIVATE SQL、模型就绪、撤权和重新 chunk+embed 后的旧 ID。

额外的 Accept=text/event-stream 回归最初发现预检查错误被编码为 SSE；已修正 GlobalExceptionHandler 显式返回 JSON，最终全量回归通过。不要把修复前日志当成最终结果。

全量测试仍观察到此前已有的 macOS Netty DNS fallback、数据库容器结束附近的连接 reset 和 Netty ByteBuf leak 告警；后者在旧 AgentRun 集成测试期间出现，根因未确定。本轮没有声称这些已修复，测试通过不等于不存在资源问题。模型/Redis 失败和配置拒绝测试也会有预期日志。

验证范围包含真实 PostgreSQL/PgVector，以及模拟模型 HTTP。未使用真实 OpenAI 额度；不把 fixture 输出当成真实模型质量验证。Pi worker 回归只保证本次没有破坏既有行为。

## 取舍与仍然保留的限制

- 独立 LiveQueryService 避免给旧 Agent 示例无意接入付费模型；复用已有 retrieval/context 服务，没有复制一套 RAG 算法。
- 输出前权限复查会增加数据库读取；不是跨外部调用的长事务，也不承诺生产级隔离。
- 实时 stage summary 只给计数/模型，不提前发送可能撤权的证据；具体排名、分数、预览在最终授权的 debug response 中查看。
- Character budget 限制 parent 正文，不等于 model token budget；完整外发内容另有 64 KiB 限制。
- 结构化引用校验不是事实性校验、不是 cross-encoder，也不是 LLM-as-judge。
- 单轮问答；没有聊天历史自动入 prompt、自动工具选择、token streaming、query 断线恢复、持久化答案或多实例 admission control。
- Redis 不是事实源。取消可留下 started 状态直至 TTL；内存 fallback 的生命周期管理仍是 demo。公开 trace ID 可由调用方指定且不是幂等键。
- 默认 local 路径仍保留原简化 SSE 与模板；不是“所有历史接口都升级成真实模型”。
- 前端聊天与阶段可视化在第三步，没有擅自提前实现。

## 手动验证入口

先阅读[中文学习笔记的启用与 curl 步骤](../learning/rag-02-live-query.md#手动启用与验证)。真实模型和向量重建涉及外发合成文本与费用，需要你明确开启。能力接口是只读配置检查，不验证账户额度。

本轮 review 的核心问题：你是否认可“Pi 处理文档”和“Java 固定 RAG 问答”分开，并接受先不用真实 context cache，换取清楚的权限与版本边界？通过后再实现第三步聊天前端。
