# 真实 RAG 第一步：模型适配与向量重建

## 先记住三个边界

1. Pi 调用 LLM 做文档诊断，不等于原来的 Query API 已接真实问答。
2. 384 维本地 hash 和 384 维真实 embedding 不能比较。模型名称、provider、维度一起定义本次使用的向量空间。
3. 调用外部模型不能放在数据库事务里。本次先生成，再短事务提交，不让网络等待占住数据库锁。

这一阶段已完成 provider 与向量准备。回答适配器有真实 HTTP 实现及测试，但不是 Spring bean，因此尚未进入旧 Query API；真实问答入口、真正的阶段 SSE、客户端界面属于后两步。

## 关键代码入口

| 文件 | 需要理解的职责 |
| --- | --- |
| `model/OpenAiProperties.java` | 服务端 key、回答模型和超时；无 key 时不自动 fallback |
| `model/OpenAiConfiguration.java` | 创建限定 OpenAI 地址的非阻塞 WebClient，响应缓冲有上限 |
| `model/OpenAiHttpClient.java` | 安全错误、有限重试、总超时；不把原始错误 body 带回 API |
| `embeddings/application/OpenAiEmbeddingProvider.java` | 单文本 embedding 请求和模型、维度、有限数值、非零向量检查 |
| `embeddings/application/ChildChunkEmbeddingService.java` | 权限、普通补向量与显式重建、job/audit 编排 |
| `embeddings/application/EmbeddingWriteService.java` | 唯一的业务提交边界：行锁、访问复查、child 快照比较、事务写入 |
| `embeddings/repository/VectorSearchRepository.java` | SQL 中先限制 tenant/owner 和模型，再按 cosine distance 排序 |
| `query/application/OpenAiAnswerGenerator.java` | Responses 结构化输出与引用集合校验；本阶段不装配进 query |
| `query/api/QueryCapabilitiesController.java` | 只读能力投影，明确 liveQueryReady=false |

上述路径均相对 `src/main/java/com/nexusagent/`。不需要先理解所有类，先看普通 embed 与 replaceExisting 的分支，再看事务边界。

## 向量状态的语义

- `childChunkCount`：当前 child 总数。
- `embeddedChildChunkCount`：已有向量的 child 数，不论模型。
- `matchingChildChunkCount`：匹配当前 provider/model/dimension 的数量。
- `mismatchedChildChunkCount`：已有但模型不匹配的数量。
- `missingChildChunkCount`：真正没有向量行的数量，不把“有错误模型”混在这里。
- `complete`：child 非空，并且所有 child 都匹配当前模型。

例如 12 个 child 都是旧本地向量，切换到 OpenAI 后应是 embedded=12、matching=0、mismatched=12、missing=0、complete=false。旧实现仅比较行数会把这种情况误判成已就绪。

## 两条写入路径

普通 embed 只补缺失。发现不匹配模型先拒绝，返回 409 `EMBEDDING_MODEL_MISMATCH`，不会替你决定覆盖。生成一个 child 向量后进行一次短事务提交，期间失败可能留下已成功写入的 child，重试可以跳过它们。

`replaceExisting=true` 是用户明确选择的向量重建，不是 force chunking。最多 256 个 child、总文本不超过 1,000,000 Java 字符。全部向量生成成功后才一次提交，失败保持原向量不变。

提交事务先锁文档行，复查访问，再比较完整 child 记录列表，包括 ID、文本、parent、index、offset 和创建时间。代码直接比较已经加载的记录，不额外计算或持久化哈希。即使数量没变，只要重新 chunk 后 ID 变化，也会拒绝本次旧快照的提交。

`ChunkRepository.replaceChunks` 使用同一文档行锁，避免检查通过后 chunk 又被同时替换。网络调用不持锁，锁只覆盖检查与数据库写入。此锁约定不等于整个系统的分布式锁或 exactly-once 保证。

重建不改变 parent/child ID、document-global chunk_index、全局 char_start/char_end（end exclusive）。embedding upsert 更新向量与模型元数据，保留已有向量行的 created_at 并更新 updated_at。

REEMBED job 仍是同步操作状态记录。成功/失败会更新 job；进程崩溃或断连可能留下 RUNNING，需要排查，不能视为自动恢复的任务队列。commit 成功后 job 状态写入失败也不能宣称数据库已回滚。

## HTTP 与引用校验

Embedding 调用 `/v1/embeddings`，明确设置 `encoding_format=float` 和 `dimensions=384`。默认 20 秒覆盖有限重试，对 429/5xx 最多再试一次；输入限制 1–8000 字符，不是 token 计数器，provider 仍可能拒绝超 token 上限输入。

Answer 调用 `/v1/responses`，模型为配置中的 `gpt-5.6-luna`，输出最多 2000 tokens，默认总超时 90 秒，不自动重试，不提供工具。解析 message/output_text，忽略 reasoning 内容，不假设 output 第一项是答案。

返回的正文引用集合、usedCitationMarkers、本次上下文允许的引用集合必须一致/包含关系正确。空证据不调用模型；证据不足或拒答返回明确状态和空引用。未知引用、缺引用、截断、无效 JSON 都是错误，不悄悄回退模板。

这只检查引用的结构和来源关联，不证明每句话正确。`store:false` 也不是零数据保留承诺。向量文本、问题和选中证据会外发到 OpenAI，必须先确认资料可外发。

## 本地验证

本阶段未改你的 `.env`，未重启现有服务，未迁移任何现有向量。当前正在运行的旧 Java 进程不会自动获得新代码。

不调用真实模型的测试：

```bash
mvn -Dapi.version=1.44 test
npm --prefix workers/pi-worker test
git diff --check
```

准备真实 embedding 联调时，在本地 `.env` 保留你自己的 key，并明确设置以下非秘密项；不要把 key 放进示例或终端输出：

```dotenv
NEXUS_EMBEDDINGS_PROVIDER=openai
NEXUS_EMBEDDINGS_MODEL=text-embedding-3-small
NEXUS_EMBEDDINGS_DIMENSION=384
NEXUS_LLM_MODEL=gpt-5.6-luna
NEXUS_OPENAI_EMBEDDING_TIMEOUT=20s
NEXUS_OPENAI_ANSWER_TIMEOUT=90s
```

先停止自己之前启动的 Java 进程，再运行 `mvn spring-boot:run`，避免占用已有 8080。不要为了准备向量而重置数据库或删除 chunk。

```bash
curl --fail-with-body http://localhost:8080/api/v1/query/capabilities
curl --fail-with-body "http://localhost:8080/api/v1/documents/$DOC_ID/embedding-status" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

以下是有外部用量、会修改向量的明确操作。只在选好合成文档并同意替换后执行，不是本次已运行的命令：

```bash
curl --fail-with-body -X POST "http://localhost:8080/api/v1/documents/$DOC_ID/embed?replaceExisting=true" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
curl --fail-with-body "http://localhost:8080/api/v1/documents/$DOC_ID/ingestion-jobs" \
  -H 'X-Tenant-Id: default' -H 'X-Actor-Id: anonymous'
```

这里不是 Pi 的审批 URL。Pi 仍只能提议 CHUNK 或 EMBED_MISSING，不能执行 replaceExisting；后续页面为人工明确操作提供确认界面，但不把它描述成生产管理员授权。

## 面试解释

“我把模型服务放在已有接口后面，用 WebClient 隔离远程调用。迁移 embedding 时，必须同时考虑模型空间和数据一致性，所以检索先按模型过滤，写入不能只判断行数。重建先在事务外生成完整的新向量，再锁文档并验证 child 快照，用短事务原子替换；失败保留旧向量。这样不需要重新分块，也不会破坏后续引用依赖的 chunk ID。”

不要说已经完成真实问答客户端、检索效果评测、生产鉴权或事实性保障。启发式重排没有换成 cross-encoder，Spring AI 仍只是可选边界，本次客户端是直接 WebClient HTTP 实现。
