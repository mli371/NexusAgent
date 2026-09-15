# 语义上下文缓存：学习与验证

## 一分钟理解

精确缓存要求问题文本及访问范围/参数/文档版本相同。语义缓存是在精确 miss 后，复用同一个 embedding 模型，把当前问题与有界历史问题向量做余弦比较；通过词面规则、阈值、原上下文指纹和权限/版本检查才复用证据。它不是答案缓存，也不是语义等价证明。

## 三条路径

| 路径 | 本次 embedding | 本次检索/重排/构建 | 本次回答 |
| --- | --- | --- | --- |
| hit：精确命中 | 不调用 | 复用 | 有证据时仍调用 LLM |
| semantic_hit：语义命中 | 调用一次 | 复用 | 有证据时仍调用 LLM |
| miss：语义未命中 | 同一向量继续用于向量检索，不再生成一次 | 正常执行 | 有证据时调用 LLM |

语义策略关闭或问题不适用时，沿用原精确缓存和正常检索。全部缓存关闭时为 bypassed。空证据不写缓存、不调用回答模型。各层 Redis 错误成为 miss/忽略写入，但权限拒绝、数据库错误与 embedding 失败不会被伪装成成功。

## 关键代码

- `LiveContextService`：精确优先、问题向量生成、候选证据验证和 fresh 来源登记。
- `LiveContextCacheKey`：复用同一个描述对象生成精确 key 和语义 scope，避免漏掉身份或版本字段。
- `RedisLiveContextCache.putWithReceipt`：只有 Redis 写入成功才返回上下文指纹和原始过期时间。
- `SemanticReusePolicy`：简单意图/否定/数值约束、余弦相似度、稳定排序与候选去重。
- `RedisSemanticContextCache`：有界 JSON 来源读写、数据校验与故障降级。
- `redis/semantic-context-put.lua`：单 bucket 原子添加、清理、裁剪和过期设置。
- `ContextBuilder.buildWithEmbedding`、`HybridRetrievalService.retrieveWithEmbedding`、`SemanticRetrievalService.retrieveWithEmbedding`：显式复用预计算向量，不用隐藏全局状态传递。

## Redis 到底存什么

上下文仍在原精确 key `retrieval:live-context-v1:{sha256}:context` 中。语义 bucket 为 `retrieval:semantic-context-v1:{scopeHash}:candidates`，这里大括号是实际 key 的一部分。

scope 包含 tenant/actor、library/documents、已解析文档的 ID/revision、topK/预算、embedding provider/model/dimension、RRF 和流水线/策略版本，不包含问题文本、session 或 trace。不同 scope 不做向量比较。

ZSET 每个 member 是小型 JSON：schemaVersion、scopeHash、questionHash、问题向量、规则特征、原缓存 key/指纹、创建和过期时间。score 为过期时间。没有问题原文、答案、完整上传文件，也不重复存父块正文。向量和特征摘要仍是敏感派生数据，不能宣称匿名化。

默认每个 scope 最多 100 条，来源 JSON 最大 16 KiB，最多校验三个候选的实际证据；Lua 原子裁剪防止并发写突破容量。读取有限数量来源，再由 Java 计算余弦；不是 Redis 内建向量索引，也不是全库 SCAN。不同作用域总数未设配额，因此不是全局内存上限。

## 为什么禁止语义命中转存

只有完整检索得到的上下文可以成为来源。A 与 B 相似时，B 可以复用 A 的证据，但不会把 B 注册成新来源，也不会为 B 建立精确缓存别名。C 必须直接和原始来源比较，不能通过 A≈B、B≈C 的链条间接复用 A。

来源有效期不晚于原上下文，读取不续期。新来源可以延长 bucket 的寿命，但不会改变旧 member 的过期时间。旧 member 到期后不可被读出，在后续写入或 bucket 过期时清理。原正文被删除、替换或校验失败时，来源即使还在也不能命中。

## 阈值与词面规则

默认 cosine >= 0.96；这不是“96% 正确”，也不是经过真实数据验证的阈值。它只决定是否尝试复用候选。

词面规则识别 COMPARISON、HIGHLIGHTS、FACT_LOOKUP 三类。比较/亮点同时出现、未知问题或多个已识别否定表达直接跳过语义复用。比较/亮点优先于一般疑问词，以免把“What are the highlights?”误分成两个意图。

数字（包括常见中文数词）、年份、部分相对时间词被规范化为摘要；两侧必须相同。已识别否定状态和意图也必须相同。中英文的固定关键词不是完整 NLP：实体差异、未覆盖的复杂否定和细微意图仍可能造成误命中，简单规则也可能保守地拒绝合理改写。

选中后再次检查指纹、parent/child/citation 对应关系、权限和 PostgreSQL revision。引用一致不代表答案事实性，也不保证旧上下文覆盖新问题的全部证据。

### 2026-09-15 人工查询记录：为何相近问题仍 miss

读取用户已有的三次页面记录（未再次调用模型）发现：首问没有来源；第二问被追问补全加入明确年份范围，而首问仅有“这几年”，约束摘要不相同，返回 `no_compatible_source`；第三问的兼容来源最高 cosine 约为 0.884，低于 0.96，返回 `below_threshold`。这些是三次实际请求的诊断，不是正式检索/语义等价评测，也不能用于确定新阈值。

当前补全与缓存尚未共享规范化的时间/对象范围，可能保守地拒绝合理近义改写。也不能直接把产品比较、发布会比较、资料/试点安排比较当作同一证据需求。后续应以标注样例评估误复用和漏复用，再协调约束表达与校准阈值；本轮没有修改默认阈值、删除年份保护或新增等价判定模型。命中后仍生成新答案，回答文本不同不是缓存 miss 的判据。

## 配置与启动

| 配置 | 默认值 |
| --- | --- |
| NEXUS_SEMANTIC_CACHE_ENABLED | true；还要求真实查询、Redis 和 live cache 启用 |
| NEXUS_SEMANTIC_CACHE_MIN_SIMILARITY | 0.96，有限且在 (0,1] |
| NEXUS_SEMANTIC_CACHE_MAX_ENTRIES | 100，可配置 1–200 |
| TTL / Redis 操作 timeout | 沿用 live cache：15m / 500ms |

没有新 Flyway 迁移，也不需要重建文档向量。重启后端加载实现即可，保留自己的 `.env`，不要重复启动占用 8080 的进程。完整启动见 [工作台说明](../learning-workbench.md)。能力接口报告 versioned_semantic_context / versioned_context / bypassed，但不探测 Redis 或模型连通性。

```bash
curl --fail-with-body http://localhost:8080/api/v1/query/capabilities
docker compose exec -T redis redis-cli --scan --pattern 'retrieval:semantic-context-v1:*'
```

上述 Redis scan 只用于人工诊断，不是请求路径。可对返回的具体 key 执行 TTL 和 ZCARD 检查，不必打印包含向量的成员内容。

临时关闭语义层、保留精确缓存启动示例：

```bash
NEXUS_SEMANTIC_CACHE_ENABLED=false NEXUS_AGENT_ENABLED=true NEXUS_AGENT_WORKER_MODE=pi \
  NEXUS_ANSWER_PROVIDER=openai NEXUS_EMBEDDINGS_PROVIDER=openai mvn spring-boot:run
```

## 前端手动验收

1. 保持同一身份、文档范围、topK 和预算，在获准的已就绪合成资料中问“这些苹果发布会有哪些亮点？”。首次应走正常检索并尝试写入来源。
2. 重复同一问题：原精确缓存仍有效时应 hit；问题向量阶段未执行。
3. 改问“这些苹果发布会有哪些特色？”。语义阶段显示实际 similarity/threshold；足够相似且校验通过才 semantic_hit，不保证这对真实 embedding 一定超过默认阈值。
4. 点击语义节点看摘要；点击 RRF/重排节点确认历史分数注明来自缓存源问题。本次 query_embedding 和 answer_generation 仍实际执行。
5. 对比不同年份或比较/亮点意图，确认不能只靠主题相似复用。切换身份/文档/参数、明确批准重建后，新 scope 应 miss。

已有精确缓存不会为了补登记语义来源而额外调用 embedding；因此第一次如果直接精确命中，该旧条目仍没有语义向量。等待其自然过期或使用尚未缓存的新问题即可，不需要清空整个 Redis。

提交这些问题会产生真实 embedding/回答用量，必须由用户自行确认。本次实现未执行付费请求；不要为了演示命中随意降低阈值。

## 测试命令

```bash
npm test --prefix workers/pi-worker
mvn -Dapi.version=1.44 clean verify
npm test --prefix frontend
npm run build --prefix frontend
npm run test:e2e --prefix frontend
git diff --check
```

核心新增测试为 SemanticReusePolicyTest、SemanticContextServiceTest、RedisSemanticContextCacheTest，以及 LiveContextCacheIntegrationTest 的真实 Redis/PgVector 编排用例。浏览器用合成 HTTP，模型用固定 transport，不能把这些用例称为真实语义质量评测。CI 沿用现有工作流，本轮未自动推送或触发新的远端运行。

## 面试答法

> I check exact context first, then optionally compare a query embedding against a bounded Redis source set within the same access scope and document revisions. Semantic misses reuse that embedding for retrieval. Only fresh retrieval seeds the cache; approximate hits cannot create aliases or extend source lifetime. Redis failure falls back to retrieval, and answers are always generated anew. Cosine similarity and lexical guards are heuristics, not a guarantee of equivalent intent or sufficient evidence.

未来优先做有标注的误命中/漏命中评测和阈值校准，再考虑实体约束、全局容量管理或专用向量索引；当前不承诺性能收益或生产安全。
