# 真实 RAG：版本化上下文缓存与 CI

## 一句话记忆

缓存的是「已检索、重排、裁剪好的证据」，不是最终回答；PostgreSQL 决定数据版本和权限，Redis 只负责有界复用。

## 请求怎么走

```text
请求 -> 身份/可访问范围/向量就绪检查 -> 文档版本快照
  -> Redis cache lookup
     miss: 问题向量 + 混合检索 -> RRF -> 重排 -> parent context -> 复查 -> 写缓存
     hit: 读取缓存 -> 格式/关联/证据/权限复查 -> 跳过上述七个阶段
  -> 版本复查 -> 回答模型 -> 引用校验 -> 最终证据/权限/版本复查 -> 返回
```

命中时不做问题 embedding，但仍调用回答模型，所以不是“第二次提问免费”。即使模型失败，已经验证的上下文可以供之后的新请求复用；绝不缓存失败或部分模型答案。

## Key 和 value

`retrieval:live-context-v1:{sha256}:context` 是独立于离线演示缓存的新命名空间。
哈希输入是结构化描述：tenant、actor、trim 后的问题、scope、排序的实际文档 ID/版本、topK、字符预算、embedding provider/model/dimension、RRF k、管线版本。
不做大小写/内部空白合并，避免把可能有不同模型含义的问题误认为相同；不是语义相似问题缓存。
session、trace 和 debug 不影响检索结果，不进 key。命中不会复用上一请求的 trace 或阶段事件。

JSON envelope 包含 schemaVersion、cacheKey、cachedAt 和 ContextBuildResult。query 字段在存储时留空、返回时用本次请求还原；包含有界候选、父块文本、引用和调试分数，不包含最终答案或原始文件。
**父块文本本身仍可能敏感**：key 哈希不是加密，不意味着 Redis 可以公开访问。

## 为什么不只靠 TTL

V10 给 documents 加 `retrieval_revision`，默认 0。PostgreSQL 触发器在 parent/child/embedding 插入、更新、删除，以及 tenant/owner/visibility/filename 变化时更新版本，与数据写入一起提交或回滚。即使 chunk ID 没变，同模型重建向量仍递增版本。

后续请求拿到新版本会生成新 key；旧 entry 仍可能存在，但不会命中，最后由 TTL 回收。新就绪文档进入全库范围也会改变文档集合。撤权先影响预检范围，再影响复查；不能先返回缓存再检查权限。

这是保守的逻辑失效，不是主动物理删除所有旧副本。没有分布式锁、全库扫描删 key，也没有持有数据库事务等待 LLM。行级触发器存在写放大，当前有界学习场景可接受。

## 配置和失败策略

| 配置 | 默认值 |
| --- | --- |
| NEXUS_REDIS_ENABLED | true |
| NEXUS_LIVE_CACHE_ENABLED | true |
| NEXUS_LIVE_CACHE_TTL | 15m，允许大于 0 且不超过 1h |
| NEXUS_LIVE_CACHE_TIMEOUT | 500ms，允许大于 0 且不超过 2s |
| NEXUS_LIVE_CACHE_MAX_BYTES | 131072，允许 1 到 1048576 |

读不续期。空结果、超限值不缓存。损坏/过期 envelope 当 miss，Redis 故障和超时重新构建，写失败忽略。日志不打印正文或 Jackson 原始异常消息。数据库故障和权限拒绝不能伪装成普通 miss 后绕过。
并发 miss 可能重复构建；没有 single-flight 或回答缓存。请求中版本变化会明确失败，不自动重试付费回答。

## 前端怎么看

- cache_lookup：hit / miss / bypassed 与原因。
- hit 后：query embedding、两条检索、RRF、重排、父块扩展、上下文构建显示“缓存复用 / 未执行”。0 不是测得的执行时间。
- 阶段详情：候选、分数、片段标为来自缓存；模型回答与引用校验仍是本次实际执行。
- debug=false 隐藏上下文、候选及缓存调试字段；普通日志仍可关联 cache lookup 的 trace。

## 关键代码

- `LiveContextService`：cache-aside 主流程、缓存证据检查。
- `LiveContextCacheKey`：结构化 key，算法改动须升级 PIPELINE_VERSION。
- `LiveContextSnapshotRepository`：授权过滤后的数据库版本快照与复查。
- `RedisLiveContextCache`：JSON、TTL、大小上限、异常降级。
- `LiveQueryService`：新回答生成、最终校验与状态/审计关联。
- `V10__live_context_cache_revisions.sql`：事务性版本失效。

## 本地测试与手动验证

```bash
npm ci --prefix workers/pi-worker
npm test --prefix workers/pi-worker
mvn -Dapi.version=1.44 clean verify
npm ci --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
npm run test:e2e --prefix frontend
```

后端集成测试使用隔离 PgVector 和 Redis，模型 transport 为测试响应，不需要真实 key。`api.version` 是本地 Docker 兼容参数。浏览器自动化使用合成 HTTP，不是模型质量测试。

手动打开学习工作台，在已准备好的合成文档上连续问同一问题，参数和身份不变，应看到 miss 后 hit；每次仍可能有回答 API 用量。改变 topK/预算，或者明确批准重建向量后再问，应 miss。不要为测试自动重建文档或自动外发问题。

## 面试答法

> I cache retrieval context, not final answers. The key includes tenant and actor scope, retrieval settings, model identity, and document revisions maintained transactionally in PostgreSQL. A cache hit still requires authorization and evidence checks before the model call and before returning the response. Redis failures fall back to fresh retrieval. TTL bounds retention, while revisions handle logical invalidation. This is not a production authentication or serializable authorization guarantee.

## CI

[GitHub Actions 配置与边界](../ci.md)运行同一套可重复测试，不使用私人 API key，不自动部署。测试通过证明所测行为，不等于生产可靠性、答案质量或性能指标。
