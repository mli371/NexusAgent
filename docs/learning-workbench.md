# 学习工作台：启动与验证

最新补充：[真实问答上下文缓存与 CI](learning/live-context-cache-ci.md)。Redis 开启时，更新后端会应用 V10；命中会显示缓存复用，仍生成真实回答。历史三步笔记中的 bypass 描述是当时的实现。

新增：[语义上下文缓存](learning/semantic-context-cache.md)。精确 miss 后可复用同作用域的近义问题证据；图中新增语义缓存阶段，显示相似度和阈值。语义命中保留本次问题向量调用，历史检索/重排分数明确标为来源问题的数据。默认阈值不保证改写一定命中；无需新增数据库迁移或服务。

新增：[Child 优先覆盖](learning/coverage-first-context.md)。预算保持 4000 字符；重排后先预留完整 child，再均衡展开附近 parent。流程新增「Child 选择」，详情先展示完整 child，再展示可展开的 parent。回答旁显示证据纳入数、父块裁剪数、预算未纳入数、正文用量及答案引用数；这几项不能互相替代。未纳入候选可查看文件名和原因。缓存命中明确标为「缓存分配」，不伪装重新执行。证据详情只在最终权限复查通过后显示，不随中间 SSE 泄露。新策略自动改变缓存 key/scope，无需清空 Redis、重新 chunk/embed 或提高预算。

真实问答三步已实现，当前 review：[第三步双范围聊天与阶段图](review/rag-phase-3.md)。默认页面是「知识问答」，可切换「文档处理」。后端通过 `NEXUS_ANSWER_PROVIDER=openai` 显式启用，不会因为配置了 key 就自动启用；前端在离线/旧后端上显示未就绪，不调用模板。

这是学习界面，不是完整产品。知识问答调用 `/api/v1/query/stream`；文档处理调用 `/api/v1/agent/runs`，不调用旧的确定性 `/api/v1/agent/query`。问答的范围、引用和边界详见 [第三步学习笔记](learning/rag-03-learning-frontend.md)。

## 1. 启动

需要 Java 17+、Maven、Docker 和 Node 22.22.2+。在仓库根目录运行命令；三个服务各用一个终端。

先启动依赖：

```bash
docker compose up -d
docker compose ps
```

保留已有私有 `.env`。按照 [Harness 配置](agent-harness.md) 确认 `NEXUS_AGENT_WORKER_TOKEN` 已配置，Java 和 worker 使用同一个至少 32 字符的本地密钥。不要把密钥填入浏览器、提交到 Git，或使用 `NEXUS_PUBLIC_` 前缀。

终端 A，运行或重启 Java 后端到本次版本：

```bash
NEXUS_AGENT_ENABLED=true NEXUS_AGENT_WORKER_MODE=pi \
  NEXUS_ANSWER_PROVIDER=openai NEXUS_EMBEDDINGS_PROVIDER=openai \
  NEXUS_LLM_MODEL=gpt-5.6-luna mvn spring-boot:run
```

默认端口 8080。不要同时启动两个占用 8080 的后端。旧进程不会自动加载 `/query/capabilities` 和双范围支持；本次无需新增 Flyway 迁移。启动不主动发起付费查询，但后续提交问答/处理任务/重建向量会产生外部用量，请只使用获准的合成资料。

终端 B，启动 Pi worker（纯问答不需要它，文档处理才需要）：

```bash
npm --prefix workers/pi-worker ci --ignore-scripts
npm --prefix workers/pi-worker run build
NEXUS_AGENT_WORKER_MODE=pi npm --prefix workers/pi-worker start
```

如果已有同模式 worker 正在运行，不需要再启动一个。`pi` 模式由服务端和 worker 配置选择，不由页面切换。模型 API key 仅在本地后端/worker 配置中；`scripted` 仅保留给无模型测试，不是当前问答体验。

终端 C，启动前端：

```bash
npm --prefix frontend ci --ignore-scripts
npm --prefix frontend run dev
```

打开 **http://127.0.0.1:5173/**。默认只监听本机，不是原型预览地址。如果端口被占用，显式指定另一个端口：

```bash
npm --prefix frontend run dev -- --port 5174
```

Vite 仅代理 `/api/v1` 到本机 8080，不代理 `/internal`，不新增后端通配 CORS。`NEXUS_FRONTEND_API_TARGET` 可配置另一个本机 HTTP 后端，仅供开发服务器使用。前端环境目录和文件服务范围均限制在 `frontend/`，不读取根目录 `.env`。

## 2. 一次完整的手动验证

**知识问答**：默认整个知识库，不必先选引用文档。后端搜索当前身份可访问且当前模型就绪的文档，并显示排除原因。也可切换指定文档，只勾选就绪项。确认外发许可后发送问题，观察并行检索、RRF、重排、父块扩展、回答和引用校验；点击引用看高亮。数据要先准备好，不会因提问自动修复。模型不匹配可从「文档与准备」明确确认重建向量。详见第三步笔记中的费用和限制。

**文档处理**：从顶栏切换视图，或在未就绪文档旁点击「处理文档」。跳转只预填文档/申请，不会自动创建任务或批准。

1. 保持 `default / anonymous`，点击文档，上传 `examples/learning-corpus/events/` 或 `standards/` 中的一份 Markdown。最多选择 10 份；只支持现有 TXT/Markdown 提取能力。
2. 选择该文件，点击「填入处理申请」。问题框会明确显示 `Approve processing:` 前缀，再发送。
3. 状态依次来自后端：已保存、worker 领取、工具观察、等待审批。点击流程节点查看真实输入/输出及静态源码职责。
4. 未分块文件会出现 CHUNK 申请。检查参数后手动批准。APPROVED 只代表同意，不代表已执行成功。
5. 分块完成后再次检查，缺少 embeddings 时出现独立 EMBED_MISSING 申请。再批准一次。
6. 查看两个 action 的实际结果、job ID、最终报告和 traceId。`HEALTHY` 仅是当前处理状态检查，不是检索质量评估。
7. 刷新页面确认恢复同一 run；可以复制已有 runId 打开。浏览器 sessionStorage 只存当前身份最近 20 个 runId，不存问题、工具输出、报告或凭证。

选择「填入只读诊断」则不会让 scripted worker 提议处理。已经处理完成的文档通常直接返回诊断报告，不一定有审批。审批批准的是分块/embedding，不是文档内容中的业务申请。

## 3. 布局和数据含义

- 左栏：独立任务消息、当前文档、待办审批、最终诊断报告。不自动带上历史对话。
- 中栏：按 PostgreSQL 事件顺序显示真实节点；重复事件按 runId + sequence 去重。每次重试/重新领取分别保留。
- 右栏：观察记录的受限输入/输出；「源码职责」是人工维护的代码映射，不是动态调用栈或模型思考过程。
- 小屏使用对话、执行流程、调用详情三个页签；较宽平板将详情放在下方。

工具详情来自新增的 owner-scoped [观察接口](api.md#optional-diagnostic-runs)。对未知工具、超长字段、超限载荷显示省略信息，不伪造空结果代表成功。

## 4. 失败与恢复

- 网络断开不等于任务失败。最多连续尝试 5 次，指数退避至 10 秒；先补历史再续订 SSE。仍无法连接时可手动重新同步。
- 任务达到终态后，补齐历史并关闭连接。SSE 使用 fetch 携带身份 header 和 Last-Event-ID；不是模型 token 流。
- 任务或任一文档失去访问权限时，清空当前观察、问题和文档显示，停止订阅。切换身份同样取消旧请求并清空视图，但不取消后端任务。
- 创建任务响应不确定时，相同问题和文档在当前页面重试沿用幂等键；刷新后不会保留这个临时键。上传不自动重试，因为可能已经存储成功。
- 审批 409/过期等冲突会显示错误并刷新，不自动重复决定。取消请求不等于写操作已回滚，页面单独显示 cancellationPending。

## 5. 测试

```bash
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run test:e2e
npm --prefix workers/pi-worker test
mvn -Dapi.version=1.44 test
```

普通浏览器测试需要本机 Google Chrome，使用合成 HTTP 响应，检查页面行为；不需要 Java/数据库。默认测试端口 5176，可通过 `NEXUS_TEST_FRONTEND_PORT` 更改，不占用开发端口 5173。

真实浏览器集成测试是显式 opt-in，避免日常 `mvn test` 强制依赖 Chrome/Node：

```bash
npm --prefix workers/pi-worker run build
mvn -Dapi.version=1.44 -Dtest=WorkbenchBrowserIT test
```

它启动临时 PgVector PostgreSQL、MinIO、随机端口的 Java、scripted worker 和 Vite。浏览器真实上传并批准两次，随后检查持久化 chunks、embeddings 和两个成功 job；测试结束清理这些进程/容器，不操作现有 Compose 文档。它显式关闭 Redis、清空测试 worker 的模型配置，不测试真实模型质量。需要 Docker 和已安装的前后端 npm 依赖、Google Chrome。没有 Docker 时此 opt-in 测试失败，不静默跳过。

## 6. 常见问题与限制

- `/tools` 404：首先重启旧 Java 进程；也可能是身份不匹配、文档已不可见或 observationId 不属于该 run。
- 长期 QUEUED：检查独立 worker 是否启动、token 和模式是否一致。
- 没有审批：检查是否选择未处理文档；scripted 是否有可见的处理申请前缀。不要修改已完成结果来伪造审批。
- 示例中中文政策适合测试处理与版本差异，但现有 English full-text 和本地 hash embedding 不保证中文检索质量。
- 问答支持当前页面最近 3 轮已完成回答的追问补全，没有服务器端聊天记忆、刷新恢复、登录、生产授权、多人协作、模型 token 展示或导出。切换身份、视图、知识范围或指定文档集合会清空历史；topK/预算变化不清空。观察接口白名单不是 DLP；reason/question/报告可能含用户输入的敏感信息，请仅用虚拟资料。

### 当前页面追问

重启后端后，`/api/v1/query/capabilities` 应包含 `pageFollowUpSupported=true` 和 `maxHistoryTurns=3`。首问跳过“问题补全”；有历史的下一问先调用已配置的模型解析，再用补全后的问题查缓存、检索和生成回答。点击“问题补全”可以查看原问题、独立问题、历史轮数和耗时，不展示模型隐藏推理。独立新话题可以保持原样；指代不明确时返回“请补充问题”，后续阶段标记为未执行，不是假装检索成功。

测试示例：先问“苹果近几年秋季发布会有哪些特点？”，再问“主要有哪些差异？”，检查第二轮独立问题是否保留主题。页面只发送最近 3 轮 answered，每轮回答最多 1000 字符；历史用于理解指代，不直接成为证据。有历史时即使上下文缓存命中，也会增加一次问题解析模型调用。外发确认涵盖历史摘录。实现细节、测试命令与限制见 [中文学习笔记](learning/page-follow-up-resolution.md) 和 [review](review/page-follow-up-resolution.md)。
- 前端最多读取 200 条工具摘要、1000 条事件；当前后端默认工具预算 30、事件上限 500。主动提高后端上限需要同步审查前端边界。
- UI 编译输出不代表部署配置已经完成。本次仅验证本机开发代理，没有配置公网托管、TLS 或生产认证。
