# 固定预算：先覆盖 Child，再展开 Parent

## 这次修复了什么

某次比较问题中，五个年份都进入了重排结果，但旧逻辑按排名逐个填入大 parent，前四项用完 4000 字符，第五项被预算排除。该请求没有命中缓存，也没有执行追问补全。问题发生在**上下文分配**，不是证明检索完全没找到某年份。

本次保持预算、topK、检索和重排算法不变。修复的是：先为可纳入的完整 child 留位置，再分配周边文字；不允许前几个大 parent 吃掉后面 child 的额度。

## 现在的流程

```text
混合检索 -> RRF -> 重排
  -> child_selection：加载/验证授权候选，按 parent 去重，预留完整 child
  -> parent_expansion：均分剩余额度，围绕各 child 截取 parent 窗口
  -> context_building：生成引用、上下文和分配明细
  -> 证据与权限复查 -> 回答模型 -> 引用与最终权限复查
```

`ParentContextExpansionService.expand` 仍负责加载 document/child/parent；真正决定窗口的是新增纯逻辑组件 `ContextBudgetAllocator`。纯逻辑不依赖 Reactor、数据库或模型，便于单独验证所有边界。

## 分配例子

预算 4000，五个不同 parent，各自命中的 child 为 400 字符，parent 均为 2000 字符：

1. 为五个完整 child 预留 2000 字符。
2. 剩余 2000 字符均分，每项多 400 字符。
3. 得到五个各约 800 字符的 parent 窗口，而不是先塞两份完整 parent。
4. 窗口围绕 child；靠近头尾时，把另一侧空间补足。
5. 某 parent 本身很短时，不强行凑满额度，剩余继续分给其他 parent。

这叫 water-filling：只给还没到完整长度的 parent 继续分配；整数余数按稳定排名分配。它均分的是**额外周边空间**，并非要求所有窗口最终一样长。

## 必须知道的边界

- 每个 parent 只保留最高排名 child 作为代表；同 parent 的其他候选记录为 `DUPLICATE_PARENT`。即使代表太大被排除，也不切换为更低排名代表。
- 保留完整 child 优先于覆盖数量。放不下时记录 `CHILD_EXCEEDS_REMAINING_BUDGET`，继续考虑后面的较短项，不截出几个字冒充完整证据。
- 没有 child 能完整装入时，返回空上下文/空引用；真实问答跳过答案模型并返回不足证据。
- `charStart`、`charEnd` 仍是 extracted text 的全局偏移，end exclusive；child 的 document-global `chunkIndex` 不变。
- “字符”沿用 Java/JavaScript 的 UTF-16 索引，不是 token，也不是 Unicode code point。新增窗口不切断完整 surrogate pair；为保留旧 child 的半对边界可能多预留一字符，裁剪边缘也可能略少用预算。
- 预算只约束 parent 正文总长度，不包含引用标题；已有外发输入 64 KiB 上限继续存在。
- 所有引用对应完整纳入的 child 和实际返回的 parent 子串，校验不止检查 ID。

## 页面怎么看

1. 流程图中，重排后先看到「Child 选择」，再到父块扩展。
2. 选中相关阶段并切到实际输出，先查看各文档完整 child，再展开它附近的 parent。
3. 回答下方显示：证据纳入 K/M 个父块、裁剪数、预算排除数、正文用量、答案引用数。
4. 展开未纳入明细，可区分同 parent 去重和预算不足，查看文件名与原因。

**证据数不等于答案引用数**。五份证据送给模型，不代表模型一定讨论五个年份，也不代表最终五个引用都必须出现。页面不能把“检索范围内有五份资料”说成“回答覆盖了所有资料”。

阶段 SSE 只给计数/状态，不提前发证据或文件详情。完整明细在最终授权 debug 响应中出现；不是隐藏推理、也不是逐 Java 方法监控。澄清等未执行检索的请求不展示虚假覆盖成功。

## 缓存为什么也要改

只更新分配代码，旧缓存可能仍返回先前缺年份的上下文。因此 `child-first-v1` 同时进入 live exact key 和 semantic scope；live/offline context envelope 升为 schemaVersion=2，offline key descriptor 也更新。

旧条目按原 TTL 过期，不清空 Redis、不重新 chunk/embed。命中时「Child 选择」等阶段标为 `skipped / cache_reuse`，页面显示缓存中的分配结果；语义缓存来源分数也仍属于原问题。回答模型继续针对本次问题执行，不缓存最终答案。

## 读源码顺序

1. `context/domain/ContextAllocation.java`：有限状态和无正文诊断。
2. `context/application/ContextBudgetAllocator.java`：先读 select，再读 expand。
3. `context/application/ContextBuilder.java`：真实阶段与引用组装。
4. `query/live/LiveQueryGuard.java`、`AnswerCitationValidator.java`：完整 child 可见性和引用检查。
5. `query/live/LiveContextCacheKey.java`：策略版本如何失效旧缓存。
6. `frontend/src/query/ContextCoverage.tsx`：选中 child、parent 和透明提示。

Java 路径均相对 `src/main/java/com/nexusagent/`。

## 测试与手动验证

```bash
mvn -Dapi.version=1.44 clean verify
npm --prefix workers/pi-worker test
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
git diff --check
```

集成测试使用隔离容器，模型响应为合成数据；浏览器测试 mock API，不需要真实 key，不验证真实模型答案质量。Docker 兼容时可省略 `api.version`。

手动在更新后端的学习页面保持预算 4000，自己提交比较问题，查看重排候选与分配明细。当各完整 child 总长可容纳时，它们都应保留；候选本身没出现的年份不可能由预算算法补出来。重复问题可以查看缓存分配标识。真实问答仍会产生模型用量，不能用自动测试结果声称真实回答已经覆盖全部年份。

## 面试答法

> I separate evidence coverage from context expansion. I first reserve the complete child span for each selected parent representative, then distribute the remaining character budget across parent windows. This prevents early large parents from starving later evidence. Diagnostics distinguish duplicate parents, budget exclusions, included evidence and citations actually used by the answer. The allocator is deterministic and versioned in the cache key. It improves evidence preservation, not guaranteed retrieval recall or answer completeness.

## 已知限制与后续

仍为启发式重排、字符预算、每 parent 一个代表、连续窗口，不保证完整句子、文档覆盖或模型使用每条证据。没有新增年份识别、query decomposition、模型压缩、cross-encoder 或扩大 topK。提示词要求区分“当前片段未提供”与“知识库不存在”，但这不是可证明的语义约束。后续应先用带标注的问题集评估遗漏在哪一层，再决定是否做多 child 窗口、token 预算或文档覆盖策略。
