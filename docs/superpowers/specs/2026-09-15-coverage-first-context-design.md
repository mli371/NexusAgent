# 固定预算下的 Child 优先覆盖与 Parent 均衡扩展

状态：用户已确认设计并批准实施；实现与验证见 [中文 review](../../review/coverage-first-context.md)。

## 问题与目标

已检查的一次问答中，2022–2026 五份资料均进入重排候选，但现有 ContextBuilder 按顺序贪心填入 parent，前四项耗尽 4000 字符，排名第五的 2025 被预算跳过。该请求两个缓存均未命中，首问未执行 query rewrite。

本次不提高默认或请求预算，不扩大 topK，不改变检索、RRF、重排模型、文档权限、chunk/embedding 数据或多轮问题补全。不增加模型调用，不引入年份解析或固定 Apple 文档规则。

目标是：先把选中的 child 证据放入预算，再公平扩展附近 parent；页面能区分“候选”“已纳入证据”“parent 已裁剪”“预算未纳入”。覆盖指本次候选证据的覆盖，不代表搜索到了知识库所有相关事实。

## 方案选择

1. 保留完整 child 后均衡分配剩余预算（采用）：引用证据完整、可预测、没有新增模型费用。
2. 一开始把预算平均切给 parent：实现简单，但长 child 可能被截断，引用范围与实际证据不一致。
3. 用模型压缩各段：可能更紧凑，但增加费用、事实变形和新的评估需求，不在本次范围。

## 确定性分配规则

### 第一步：候选与 Child 选择

- 使用现有重排结果及其稳定顺序，加载当前授权的 document/child/parent，检查关系与全局偏移。
- 保留现有每个 parent 一个代表 child 的去重语义：同一 parent 的最高排名 child 为代表，其余候选明确记录为 DUPLICATE_PARENT，不宣称都已选中。该限制本次不扩展为多窗口 parent。
- 在任何 parent 扩展之前，为各代表 child 预留完整正文长度。
- 全部 child 的长度之和不超过预算时，全部纳入，不因高排名 parent 较长而丢掉后面的 child。
- 如果 child 正文本身超出总预算，按重排顺序纳入完整可容纳的 child；装不下的记录 CHILD_EXCEEDS_REMAINING_BUDGET，继续检查后面的可容纳项。不会为覆盖数量只保留几字符碎片，也不会生成缺失 child 正文的引用。
- 如果没有完整 child 能装入，返回空证据及预算原因；沿用不足证据回答，不调用答案模型，不把它说成知识库没有资料。

### 第二步：均衡扩展 Parent

- 扣除所有已选 child 的保留空间后，才分配剩余预算。
- 对仍可扩展的 parent 均分额外字符；不足一轮的余数按稳定排名分配。
- 达到完整 parent 长度的项停止领取，把未用额度重新分给其他 parent。最终结果不依赖异步返回顺序。
- 每个片段围绕所选 child 向两侧扩展，越过 parent 边界时向另一侧补足。保留完整 child，不因边界裁剪丢掉命中证据。
- parent 只展示一次；所有正文长度之和不超过原 contextBudgetChars。沿用正文字符预算语义，citation 标题等格式开销仍不计入，外发 64 KiB 上限继续生效。
- global charStart/charEnd 与 child chunkIndex 不变，charEnd 继续 exclusive；文本必须等于原 parent 对应范围的子串。窗口边缘不切断完整 Unicode surrogate pair，允许因此略微少用预算。

示例：预算 4000，5 个去重 child 各 400 字符，parent 均足够长。先保留 2000 字符 child，再给每项约 400 字符的周边空间，最终约 800 × 5，而不是前四项耗尽 4000。

## 编排与学习前端

流程明确为：reranking -> child_selection -> parent_expansion -> context_building。child_selection 阶段包含加载并验证当前候选文本、去重与完整证据预留；parent_expansion 是依据分配结果截取实际 parent 窗口；context_building 负责组装引用和最终文本。事件必须来自真实服务边界，不能前端伪造阶段耗时。

- 新增“Child 选择”节点，位于重排与父块扩展之间。
- 最终授权 debug 输出中先展示已选 child，再展示各自 parent 展开范围。child 正文可从最终 parent 片段与全局偏移还原，不另存历史或重复正文。
- 展示“候选 N / 去重代表 M / 纳入 K / 预算 B、已用 U”，并区分 parent 裁剪数与 child 排除数。
- 每条纳入记录展示文件名、child/parent ID、child 字符数、parent 分配字符数、是否裁剪；有排除时展示文件名、ID、有限原因码，不泄露未授权文档或不存在的候选正文。
- 回答旁增加紧凑提示，例如“覆盖 5 个父块；5 个父块已裁剪；预算未纳入 0 个”。预算不足时允许展开查看未纳入项。
- 这里的已纳入证据数与模型最终使用的引用数分开，不能把未引用直接判为检索遗漏。
- 澄清/拒绝等未检索终态不展示虚假的覆盖成功；缓存命中显示“复用缓存中的分配结果”，新节点同其他缓存阶段标为 skipped，不伪装执行。
- 保持现有桌面/移动布局，只增加步骤、结构化详情和紧凑提示，不重做页面设计。

## 后端契约、缓存与安全

把确定性预算分配放入小型纯逻辑组件，ContextBuilder 继续负责 Reactor 编排、引用组装。新增有界的覆盖/分配 debug 元数据：策略版本、总量计数、预算用量及每个候选的分配/排除状态。列表最大不超过本次候选数量，不存原始文件。

selectedChildChunks 只包含完整进入最终上下文的 child；citations 只引用这些 child 和对应 parent。加强校验：所选 child 全局范围必须包含于返回的 parent 窗口，引用文本与现存数据一致。

新诊断只在现有 debug 契约下可见；SSE summary 仍只提供计数/状态，证据和文件详情等最终权限复查后再返回。不向日志或审计写入原始证据。

提升 live pipeline 的 context 策略版本，使精确 key 和 semantic scope 一起改变；同时处理旧 offline context cache 的版本兼容，避免旧对象通过宽松反序列化继续复用。旧缓存自然按 TTL 过期，不清空 Redis、不删除数据、不伪造新诊断。没有完整新分配元数据的结果不能冒充新策略。

答案提示词明确：只能说“本次提供的上下文缺少某项信息”，不能据此断言整个知识库没有对应资料。不保证提示词能消除全部模型误述；最终文本仍受原有引用校验约束。

## 验收测试

- 五个独立 parent/child、预算 4000：只要完整 child 都可容纳，五项均进入上下文。
- 排名前面的 parent 很大，也不能消耗后面 child 的保留额度；短 parent 未用额度公平再分配。
- child 位于 parent 中部/末尾、全局非零偏移：child 完整可见，窗口和引用一致。
- 同 parent 去重、重叠窗口、极小预算、单 child 大于预算、空候选、Unicode 边界、排序确定性、预算不超限。
- 每个 citation 都对应 selected child 和 expanded parent；没有纳入的候选没有引用。
- REST/debug visibility、真实 SSE child_selection 顺序、澄清 skipped、精确/语义缓存 hit skipped 与旧版本失效。
- 前端展示 child -> parent、裁剪与排除原因、缓存标识；桌面/移动无重叠；模型引用数与证据数区分。
- 后端完整回归、frontend unit/build/e2e、worker 回归、diff 检查。模型测试全部使用合成 transport，不发付费请求。

## 文档与边界

实施后更新 README、API、架构、context 学习笔记、当前学习工作台说明、限制，新增中文 learning/review。说明优先保证 child 并不等于保证每个年份被检索到，也不保证模型在最终答案中讨论全部证据；预算极小或候选本身缺失仍可能不完整。

此次先完成设计 review，再实施。保留现有未提交改动，不读取或修改 .env，不改文档/向量，不提交或推送代码，不发送付费模型请求。

## Review 清单

- [x] 核对 ContextBuilder、parent 加载、citation 格式、live 证据校验、缓存版本、前端阶段与现有测试。
- [x] 用户已明确选择不加预算、先覆盖后扩展并增加透明提示。
- [x] 对比预算方案，明确极小预算、重复 parent、缓存命中与可见性边界。
- [x] 自查：没有把候选覆盖说成知识库完整覆盖；没有新增模型调用或持久化数据范围。
- [x] 用户确认本细化设计后进入实施与回归。
