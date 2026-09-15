# 真实 RAG 第二步：问答管线与真实阶段事件

历史阶段笔记：第三步已补充默认全库/可选指定文档和学习聊天 UI。下文“必须选择 1–10 文档/前端未实现”描述第二步完成时的边界；当前规则见 [第三步笔记](rag-03-learning-frontend.md)。已有带 documentIds 的命令仍兼容，不带 IDs 现在是 library 模式。

## 本次做到哪里

后端已支持显式开启的真实模型问答。前端仍是原来的 Pi 文档处理工作台；聊天界面、引用面板与阶段图留给第三步。

三条路径不要混淆：

| 入口 | 编排与回答 | 是否执行文档写操作 |
| --- | --- | --- |
| `/api/v1/query`、`/query/stream`，answer-provider=openai | Java 固定 RAG + OpenAI Responses | 否 |
| 同上，默认 local | 原离线模板管线 | 否 |
| `/api/v1/agent/query` | 原确定性 Plan-Execute-Critique 示例 | 否 |
| `/api/v1/agent/runs` | Pi 诊断/建议 + Java 审批与执行 | 人工审批后，仅允许已有的处理动作 |

Pi 不参与本次问答。真实回答模型也没有文件、数据库写入或 shell 工具。不会因为问题里提到“处理文档”就绕过审批。

## 一次请求的顺序

1. 校验问题、所选文档、topK、字符预算和关联 ID。真实模式必须明确选择 1–10 份文档，问题 1–2000 个 Java 字符。
2. `LiveQueryGuard` 按 tenant/actor 检查每份文档；PRIVATE 必须属于当前 actor。不存在和无权访问都返回 404。
3. 检查 child 数量、向量覆盖率和 provider/model/dimension。未 chunk、缺少向量、模型不匹配分别返回可识别的 409；不自动修复。
4. 记录有界生命周期摘要；context cache 明确 bypass。
5. 复用 `ContextBuilder`：问题 embedding 与全文检索分支并发；问题向量就绪后执行向量搜索。两条排名一起进入 RRF，随后启发式 rerank、parent 扩展和字符预算裁剪。
6. 发给回答模型前，再检查访问、覆盖率和所选 parent/child 仍存在且文本范围匹配。
7. 有证据时调用 Responses；没有证据时不调用回答模型，直接给 `insufficient_context`。此时问题 embedding 可能已经调用过，不能声称“整个请求完全没有模型费用”。
8. 校验 answer markers 与 usedCitationMarkers 一致，且引用来自本次 selected child 与 expanded parent；公开 citations 只保留实际使用的条目。
9. 记录不含正文的工具/审计摘要。返回前再次检查证据与权限；变化则不输出答案和 debug context。

没有对整个管线进行自动重试。一次 HTTP 请求只执行一次回答调用；REST 和 SSE 复用同一个 `pipeline`，没有为了画图额外调用一遍。

## 关键源码入口

| 文件/类 | 阅读重点 |
| --- | --- |
| `query/api/QueryController` | 按实际 LiveQueryService bean 选择真实或离线管线 |
| `query/live/LiveQueryConfiguration` | 显式开关、配置校验、独立的真实 answer bean |
| `query/live/LiveQueryInput` | 输入上限和明确文档范围 |
| `query/live/LiveQueryService` | prepare、pipeline、generate、bestEffort；同一执行供 REST/SSE 使用 |
| `query/live/LiveQueryGuard` | access、readiness、evidence；无跨远程调用的长数据库事务 |
| `query/application/OpenAiAnswerGenerator` | Responses 结构化输出解析和拒答；不是 Spring AI SDK |
| `query/live/AnswerCitationValidator` | 引用子集和 child/parent 元数据关联 |
| `common/observation/StageObservation` | 从 Reactor Context 读取观察器；没有观察器时不改变原调用方式 |
| `query/live/QueryTrace` | 有界请求内事件、时间、sequence、终止事件 |
| `retrieval/application/*RetrievalService` | 实际 embedding/search/fusion 边界 |
| `context/application/ContextBuilder` | 实际重排、父块展开、上下文构建边界 |

`QueryOrchestrationService` 保留原离线示例行为；本次只修正其超时/空 context 不应写入缓存的问题。旧 Agent 示例仍依赖它，所以没有把旧管线整体替换成付费模型。

## SSE 到底展示什么

```text
access_check -> embedding_readiness -> cache_lookup(skipped)
                                  -> query_embedding -> vector_search --+
                                  -> full_text_search -----------------+-> rrf_fusion
                                  -> reranking -> parent_expansion -> context_building
                                  -> answer_generation -> citation_validation
                                  -> 最后复查 -> message -> completed
```

上图表示依赖关系，不是固定的所有事件到达顺序。全文搜索可能先结束，也可能后结束。观察器在实际订阅时记录 running，在完成/失败/取消时记录对应状态。

Envelope 中的 `traceId` 贯穿整次请求；stage 中包含递增 `sequence`、`stage`、`attempt=1`、`status`、`timestamp`、`durationMs`。attempt 是业务阶段次数，不是 embedding 客户端内部 HTTP 重试次数。缓存 bypass 与空证据跳过回答会发 skipped。

debug=false：安全状态、耗时和公开答案元数据，不带 context/debug/阶段摘要。debug=true：阶段 summary 只含计数、模型或状态；最终校验通过后才返回完整的 retrievalDebug/contextDebug。这样中途权限撤销时，不会已经通过阶段事件发出原文。

单 summary 上限 16 KiB；最多 48 个 stage 记录；请求内 replay buffer 最多 64 个事件。它用于暂存预检查事件和同步订阅，不是断线恢复系统。没有持久化 query run，也没有 Last-Event-ID 恢复接口。

SSE 是真实进度流，不是 token 流：模型输出完整且校验通过后，才发送一条 message。不会把最终答案拆成小片段冒充模型 token，也不输出隐藏推理。

## 错误、超时和取消

| 位置 | 行为 |
| --- | --- |
| 输入/访问/就绪预检查 | 建流前返回 JSON HTTP 400/404/409，即使 Accept=text/event-stream |
| 查询 embedding | 单调用默认 20s；仅 429/5xx 最多重试一次；总 context 预算仍约束它 |
| 预检查、上下文构建与证据复查 | 各受默认 45s 的阶段超时保护，不是整个 HTTP 请求只能 45s |
| 回答生成 | 默认 90s；不使用旧模板的 5s 超时，不自动重试 |
| Redis/工具摘要/审计 | 每个 side effect 默认 2s，失败记录安全日志后继续 |
| 已开 SSE 的失败 | error + 稳定 code/traceId，随后关闭；不再输出 message/completed |
| 浏览器取消 | 取消本地 reactive 订阅；不保证外部模型立即停止或退回用量 |

失败不会变成模板成功。未知底层错误不暴露原始信息；回答模型返回无效结构、未知引用或不完整输出会失败，而不是自动再花一次费用“修复”。

## Redis 与审计边界

真实管线不依赖 RetrievalCacheService，所以不会读写旧缓存。离线缓存仍有文档版本失效限制，模型 key 不等于解决了权限/版本失效。

Session/status/tool 的存储标识是 `live-` 加 SHA-256，输入为有长度前缀的 tenant、actor、session 或 trace。它避免相同 sessionId/traceId 跨租户、跨 PRIVATE actor 共用记录，也避免含冒号的身份产生拼接歧义。Hash 是命名空间隔离手段，不是认证或加密。

- Session recent：questionHash，不存原始问题。
- Query status：短期生命周期状态，使用 scoped trace storage ID。
- Tool output：schemaVersion、公开 traceId、answerStatus、计数和 bypass 状态，不存答案、context 或 raw document。
- Audit：原始公开 traceId、tenant、actor、QUERY_EXECUTED、query resource ID、问题 hash 与有限元数据；不存 prompt、向量或原文。

HTTP/SSE/审计的 traceId 保持一致，但 Redis envelope 的 ID 是存储 scope，不应直接拿公开 traceId 猜 Redis key。旧接口未全面迁移到这个新命名空间。Redis 不启用时仍能问答；内存实现仍是 demo，未增加完整的过期/淘汰系统。

## 手动启用与验证

以下真实 embedding/问答操作会向 OpenAI 传输合成资料并产生费用。本次实现没有替你执行，没有修改 `.env`，也没有重启已有后端。

确认 `.env` 中只有你自己的本地 key；不要把 key 写进浏览器或命令历史。你确认后可使用下列开关启动后端，或把对应非敏感配置加入本地 `.env` 后重启。8080 已有旧后端时先手动停止旧进程。

```bash
NEXUS_ANSWER_PROVIDER=openai NEXUS_EMBEDDINGS_PROVIDER=openai \
  NEXUS_LLM_MODEL=gpt-5.6-luna mvn spring-boot:run
```

先只读确认配置和选中文档。DOC_ID 换成自己的合成文档 ID：

```bash
DOC_ID='<synthetic-document-uuid>'
curl --fail-with-body http://localhost:8080/api/v1/query/capabilities
curl --fail-with-body "http://localhost:8080/api/v1/documents/$DOC_ID/embedding-status" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

能力接口的 liveQueryReady=true 只表示已接线，不表示 key 已通过外部验证或文档已经准备好。若显示模型不匹配，先阅读第一步重建说明，明确确认后单独调用 replaceExisting；这里不把破坏性迁移混进问答脚本。

确认文档就绪后，选择文档中确实有证据的问题：

```bash
curl --fail-with-body -X POST http://localhost:8080/api/v1/query \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -H 'X-Trace-Id: rag-phase2-rest-1' \
  -d "{\"sessionId\":\"rag-learning\",\"question\":\"Summarize the documented release information.\",\"documentIds\":[\"$DOC_ID\"],\"topK\":5,\"contextBudgetChars\":4000,\"debug\":true}"

curl --fail-with-body -N -X POST http://localhost:8080/api/v1/query/stream \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -H 'X-Trace-Id: rag-phase2-stream-1' \
  -d "{\"sessionId\":\"rag-learning\",\"question\":\"Summarize the documented release information.\",\"documentIds\":[\"$DOC_ID\"],\"topK\":5,\"contextBudgetChars\":4000,\"debug\":true}"
```

这两条命令是两次独立付费查询，不是同一次请求的两种观察方式。traceId 不是幂等 key；相同 traceId 重发不会去重。生产系统需要由服务端分配/约束 trace ID。

检查结果：answerStatus、实际 model、引用是否对应文档；debug 模式 cacheStatus 必须 bypassed。改成 debug=false 后应没有 finalContextText、retrievalDebug、contextDebug、limitations、retrievalCacheStatus、stages。换到无权 tenant/actor 应返回 404；删除 documentIds 或给 topK=0 应返回 400。

## 自动测试与边界

```bash
mvn -Dapi.version=1.44 test
npm --prefix workers/pi-worker test
```

新集成测试以实际 PostgreSQL/PgVector 执行检索、RRF 和 context 服务，只把 OpenAI HTTP transport 替换为合成响应。它验证管线协议与安全，不验证模型质量。本次没有真实付费模型验收、没有前端浏览器测试；第三步再做 UI 联调。

## 面试说法

“我将文档管理 Agent 和知识问答分开。问答由 Java 固定编排，先做访问和 embedding 就绪检查，再并行执行向量与全文检索，经过 RRF、启发式重排和 parent 扩展后，向模型发送有界证据。答案返回前校验引用与当前权限。SSE 记录真实阶段，不暴露模型推理，也不是伪造的 token 流。真实模式暂时绕过上下文缓存，避免旧版本和权限变化带来的错误复用。”

必须承认：header 身份不是认证；引用结构正确不证明每句话都有依据；reranker 仍是启发式；没有检索质量评测和生产性能指标。权限复查不是跨 HTTP 的串行化隔离，极小并发变化窗口仍存在。为简化实现，复查可能重新加载整份选中文档的 chunks，后续可以改成有版本约束的定点查询。
