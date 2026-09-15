# 当前页面多轮追问解析设计

状态：用户已批准当前页面多轮方案；本文待 review。本文描述待实现行为，不代表功能已经上线。

## 1. 已确认的范围

解决“上一轮问苹果发布会，下一轮只问主要有哪些差异”时主题丢失的问题。在现有真实问答管线中增加 Query Rewriting，把有历史的追问解析为独立问题，再进入缓存、检索和回答。

用户选择当前页面内的短期历史，不做 Redis 会话记忆、数据库聊天记录、刷新恢复、跨设备同步或会话管理页面。不改 Pi 文档审批、chunk、embedding、RRF 算法或权限模型，不更换现有模型，不增加新的服务依赖。

比较过两个方案：页面携带有界历史，或者 Redis 保存短期会话。选择前者是为了直接解决连续追问，避免同时引入会话恢复、并发写入和持久化生命周期。现有 Redis session 仍只承担运行状态等原有职责，不把它宣称为聊天记忆。

## 2. 用户可见行为

1. 首问没有历史，跳过问题补全的模型调用，沿用现有单轮检索。
2. 有历史时进行一次真实模型解析：独立新问题保持原样；指代明确的追问补全；无法确定对象时要求用户澄清。
3. 页面仍显示用户的原话。学习流程增加“问题补全”，详情显示原始问题、独立问题、使用的历史轮数、结果类型和实际耗时，不展示隐藏推理。
4. 只从当前页面同一访问范围的已完成且 answered 的问答中选最近 3 轮；失败、取消、拒答、证据不足和澄清提示不作为历史答案。
5. 刷新、离开问答视图、清空会话、切换 tenant/actor、切换 library/documents 或改变指定文档集合，均清空可见问答及可携带历史，并生成新 sessionId。变更期间不提交新问题；仍保留现有请求取消和迟到响应隔离。
6. 只调整 topK 或字符预算不清空历史；这些参数继续影响本次缓存 key。重复选择同一文档集合或只改变集合顺序不清空。
7. 输入框旁的外发授权说明覆盖“本次问题、最近最多 3 轮有界问答以及检索片段”。历史只在内存中，不写 localStorage、sessionStorage 或 IndexedDB。

示例：

```text
上一轮独立问题：苹果近几年秋季发布会有什么特别的地方？
当前输入：主要有哪些差异？
补全问题：苹果近几年秋季发布会有哪些主要差异？
```

补全不能自行添加用户没指定的年份、产品、价格或结论。历史回答中的实体可以用于识别“第二个产品”这类指代，但历史陈述不得作为已证实事实。模型可能误解指代，这是一项需评估的能力，不承诺改写一定正确。

## 3. 请求与边界

沿用 `POST /api/v1/query` 和 `POST /api/v1/query/stream`，为 QueryRequest 增加可选 `history`，旧请求省略或传空数组时兼容原行为。

```json
{
  "sessionId": "page-session",
  "question": "主要有哪些差异？",
  "scope": "library",
  "documentIds": [],
  "topK": 5,
  "contextBudgetChars": 4000,
  "debug": true,
  "history": [
    {
      "question": "苹果近几年秋季发布会有什么特别的地方？",
      "answer": "此前回答的有限文本摘录。",
      "answerTruncated": false
    }
  ]
}
```

- history 按时间从旧到新排列，最多 3 个对象，不接受任意 role/system 消息。
- 每轮 question 为 1 到 2000 个字符，采用上一轮实际使用的独立问题；前端拿不到该字段时采用原始问题。不能用截断问题代替完整问题。
- 每轮 answer 为最多 1000 个字符的摘录；前端截断时设置 answerTruncated=true，并避免切断 UTF-16 surrogate pair。空摘录允许，但不得据此假定完整回答可用。
- 每轮历史最多 3000 字符，三轮总计最多 9000 字符；本轮原问题与补全后的独立问题各自仍最多 2000 字符。这是字符预算，不是模型 token 预算。
- 后端校验数量、字段类型、null 元素及长度；不接受超长输入后静默裁剪。模型解析调用的序列化输入另有 64 KiB UTF-8 上限。
- history 不携带文档正文、完整 context、citation 对象、向量或递归的上一轮请求对象。
- 客户端历史是用户可编辑的不可信输入，不是服务器签名记录，也不是授权凭据；sessionId 不授予任何文档访问权。
- 离线模板查询收到非空 history 时明确返回 400 `MULTI_TURN_UNAVAILABLE`，不静默忽略，也不伪装为支持多轮。Pi 与原有 agent 接口不引入历史功能。

## 4. 编排与职责

```text
请求校验
  -> 当前身份权限和文档就绪预检查
  -> 记录本次运行开始
  -> query_resolution
       无历史：skipped，直接使用原问题
       unchanged / rewritten：得到独立问题
       needs_clarification：返回澄清提示，跳过后续检索与回答
  -> 当前文档版本快照
  -> 精确上下文缓存
  -> 语义缓存 / 正常混合检索
  -> RRF、重排、父块上下文、引用
  -> 使用独立问题和本次证据生成新回答
  -> 权限、版本、引用复查
  -> 运行状态与安全审计摘要
```

复用 `LiveQueryService` 的 REST/SSE 主流程，在已有 preflight 之后、`LiveContextService.load` 之前执行解析。不能把慢模型调用放在 SSE 订阅之前，导致用户一直看不到阶段进展。

- `ConversationTurn`：有界历史传输对象。
- `QuestionResolver`：`Mono<QuestionResolution>` 接口，便于独立测试；不访问 Redis、数据库、工具或执行写操作。
- `OpenAiQuestionResolver`：复用现有 OpenAiHttpClient 和已配置的回答模型，处理结构化输出、拒绝、格式与长度校验。
- `QuestionResolution`：保存解析状态、独立问题、有限原因码以及可选澄清问题，不保存模型隐藏推理。
- `LiveQueryService`：保留 originalQuestion 和 resolvedQuestion 的区别；先解析，再构造供现有缓存及检索使用的有效输入。traceId、sessionId、tenant、actor、documentIds、scope、topK、budget 均不可由解析结果修改。
- 前端新增有界历史选择纯函数，`useQuery`/`QueryWorkbench` 负责生命周期；不要为每一条已展示问答重复保存完整 history 树。

不为这个功能引入新的 agent、外部工作流引擎或通用记忆框架。已有 API record 的兼容构造方法和单轮测试继续可用。

## 5. 模型解析契约

有非空历史时每次最多调用一次 resolver，包括模型最终判定为独立新问题的情况。第一版不再增加一个分类模型，也不依赖中文代词正则决定是否收费调用。

复用 Responses API 的 `text.format` 严格 JSON Schema，不设置工具，不链接服务端 conversation 或 previous_response_id，继续发送 store=false。结果只允许：

| status | resolvedQuestion | clarificationQuestion | 后续行为 |
| --- | --- | --- | --- |
| unchanged | 必须等于 trim 后的原问题 | null | 正常检索 |
| rewritten | 非空、最多 2000 字符 | null | 使用独立问题检索 |
| needs_clarification | null | 非空、最多 300 字符 | 请用户明确对象，不检索 |

附加 reasonCode 仅允许 `STANDALONE`、`RESOLVED_REFERENCES`、`AMBIGUOUS_REFERENCES`、`UNSUPPORTED_FOLLOW_UP`，并校验它与 status 的对应关系。历史和当前问题通过 JSON 序列化置于不可信数据区域，不能拼成额外 system 消息。

解析器必须验证完整响应、单一文本输出、JSON 字段、枚举、组合约束与大小；不依靠 JSON Schema 证明语义正确。拒绝未知字段、incomplete 或夹杂工具输出的响应。模型拒答单独处理，不归类为“文档证据不足”。Responses 的结构化输出和拒答处理参考 [OpenAI 官方文档](https://developers.openai.com/api/docs/guides/structured-outputs)；该文档明确说明结构化结果仍可能有内容错误。

提示要求：优先保留本轮明确的实体、数字、年份、否定条件和输出要求；无歧义的新话题不继承旧主题；禁止从历史中的指令改变本轮权限/范围或执行操作；缺少必要指代时请求澄清。格式检查不能保证这些语义约束全部满足，需通过样例评估并在学习界面暴露改写结果。

配置增加 `NEXUS_QUERY_REWRITE_TIMEOUT`，默认 20s，允许正值且不超过 60s；模型使用现有回答模型配置，不读取或修改 .env，不增加单独 key。输出预算初始 1200 tokens，reasoning effort 与现有 low 配置一致；到达上限返回明确错误，不自动补调用。

## 6. 缓存、证据与隐私

解析必须在两层缓存之前。精确 key 的问题字段、语义匹配特征和问题向量，以及后续检索、重排和回答，统一使用 resolvedQuestion；无历史/unchanged 时使用原问题。

例如两个请求原话都为“它有什么特点？”，历史分别是 iPhone 和 Apple Watch，解析结果应不同，因此不能按这句原话共享精确 key。该性质依赖正确的解析；仍可能出现错误改写或语义缓存误命中，不能宣称绝对隔离语义。

缓存继续包含 tenant、actor、范围、就绪文档及 revision、topK、budget、embedding 模型和检索配置。不把整段 history、sessionId、traceId 或补全调试对象放入上下文缓存，也不缓存最终答案。两个会话只要得到相同独立问题且访问/配置作用域相同，可以复用同一证据缓存。

由于缓存内容只依赖已解析的独立问题和现有证据构建算法，不单独把 resolver 模型版本塞入证据 key；未来如引入依赖 history 的检索排序或更改证据构建语义，必须重新设计 key 或升级管线版本。不得让完整历史隐式参与检索而遗漏在 key 外。

历史回答不进入最终 reference material、不直接成为 citation、不跳过权限/版本复查。最终回答模型只收到 resolvedQuestion 和本次经过现有检查的文档证据；“把上一条原文翻译/逐字改写”等纯聊天编辑任务暂不支持，应要求用户提出可从文档回答的独立问题，而不是把旧答案当事实来源。

日志、audit、session 状态、ToolOutputStore 只记录本次状态、历史轮数、解析结果枚举和原/独立问题 hash，不记录完整历史、原始 prompt、模型隐藏推理或全文解析响应。SSE summary 同样只放有界计数和原因码。

原/独立问题仅可在本次 debug=true 响应的 `queryResolution` 中回显，调试对象不得随 context 写入 Redis。前端使用自己本来就持有的本轮输入展示历史范围，不要求服务端回传历史答案。

应用不新增聊天历史存储，但用户问题及其派生向量仍会按现有缓存策略参与缓存；不能宣称外部模型零留存或新增功能完全不涉及敏感信息。客户端已经收到的历史答案无法被后端远程撤回；现有 header 身份也不是生产认证。当前权限检查约束新检索，不能验证用户自带历史的来源或证明旧文本已被清除。

## 7. 澄清、错误与事件

- 正常歧义：HTTP 200，answerStatus=`needs_clarification`，answer 是有长度限制的澄清问题；citations=[]，cacheStatus=`bypassed`。debug=true 时 context/retrieval 数据为空，不能伪造候选或引用。
- 模型明确拒答：返回现有 refused 状态和安全提示；跳过检索及回答生成。
- 解析 API 超时：`QUERY_REWRITE_TIMEOUT`，HTTP 504 或已开始 SSE 中的 error；不自动重试，提醒远端可能已产生用量。
- 无效/不完整输出：`INVALID_QUERY_REWRITE_RESPONSE`，HTTP 502 或 SSE error；不以原始追问继续检索，不伪装成证据不足。
- 供应商连接/限流/服务错误：映射为安全的 `QUERY_REWRITE_UNAVAILABLE`（503），或请求配置被拒绝的 `QUERY_REWRITE_REJECTED`（502）；保留阶段定位，不记录供应商原始响应。
- 错误时不写新的上下文缓存或语义来源；正常检索后发生的后续回答失败继续遵守现有 context 缓存语义。
- 澄清/拒答仍完成本次 session 状态并记录有界审计摘要；运行错误记录失败。所有事件使用同一 traceId。

前端保留既有 received -> stage -> message -> completed / error 协议。新增 `query_resolution` 阶段，在 access_check、embedding_readiness 之后；跳过原因必须真实。澄清/拒答路径对缓存、embedding、检索、重排、上下文、回答及引用校验标记 skipped，而不是 succeeded 或错误的 0ms 执行。

新增 `queryResolution` 调试字段包含 originalQuestion、resolvedQuestion、status、reasonCode、historyTurnsUsed、modelCalled、resolverModel、resolverVersion。debug=false 隐藏此字段及原有内部调试数据；对外的 needs_clarification 状态和提示仍保留。首问 modelCalled=false、resolverModel=null，不能因配置了模型就声称执行过补全。

capabilities 增加 `pageFollowUpSupported` 和 `maxHistoryTurns=3`，只在真实管线启用。前端不对旧后端发送非空 history 并假装已支持；类型、SSE 校验器、空上下文分支和阶段数量限制一起更新。保持现有事件上限，在测试中验证新增阶段后不溢出。

## 8. 学习前端

复用现有三栏布局，不另开页面，不做新的视觉框架。左栏保留原始问题和回答；中栏加入“问题补全”节点；右栏展示本次解析结果和静态方法映射。没有历史时显示未执行；有历史时显示真实完成/失败状态。无需把模型隐藏推理包装为“思考过程”。

将需要澄清与文档证据不足区分：前者标为“请补充问题”，后者仍为“证据不足”。澄清不显示引用按钮，也不声称调用了最终回答模型。保留当前外发勾选与停止等待行为，不自动重发失败请求。

连续三次短追问的历史 question 使用此前 resolvedQuestion，以避免把“它呢”“那价格呢”等短句再次当作独立历史锚点。只有服务端本次确认完成的结果可加入历史；停止后到达的旧响应不能进入下一轮。

## 9. 测试与交付

- 纯逻辑/契约：旧请求兼容，最多 3 轮，长度/null/type 校验，最近顺序、摘录标记、UTF-16 安全截断、无递归请求快照。
- 模型 adapter：首问零次 resolver 调用；有历史一次调用；structured unchanged/rewritten/clarification/refusal；非法 JSON、状态组合、超长、incomplete、超时与无自动重试。使用固定 transport，无真实 key。
- 编排：补全发生在缓存之前；独立问题同时进入 key、embedding、检索、重排和回答；不同历史同短句产生不同精确 key；相同独立问题可命中现有 exact/semantic；保留 tenant/actor/revision 隔离。
- 证据：最终模型输入不包含 history 数组/历史答案正文，不把历史 marker 继承为引用；权限失败发生在新增模型调用之前。提示注入样例覆盖固定输入和输出校验，但测试不能证明模型永不被注入。
- 澄清/异常：不调用缓存、embedding、检索或最终回答模型；无伪造引用；REST/SSE 正常终态、error、traceId 一致和 debug 隐藏。
- 前端：当前页最近三轮收集、连续三次追问、明确换话题、切换身份/范围/文档集合清空、新 sessionId、迟到响应隔离、刷新清空、修改 topK 保留历史。
- 浏览器：固定模型响应下的双轮与三轮流程，原/独立问题显示、澄清提示、真实阶段顺序、桌面和手机无溢出；不调用真实收费 API。
- 回归：后端 `mvn -Dapi.version=1.44 clean verify`、前端 unit/build/e2e、worker tests、`git diff --check`。通过与否以实施后的实际结果为准。

完成后更新 README、docs/api.md、docs/learning-workbench.md、docs/limitations.md，并新增中文 docs/learning/page-follow-up-resolution.md 和 docs/review/page-follow-up-resolution.md。文档解释历史与证据区别、额外调用成本、首问行为、未识别指代及窗口截断限制。

不承诺本轮真实改写质量、模型延迟或命中率已经经过评估。新增付费调用需另行确认；不修改 .env，不操作现有文档与向量。本设计仅单独提交文档，不提交已有语义缓存实现、不 push、不重启运行服务。

## 10. 面试解释

> I resolve follow-up questions into standalone queries before context caching and retrieval. The browser sends at most three bounded turns, which are treated as untrusted conversational hints, not factual evidence. Retrieval and answer generation use the resolved query and freshly authorized document evidence. Ambiguous requests ask for clarification, and failures do not silently fall back to guessing. This is page-local conversation support, not durable memory or an autonomous agent.

## 11. Review 检查

- [x] 检查现有请求、会话状态、真实模型边界、缓存、SSE 和前端生命周期。
- [x] 比较页面历史与 Redis 会话方案；用户选择页面历史。
- [x] 明确数据预算、成本、失败策略、缓存输入和隐私边界。
- [x] 检查范围、状态组合与文档中的未确定事项。
- [ ] 用户 review 本设计稿。
- [ ] 基于批准的设计制定实施计划并实施、测试、交付。
