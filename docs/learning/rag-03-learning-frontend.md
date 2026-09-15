# 真实 RAG 第三步：双范围问答与学习前端

## 一句话定位

你选的是「搜索范围」，不是「答案引用」。默认知识库模式自动在有权访问、且当前模型向量就绪的文档里检索；指定模式只在选中文档里检索。两者最后都由同一 Java RAG 管线选 child、扩展 parent、生成引用并请求真实回答模型。Pi 仍只处理文档诊断和审批，不参与这条问答管线。

## 两种范围的契约

| 范围 | 请求 | 后端行为 |
| --- | --- | --- |
| `library`，默认 | 不带 documentIds 或传空数组 | 先按 tenant/PRIVATE owner 过滤，再筛当前 provider/model/dimension 全覆盖文档 |
| `documents` | 1–10 个 documentIds | 所选全部有权限且就绪才执行；否则 404/409 |

不传 scope 时，有 IDs 推断 documents，无 IDs 推断 library。library 搭配非空 IDs、未知 scope、documents 空选择都返回 400。

全库不等于无限量：学习实现最多支持 **200 份可访问文档**，SQL 读取 201 个 ID 作为越界哨兵。超过明确 `LIBRARY_SCOPE_TOO_LARGE`（400），不会只搜索前十/前两百然后声称全库完成。这个上限限制覆盖率查询和后续权限复查的工作量，不是经过性能基准得到的容量承诺。

向量未就绪的文档会从**两条**检索路径排除，包括它的全文结果。`scope` 响应包含 accessible / searched / excluded 数量和 CHUNKING_REQUIRED、EMBEDDING_INCOMPLETE、EMBEDDING_MODEL_MISMATCH 原因计数。其他租户/其他 owner 的 PRIVATE 文档连统计都不进入。documents 模式统计仅指选中范围。

零就绪文档返回 `LIBRARY_NOT_READY`（409），在 query embedding/回答模型之前结束。已进入检索但没有可用上下文，则沿用 `insufficient_context`，不调用回答模型；query embedding 此时可能已经产生用量。

## 从点击发送到答案

```text
浏览器生成 traceId，发送一次 POST /query/stream
  -> Java 校验输入、权限、当前模型覆盖率，形成就绪文档 ID 快照
  -> 缓存 bypass
  -> (问题 embedding -> vector search) || full-text search
  -> RRF -> heuristic rerank -> parent expansion -> character budget
  -> 当前证据/访问复查 -> OpenAI Responses
  -> 引用子集校验 -> 受限状态/审计写入 -> 最终证据/访问复查
  -> message + completed
  -> 浏览器核对 trace/sequence/provider/citation，显示答案与引用
```

快照只确定本次搜索哪些文档，不是长期数据库事务。执行中新增/变就绪的文档在下一问加入；原已选证据失效则失败，不自动付费重试。既有权限复查不等于跨远程模型调用的可串行化隔离。

## 三栏怎么学

- 左侧：单轮问题和已确认答案。每次发送不附带上一轮消息；sessionId 只是短期状态关联，不是对话记忆。最多保留 12 条在当前页面内存。
- 中间：真实服务阶段 DAG。全文检索与问题向量分支并行；尚未收到事件的节点保持未执行，出错不把后续节点标绿。stage attempt=1，不是底层 HTTP 内部重试次数。
- 右侧：输入范围、实际输出、源码职责。输入范围是用户请求参数，不冒充每个 Java 方法的全部实参；实时输出只有计数/状态摘要，候选分数和正文来自最后授权通过的 debug 响应。
- 引用：点击 `[C1]` 查看实际返回的 parent 片段，并按 child 全局 `[charStart, charEnd)` 高亮交集。沿用 Java/JS 的 UTF-16 索引，不是假定字节或 tokenizer offset。裁剪的父块明确标识，未返回的原文不会由浏览器补造。
- 源码职责是静态映射，不是完整调用栈、动态调试器或模型隐藏推理。答案按纯文本展示，不执行文档/模型返回的 HTML。

## 防止重复调用与旧身份数据

`streamQuery` 只发一个 POST，不自动重连重发。断线或缺少 completed 显示结果未确认；即使已收到 message，也不会提前显示未确认答案。一次新发送是新的潜在付费请求，traceId 不是幂等键。

预检查等待上限 60 秒，流读取空闲上限 180 秒，总请求上限 360 秒；服务端仍执行自己的较短阶段保护。最多接受 64 个事件、2 MiB SSE 字节，parser 单缓冲上限 1 MiB；异常响应安全失败。首版只支持后端当前协议，不是任意 SSE 客户端。

取消停止本地等待，不保证远端模型已取消/未收费。切换 tenant/actor 或离开问答视图会取消旧请求、清空答案/引用/文档选择；迟到回调由 generation 与 AbortSignal 双重挡住。切换身份也清理交给文档处理视图的预选文档。访问撤销错误清空旧问答证据，不继续展示历史 context。已经展示过的资料无法从用户记忆/截图中撤回，Header 身份也不是生产认证。

## 关键文件

| 文件 | 读代码时关注 |
| --- | --- |
| `query/live/LiveQueryInput.java` | 两种 scope、兼容旧请求、输入上限 |
| `query/live/QueryLibraryRepository.java` | 先授权再统计当前模型 coverage；201 哨兵 |
| `query/live/LiveQueryGuard.java` | librarySnapshot/selectReady 与既有 access/readiness/evidence |
| `query/live/LiveQueryService.java` | 真实 prepare/pipeline，不委托 Pi |
| `query/api/QueryScopeSummary.java` | 只返回有权范围的统计，不泄露被隐藏文档 |
| `frontend/src/query/QueryWorkbench.tsx` | 双模式、模型能力检查、外发确认、聊天 |
| `frontend/src/query/client.ts` | 单 POST、事件与引用关联校验、completed 才放出答案 |
| `frontend/src/query/useQuery.ts` | 有界内存、取消、重复发送/迟到响应防护 |
| `frontend/src/query/QueryFlow.tsx` | DAG 与实际状态，不伪造完成 |
| `frontend/src/query/QueryInspector.tsx` | 静态解释、debug 数据、父块/child 高亮 |
| `frontend/src/components/DocumentPanel.tsx` | 模型就绪、Pi 跳转、明确确认的向量替换 |

Java 路径相对 `src/main/java/com/nexusagent/`。后端数据结构不变：没有新 migration，没有修改 chunk_index 或 offsets 语义。

## 本地运行与手动检查

本次未修改 `.env`、未迁移现有向量、未执行付费问答。使用已有私有 key；不把 key 放到浏览器。先停止 8080 的旧后端，再在原终端启动更新版本：

```bash
docker compose up -d
NEXUS_ANSWER_PROVIDER=openai NEXUS_EMBEDDINGS_PROVIDER=openai \
  NEXUS_LLM_MODEL=gpt-5.6-luna mvn spring-boot:run
```

若需要 Pi 文档处理，同时保留已有 Harness 配置（`NEXUS_AGENT_ENABLED=true`、服务端/worker 模式均 `pi` 及共享 worker token），另起 worker，详见 [工作台启动](../learning-workbench.md)。纯问答不用 Pi worker。

```bash
npm --prefix frontend run dev
curl --fail-with-body http://localhost:8080/api/v1/query/capabilities
```

打开 http://127.0.0.1:5173/。已在运行的前端不必重复启动。capabilities 应包含 liveQueryReady=true、OpenAI embedding、真实 answerModel、queryScopes 两项；这只确认配置，没有探测 key/account 可用性。404/缺少 scopes 通常说明后端未重启。

1. 「文档与准备」上传 `examples/learning-corpus/` 的合成资料，未就绪项旁「处理文档」只跳转/预填，不自动执行。
2. 使用原 Pi 审批完成分块/补向量。已有本地模型向量不兼容 OpenAI；「重建向量」会弹出确认，明确外发 child 文本、替换向量和费用，不重分块。
3. 默认知识库模式不勾选文档。确认外发许可，问资料里有依据的问题，观察真实流程与 scope 统计。
4. 改指定文档，只勾选就绪项。比较候选/引用；最终引用不是人工预选。
5. 点击 RRF 看 source/ranks/score；点击 parent/context 看预算；点击答案引用看 child 高亮。
6. 切换 tenant/actor，确认历史清空；无就绪文档明确 409，不编造答案。不要为测试支付风险随意断开后连续重试。

可选 curl，每条是独立请求且可能收费：

```bash
# Whole accessible ready library; no documentIds required.
curl --fail-with-body -N http://localhost:8080/api/v1/query/stream \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -d '{"scope":"library","question":"Which approval rules are documented?","topK":5,"contextBudgetChars":4000,"debug":true}'

# Replace the variable with an accessible, current-model-ready document UUID.
DOC_ID='<synthetic-document-uuid>'
curl --fail-with-body http://localhost:8080/api/v1/query \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous' \
  -d "{\"scope\":\"documents\",\"documentIds\":[\"$DOC_ID\"],\"question\":\"Which approval rules are documented?\",\"topK\":5,\"debug\":true}"
```

## 测试与面试防守

```bash
mvn -Dapi.version=1.44 test
npm --prefix workers/pi-worker test
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
git diff --check
```

后端测试使用真实隔离 PgVector PostgreSQL 和模拟模型 transport；浏览器测试是合成 HTTP，不调用真实 key。UI 截图在忽略的 `frontend/test-results/`。不能把截图/通过测试写成真实模型质量验证。

面试可以说：“我把文档准备与问答分开。用户默认不需要指定文档，服务端先解出有权且模型就绪的范围，再执行固定 RAG。前端呈现真实并行阶段和最终证据，引用高亮能追溯 child 与 parent。为了避免重复付费，POST 断流不自动重发；切换身份会清空并取消旧状态。它是可解释的学习界面，不是完整生产聊天产品。”

仍需承认：无真实登录/生产隔离保证、无多轮记忆/查询恢复、无事实性或检索质量评测；reranker 仍是启发式、全文配置 English、预算仍是字符。全库 coverage/复查仍较粗粒度，未来可用版本字段与定点批量读取改善；真正缓存复用需补版本与权限失效策略，目前直接 bypass。真实付费端到端问答验收尚未执行。
