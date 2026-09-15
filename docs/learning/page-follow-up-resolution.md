# 当前页面多轮追问：学习笔记

## 这次解决什么

上一轮问“苹果近几年发布会有哪些特点？”，下一轮只问“主要有哪些差异？”，后者缺少检索主题。语义缓存只能判断问题近似，不能凭空恢复上一轮对话。因此新增问题补全阶段，把追问变成可独立检索的问题，再进入原有 RAG 管线。

```text
当前原问题 + 页面最近最多 3 轮已完成回答
  -> 输入校验、当前身份/文档权限与 embedding 就绪检查
  -> query_resolution（首问跳过，有历史时一次模型调用）
       unchanged / rewritten -> 独立问题
       needs_clarification / refused -> 返回提示，后续阶段全部跳过
  -> 版本快照 + 精确上下文缓存
  -> 精确未命中：可选语义缓存 / 混合检索
  -> RRF -> 启发式重排 -> parent 展开 -> 引用上下文
  -> 使用独立问题和本次证据生成回答
  -> 权限、版本、引用复查 -> REST / SSE 最终结果
```

它是固定 Java 编排中的一个模型解析步骤，不是 Pi 工具循环、服务器端聊天记忆或新的多代理系统。

## 代码阅读顺序

| 文件 | 负责什么 |
| --- | --- |
| `frontend/src/query/history.ts` | 同一 session/scope 下选最近 3 轮 answered；使用上一轮独立问题维持主题；回答有界截断 |
| `frontend/src/query/useQuery.ts` | 页面会话、取消、迟到响应隔离、新 sessionId；展示记录不递归复制 history |
| `frontend/src/query/QueryWorkbench.tsx` | 请求携带历史、范围变更清空、外发确认、澄清展示 |
| `query/api/ConversationTurn.java` | 严格 JSON 类型、数量、长度校验，不接受 role/system 等字段 |
| `query/application/QuestionResolver.java` | 只解析问题的响应式接口 |
| `query/application/OpenAiQuestionResolver.java` | 非阻塞 Responses 调用、严格 schema、拒绝与错误处理 |
| `query/live/LiveQueryService.java` | preflight 后解析、缓存前替换有效问题、澄清终态、debug 和安全摘要 |
| `query/live/LiveContextService.java` | 原有精确/语义缓存与检索，接收的已是独立问题 |
| `frontend/src/query/client.ts` | 验证 SSE 顺序、trace、解析元数据和澄清时无证据/无下游执行 |

Java 路径相对于 `src/main/java/com/nexusagent/`。

## 三个关键边界

### 1. 原问题不等于检索问题

原问题保留在对话框；补全问题在“问题补全”详情显示。缓存 key、语义约束、embedding、检索、重排、最终答案 prompt 都使用补全问题，不能一部分用原话、一部分用补全结果。模型只能返回问题解析字段，不能修改 tenant、actor、文档范围、预算或 traceId。

例如同一句“它有什么区别？”可能指向不同产品。先解析得到独立问题，再生成精确 key，才能避免仅按短句误用上下文。不同历史如果最终得到同一个独立问题，在同一权限/版本/参数范围内可以复用上下文。语义命中的近似风险仍然存在，本功能没有把相似度升级成等价性证明。

### 2. 历史用于理解指代，不是证据

浏览器只携带问答摘录，不携带旧 context、citation 对象、向量或递归请求。模型看到的 history 是不可信用户数据，不是 system 指令、权限凭证或已确认事实。最终答案模型只收到独立问题与当前检索证据，不收到旧答案全文。

这能避免直接把上一轮答案当文档，但不能保证模型不会错误理解或引入历史里的错误前提。提示词不是安全隔离层，引用关联校验也不是事实验证。

### 3. 页面历史不等于 Redis memory

最多展示 12 轮，只发送最近 3 轮 `completed + answered`。失败、中断、拒绝、证据不足、澄清都不进入历史。每轮 question 最多 2000 个 Java/JavaScript UTF-16 字符单位，answer 摘录最多 1000；截断避免切断 surrogate pair，并设置 `answerTruncated=true`。这是字符限制，不是 token 限制。

刷新、离开问答页、清空、切换身份/知识范围/指定文档集合都会清空并轮换 sessionId。只改 topK/字符预算不清空。没有 localStorage、刷新恢复、跨设备历史或数据库聊天记录。Redis 仍只有原有有界缓存和状态；历史摘录不写缓存/状态/tool/audit。审计增加的是问题哈希、解析状态、历史数量，而不是内容。

客户端历史可被编辑，没有服务器签名。后端校验当前访问范围，但不会为客户端旧答案重建来源权限，不能承诺已展示文本在权限撤销后从用户设备消失。

## 模型调用、费用与失败

复用现有已配置回答模型和 `OpenAiHttpClient`，没有新 API key。`text.format` 使用严格 JSON schema，`store=false`，不提供工具、不关联 provider conversation。输入 JSON 额外限制 64 KiB，输出上限 1200 model tokens，reasoning effort 为 low；不返回隐藏推理。

| 情况 | 结果 |
| --- | --- |
| 首问/空 history | 不调用 resolver，原问题直接进入现有检索 |
| 有历史但新话题独立 | 调用一次 resolver，返回 unchanged |
| 明确追问 | 调用一次，返回 rewritten；继续查缓存/检索/回答 |
| 无法确定指代、要求编辑上一轮答案 | 正常 200 needs_clarification；无引用，不检索、不生成最终答案 |
| resolver 拒绝 | 正常 refused；不继续检索或答案生成 |
| 超时 | `QUERY_REWRITE_TIMEOUT`；不自动重试，也不猜补全结果 |
| JSON/状态/字段组合/长度不合法 | `INVALID_QUERY_REWRITE_RESPONSE`；不使用部分输出 |
| provider 拒绝或不可用 | `QUERY_REWRITE_REJECTED` / `QUERY_REWRITE_UNAVAILABLE`；安全错误、不回退模板 |
| 非空 history 发到离线模板模式 | 400 `MULTI_TURN_UNAVAILABLE` |

`NEXUS_QUERY_REWRITE_TIMEOUT=20s`，配置必须大于 0 且不超过 60s。解析在 SSE preflight 之后，因此浏览器可以看到该阶段 running/failed。超时或断开只取消本地工作，不能保证远端未计费。有历史时，即使上下文精确命中，仍有解析和最终回答两个模型调用；没有最终答案缓存。

`queryResolution` 只在 debug=true 返回；阶段 summary 只有数量、状态、原因码、模型版本，无历史正文。澄清响应仍携带 preflight 的就绪文档数量，不表示执行了检索；前端明确显示后续未执行。

## 本地运行与验证

保持现有 PostgreSQL/Redis/MinIO 配置与已准备好的虚拟文档。关闭旧的本项目后端后，在仓库根目录启动：

```bash
NEXUS_AGENT_ENABLED=true NEXUS_AGENT_WORKER_MODE=pi \
NEXUS_ANSWER_PROVIDER=openai NEXUS_EMBEDDINGS_PROVIDER=openai \
mvn spring-boot:run
```

Pi worker 只用于文档处理，知识问答不依赖它。另一个终端：

```bash
npm --prefix frontend run dev
curl --fail http://localhost:8080/api/v1/query/capabilities
```

打开 `http://127.0.0.1:5173/?view=query`，确认支持 pageFollowUp 和最多 3 轮。真实手动提交会外发当前问题、有界历史和证据并产生用量，只用已获许可的虚拟资料。先问完整问题，再追问“主要有哪些差异？”，检查补全主题；再切范围，确认历史清空。不要把固定输出当作模型必然会给出的结果。

不调用真实模型的回归：

```bash
mvn -Dapi.version=1.44 clean verify
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix workers/pi-worker test
git diff --check
```

Docker 需可用；`api.version` 是本地 Docker 测试客户端兼容设置。2026-09-15 验证：后端 353 项、前端单测 50 项、浏览器 16 项、worker 26 项全部通过。后端集成测试使用隔离 PostgreSQL/Redis 和合成模型 transport；浏览器测试覆盖连续追问、澄清、清空、范围变化、桌面/移动布局。没有运行付费问题解析请求，不宣称真实模型质量已验收。

## 面试答法与后续改进

“我们先把有界的页面追问解析成独立问题，再进入已有 tenant/version-scoped cache 和 RAG。这样检索与缓存不依赖缺主题的短句。历史只用于消解指代，最终事实来自重新校验的文档证据。模糊追问会要求澄清，不强行生成答案。第一版没有持久会话，最多三轮，并明确记录额外模型开销和失败阶段。”

后续重点应是建立真实追问评估集：主题保持、新话题、数字/否定保持、歧义澄清、错误前提、截断历史和权限变更。还需分别评估改写正确率、检索召回、答案证据支持率和额外延迟/费用。不要用 mock 测试通过来代替模型评估；也不要把当前页三轮上下文称为完整长期记忆。
