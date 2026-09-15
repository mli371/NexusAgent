# 真实 RAG 第一步 Review

日期：2026-09-14

## 结论

第一步“模型适配 + 向量准备”已实现并完成自动测试。**现在还不是可在学习前端使用的真实文档问答**：第二步负责安全接入 Query API 和真实阶段事件，第三步负责前端。已有 Pi 页面与审批策略未改变。

未改 `.env`，未调用付费 API，未替换用户向量，未重启现有前后端进程，未执行 Git 暂存/提交/推送。

## 请优先看这五件事

1. `ChildChunkEmbeddingService`：普通请求补缺失；replaceExisting 才整体替换。每次外发 child 前复查访问。
2. `EmbeddingWriteService.commit`：独立事务 bean，文档行锁下比对完整 child 快照，再提交。真正的 provider 调用不在事务里。
3. `VectorSearchRepository`：模型约束与 tenant/owner 约束一起进入 SQL WHERE，然后排序/LIMIT。
4. `OpenAiAnswerGenerator`：真实 Responses 请求与严格引用校验已经可单独测试，但没有注册成旧 Query API 的 bean，避免跳过第二步保障。
5. `QueryCapabilitiesController`：明确报告 activeAnswerGenerator=local-template、liveQueryReady=false，而不是把配置了 key 当作整条问答已上线。

源码路径和学习顺序见 [学习笔记](../learning/rag-01-real-providers.md)。

## 新增/改变的接口

- `GET /api/v1/query/capabilities`：当前组件和阶段能力，不返回任何 key 或内部地址。
- `GET /api/v1/documents/{id}/embedding-status`：新增 matchingChildChunkCount / mismatchedChildChunkCount，complete 按当前模型定义。
- `POST /api/v1/documents/{id}/embed?replaceExisting=true`：明确替换 embedding，不改变 chunk；普通 embed 路径不获得覆盖权限。
- 模型冲突/文档变化返回 409 和稳定 code；provider 故障返回安全 502/503/504，错误带 traceId，不暴露 provider 原始 body。

缓存 key 版本升级为 context-cache-v2，纳入 embedding provider/model/dimension。不清空 Redis；旧 key 自然过期。模型标识不能解决文档版本失效，第二步的真实问答将绕过 context cache。

## 验证结果

```text
mvn -Dapi.version=1.44 test
Tests run: 230, Failures: 0, Errors: 0, Skipped: 0

npm --prefix workers/pi-worker test
26 passed, 0 failed

git diff --check
通过
```

新增覆盖：18 项 provider/协议/输入/引用测试，4 项配置测试，10 项真实 PostgreSQL 重建/访问/行锁集成测试，能力接口测试，以及已有服务/路由/cache key 的新增断言。

HTTP 测试既有模拟 ExchangeFunction，也有回环地址的真实 HTTP 序列化测试；都不是向 OpenAI 发出请求。SQL 测试使用隔离 Testcontainers，包含第一次 upsert 后第二次失败导致全回滚、生成期间重新 chunk、访问撤销和行锁竞争。没有碰用户正在使用的 PostgreSQL 数据。

首次受限环境运行因 Mockito 无法附加 JVM 而失败，放开本地测试权限后全量通过；不是隐藏失败或跳过集成测试。

本次全量日志出现 macOS Netty DNS fallback、测试容器关闭附近的连接 reset，以及 AgentRunIntegrationTest 期间的 ByteBuf 释放告警。断言通过不代表这些告警已解决；资源释放告警根因尚未定位，本次没有扩展范围修改旧 Harness 网络代码。

## 仍需注意

- 回答适配器未接入查询入口；不要在当前页面期待真实 RAG 回答或真实逐阶段追踪。
- 尚未验证账号是否能调用选定 embedding/answer 模型；后续只用合成资料做明确开启的付费 smoke test。
- PRIVATE/tenant 仍依赖不可信 demo headers，不是正式身份认证。人工直调重建 endpoint 不是管理员角色系统。
- 普通补向量允许保留部分成功；整体替换路径才保证单次提交原子性。外部模型调用和数据库不是一个分布式事务。
- 进程崩溃、客户端取消或 commit 后 job 状态写入失败，仍可能产生 RUNNING/未知状态；需要人工检查，不能自动认定已回滚或安全重试。
- 初次加载现有 chunk 快照沿用现有读取方式；重建上限限制生成和提交规模，不是任意大文档的流式处理实现。
- 只支持当前 vector(384) 与 text-embedding-3-small；不并存多套向量，不自动升级表维度。
- 引用校验不是事实正确性评测；不宣称生产效果或生产安全。

## 下一步

Review 通过后，实现共享 Query 流水线：模型就绪检查、权限复查、正确筛选最终 citations、context cache bypass、真正的阶段 SSE，再接客户端。保持 Pi 的管理职责不变。
