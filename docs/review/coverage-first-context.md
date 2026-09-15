# Review：Child 优先覆盖与 Parent 均衡扩展

## 结论

已按批准设计实现。默认 4000 字符及现有最大值不变；未改 topK、检索/重排、chunk/embedding 数据、私有配置或模型提供方。保留此前未提交的语义缓存和多轮追问改动，不提交或推送。

## 建议重点 Review

| 位置 | 变化与关注点 |
| --- | --- |
| ContextBudgetAllocator | 完整 child 预留、每 parent 一个代表、剩余额度均分、短 parent 额度回收、全局偏移与 Unicode 边界 |
| ContextBuilder / ContextDebugMetadata / ContextAllocation | child_selection 实际阶段、有界诊断、引用只对应完整纳入证据 |
| LiveQueryGuard / AnswerCitationValidator | child 必须完整位于返回 parent 中，正文仍与数据库一致 |
| LiveContextCacheKey / RedisLiveContextCache / RedisKeyFactory / RedisRetrievalCacheService | exact 和 semantic 策略版本同时更新，旧 context envelope 不可复用 |
| LiveContextService / LiveQueryService | 缓存命中及澄清路径明确跳过新增阶段 |
| OpenAiAnswerGenerator | 提示词限定“片段缺失”，不能由此推断整个知识库不存在资料；未更改模型或新增调用 |
| ContextCoverage / QueryFlow / QueryInspector / client | child 先于 parent 显示，预算/去重原因、缓存来源、证据数与答案引用数分开；校验响应内部关联 |

## 回归证据

- 后端完整 `mvn -Dapi.version=1.44 clean verify`：366 项通过，无失败或跳过。
- 前端单测：57 项通过；TypeScript/Vite 构建通过。
- Playwright：18 项通过，包含桌面和手机截图、五份证据在 4000 字符内纳入、预算排除原因、child 先于 parent。
- Pi worker：26 项通过，未修改 worker 逻辑。
- 新增语义命中阶段断言另做定向回归：6 项通过，验证 child_selection 为缓存跳过。
- `git diff --check` 通过。后端 8080 已重启，health=UP，capabilities 保留 OpenAI/语义缓存/追问配置；前端 5173 返回 HTTP 200。这些只验证本地服务，不验证远端模型可用性。

主要后端用例：五个独立 parent、短 parent 额度再分配、首/中/尾窗口、非零全局偏移、同 parent 去重、超长 child、极小预算、空输入、Unicode 边界、重复执行确定性、无部分 child 引用、旧缓存 envelope 拒绝、真实 REST/SSE 阶段与缓存行为。

所有模型测试使用合成 transport；浏览器使用 mock API。没有运行付费问答，没有从测试结果推断真实模型质量。局部 JVM 测试首次受沙箱 instrumentation 限制，需在允许 JVM attach 的本地环境运行；没有关闭测试或修改算法规避环境问题。

## 手动验收

1. 更新后端、打开学习页面，保持默认预算。
2. 自行提交比较问题（会产生真实 API 用量）。
3. 在「Child 选择」实际输出查看完整 child，再查看周边 parent；核对分配总正文不超预算。
4. 核对透明提示。如果五个年份都进入候选且完整 child 总长能装下，不应再因前几个 parent 太大而丢掉第五项。
5. 区分候选没找到、预算未纳入、parent 被裁剪、模型未使用引用四种情况。
6. 相同输入命中缓存时，分配来自缓存，child_selection 不能伪装本次执行。

新版本无需 Flyway 迁移、数据重建或清空 Redis。重启后端加载代码即可；前端刷新会丢失当前页面内历史，先保留需要的旧对话信息。

## 仍不能承诺

不能承诺召回所有年份、保留所有 parent 全文、同 parent 多个 child 都可见，或模型引用每条证据。极小预算仍可能不足；语义缓存仍有近义但不等价的误复用风险。字符预算不是模型 token 预算。更强的答案完整性或年份覆盖策略不在这次修复中。

学习与面试解释见 [学习笔记](../learning/coverage-first-context.md)。
