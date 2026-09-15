# 语义上下文缓存设计

状态：用户已批准“现有 Redis + Java 有界相似度计算”方向；本文件待 review，尚未实现。

## 1. 目标与范围

在真实问答的精确缓存之后增加语义缓存，使同一访问范围内的近义问题有机会复用检索上下文。继续使用现有 embedding provider，不新增判断用 LLM、Redis Search、数据库表或独立服务。

只复用证据上下文，不缓存答案。命中后仍针对本次问题调用回答模型并校验引用。不改 Pi 审批流程、文档处理语义、已有离线演示查询或认证模型。

比较的两种方案：现有 Redis 存有界问题向量、Java 做精确余弦计算；或者新增专用向量索引能力。选择前者以控制部署和代码复杂度，明确不是面向大量缓存的 ANN 服务。

## 2. 请求流程

1. 沿用权限、文档就绪检查和 PostgreSQL 文档版本快照。
2. 查询现有精确上下文缓存。有效命中直接复用，不生成问题向量，也不查询语义缓存。
3. 精确未命中且语义功能启用时，用 `EmbeddingService` 生成本次问题向量，记录一次 `query_embedding`。
4. 只读取同一作用域的有界历史问题向量，校验 envelope、过期时间和复用规则，计算余弦相似度。
5. 按相似度降序检查最多 3 个候选，分数相同时按 questionHash 排序；读取原始上下文并复查证据、权限及版本。
6. 有效语义命中复用上下文；否则把已经生成的向量传给现有混合检索，不再调用一次 embedding。正常执行全文检索、RRF、重排和上下文构建。
7. 只有本次实际执行完整检索得到的有效上下文，才可以写入精确缓存并登记语义来源。
8. 保留回答生成前、回答返回后的权限/版本复查与引用校验；所有路径均生成本次 traceId。

语义功能关闭时保留现有精确缓存逻辑；Redis 或真实上下文缓存总开关关闭时不启用语义缓存。

## 3. 作用域与数据

新增命名空间：`retrieval:semantic-context-v1:{scopeHash}:candidates`。scopeHash 使用结构化 JSON 的 SHA-256，包含：

- tenantId、actorId、library/documents 模式。
- 已解析、排序的文档 ID 及 retrieval_revision，不只是客户端传来的筛选条件。
- 有效 topK、contextBudgetChars、embedding provider/model/dimension、RRF 参数。
- 现有检索/上下文算法版本，以及语义复用策略版本。

问题文本不参与作用域 hash，否则无法查找近义问题。精确缓存 key 保留原来的问题文本语义。不把 sessionId、traceId、debug 纳入缓存匹配范围。

语义来源的 JSON 包含 schemaVersion、scopeHash、questionHash、问题向量、规则特征、原精确缓存 key、原上下文指纹、createdAt、expiresAt。正文仍只在原精确缓存中保存一份。

不存历史问题原文、模型答案、完整文档、trace 或隐藏推理。数字等词面规则特征保存稳定摘要，否定/意图分类保存有界枚举；问题向量和摘要仍可能泄露信息，不是加密或匿名化保证。

## 4. Redis 有界存储

每个作用域使用一个 ZSET，score 为来源 expiresAt，member 为有大小限制的来源 JSON。默认最多 100 条来源，Java 只对有界结果做余弦计算，不使用 KEYS 或全库扫描。

使用一个短 Lua 写入脚本执行添加、清理到期成员、裁剪最早到期成员及设置 bucket TTL，避免并发写突破条目上限。脚本不计算相似度，不处理大块上下文。Redis 脚本原子执行且执行时占用服务端，因此操作量必须有界；参考 [Redis Lua 说明](https://redis.io/docs/latest/develop/programmability/eval-intro/)。

读取按 score 排除过期成员并限制返回数量；兼容现有 Redis 7.4，可通过 Spring Data Redis 对应的有界 score-range API 实现，语义参考 [Redis score-range 查询](https://redis.io/docs/latest/commands/zrangebyscore/)。

- 来源 JSON 写入上限 16 KiB；读取时再次检查大小、schema、scope、维度和有限数值。
- 问题向量维度必须匹配当前模型，零向量、NaN、Infinity 均不得参加比较。
- 默认 bucket 最多约 100 x 16 KiB 的应用层来源数据，另有 Redis 自身开销；上下文沿用已有 128 KiB 默认上限。
- 并发同问题的 fresh 请求可以产生重复来源；读取按 questionHash 去重，所有来源仍受条目数上限限制，不实现分布式锁。
- 容量是每个作用域的限制，不是整个 Redis 的全局配额；不同身份/版本/参数产生的 bucket 由 TTL 回收。

## 5. 过期、指纹与防语义漂移

增强内部缓存写入结果，明确返回是否成功缓存、原始过期时间和上下文指纹；不能因为写入方法正常结束就假定 Redis 已保存值。

只有精确上下文实际写入成功后，才登记语义来源。来源 expiresAt 不晚于原上下文 expiresAt。原值不存在、已过期、指纹变化或证据校验失败均视为候选不可用，不返回旧证据。

读命中不续期。bucket 因新 fresh 写入可以续期，但旧 member 的 expiresAt 不改变；到期成员查询不可见，并在下次写入或 bucket 过期时物理清理。

语义命中不能写入本次问题的精确缓存，也不能注册本次问题向量为新语义来源。这样 A 的原始检索结果不会经 A≈B、B≈C 连续转存后被 C 间接使用，也不会通过命中延长原始证据寿命。

授权元数据、chunk、embedding 改变仍由已有 V10 事务性 revision 机制使新请求进入不同 scope。版本复查不是跨 LLM 网络调用的强一致授权保证；当前身份仍是 header 演示模式。

## 6. 匹配规则与诚实边界

采用真实问题向量的余弦相似度；默认阈值 0.96，只是保守的可调起点，未经过真实问题集标定。0.96 不是“96% 正确”，也不保证用户指定的一对改写一定命中。

除相似度外，使用小型、可测试的词面防护规则：

- 阿拉伯数字、年份及其规范化数字约束集合必须相同；出现/缺失也视为不同。
- 已识别的中文/英文否定表达在两侧必须一致；含无法判断的复杂否定形式不复用。
- 第一版仅识别 COMPARISON（比较/差异）、HIGHLIGHTS（亮点/特色）和 FACT_LOOKUP（明确的事实查询），必须为同一分类才可复用。未知或多意图问题不参与语义复用，仍走精确缓存和正常检索；不把未识别当作语义一致。
- 特征提取规则随实现保存为固定版本并配套正反例测试；这不是完整 NLU、实体识别或 LLM-as-judge。

例如“2024 年发布了哪些产品”和“2025 年发布了哪些产品”必须拒绝复用；“比较发布会差异”和“介绍特色产品”也不能只因都提到发布会就复用。

即使规则通过，仍可能出现误命中、上下文不充分和不同实体被混淆。引用校验检查引用关联，不证明上下文足以回答新问题。这是实验性优化，提供总开关；不宣传为语义等价判断或已验证的效果提升。

## 7. 配置与失败策略

| 配置 | 初始值与约束 |
| --- | --- |
| NEXUS_SEMANTIC_CACHE_ENABLED | true；仅当 live/Redis/上下文缓存都启用时生效 |
| NEXUS_SEMANTIC_CACHE_MIN_SIMILARITY | 0.96；必须有限且大于 0、不超过 1 |
| NEXUS_SEMANTIC_CACHE_MAX_ENTRIES | 100；允许 1 到 200 |
| TTL | 继承 live cache TTL，默认 15 分钟，不建立更长的证据保留期 |
| 来源 JSON 上限 | 固定 16 KiB；超限跳过登记 |
| 候选证据检查数量 | 最多 3 个 |
| Redis 等待 | 继承 live cache timeout，默认 500ms；上下文阶段保留总体 timeout |

阈值在每次匹配时读取当前配置，不因降低阈值重新写入或续期旧来源。算法或词面规则变化升级策略版本。

Redis 读写故障、超时、损坏值和过期值降级为 miss 或跳过写入；不影响正常问答。权限拒绝和数据库故障不得被缓存降级吞掉。embedding 失败保留现有失败路径，不伪装成无上下文，也不为缓存添加额外付费重试。

保留 Reactor 非阻塞 Redis I/O；相似度计算只遍历限量且已校验的数据，不做阻塞网络调用。日志和 SSE 只记录 match 类型、相似度、阈值、计数、原因码，不输出原始问题向量或历史问题。

## 8. 代码边界与学习前端

- `SemanticContextCache` / `RedisSemanticContextCache`：来源存取、JSON、TTL、上限、Redis 降级。
- `SemanticReusePolicy`：可比较性、数值校验、余弦相似度和稳定排序，纯逻辑便于测试。
- `LiveContextService`：精确优先、语义候选验证、fresh 来源登记；与现有路径共用证据校验，不复制权限逻辑。
- 抽取共享作用域描述，避免精确 key 和语义 scope 的权限/版本字段逐渐不一致。
- 为 `ContextBuilder`、`HybridRetrievalService`、`SemanticRetrievalService` 增加明确的预计算问题向量入口；旧方法继续可用，不用 ThreadLocal 或隐式 Reactor context 传业务向量。

前端保留现有布局，新增 `semantic_cache_lookup` 阶段。精确命中跳过 query_embedding 和语义查找；语义命中显示 query_embedding 实际执行，其余检索、RRF、重排、父块扩展及构建阶段显示复用，不能标记为实际执行成功。

`retrievalCacheStatus` 保留 hit（精确命中）、miss、bypassed，新增 semantic_hit。debug=true 时通过阶段 summary 显示相似度、阈值和原因；debug=false 保持隐藏调试字段。能力接口可报告 `versioned_semantic_context`，禁用后保持既有模式。

命中返回的历史 retrieval/context 调试数据必须标注“来自缓存源问题，本次未重新计算”。将 query 字段替换成本次问题不能使历史 vector distance、全文分数、RRF 或重排分数变成本次分数。前端解析器、学习说明、REST/SSE 测试同步更新。

## 9. 验收与实现顺序

1. 纯逻辑测试：作用域隔离、维度/非法向量、余弦边界与稳定排序、数字/否定/意图防护。
2. Redis 集成测试：有界写入、并发容量、全部 key 正 TTL、成员独立过期、坏数据、指纹变化、悬空来源、关闭/停机降级。
3. 编排测试：首次 miss、同问题精确命中、固定相似向量语义命中、低相似度 miss；每次仍新生成答案。
4. 验证精确命中零次 embedding、语义命中一次 embedding、语义 miss 后正常检索总计一次 embedding；不建立语义命中派生来源或精确别名。
5. 覆盖 tenant/PRIVATE actor、参数/模型/版本差异、force re-chunk、重建 embedding、可见性修改和库新增就绪文档，确保不复用其他 scope。
6. REST/SSE debug 隐藏、trace 一致、历史分数来源、exact/semantic/miss/disabled 前端状态与桌面/手机测试。
7. 更新 README、API/缓存说明、中文 learning/review 文档及限制说明。现有 CI 执行新测试，不添加部署功能。

自动测试使用可控向量和固定模型 transport，隔离 Redis/PostgreSQL 容器，不需要真实 key；合成测试只证明控制流程，不证明真实语义命中质量。手动测试必须记录相似度和实际选中的证据，不为制造命中任意降低阈值；新增付费请求另行确认。

本次设计阶段不改变运行服务、不重建文档/向量、不修改 .env，也不推送新提交。实现完成后再执行完整测试与交付。

## 10. 面试解释

> I use exact context caching first, then an optional bounded semantic cache within the same access scope and document revisions. Semantic misses reuse the query embedding for retrieval. Only fresh retrieval results can seed the semantic cache, so approximate hits do not form chains or extend evidence lifetime. Redis failures fall back to retrieval, and every request still generates a new answer. Similarity is a heuristic, not a guarantee of equivalent intent or sufficient evidence.
