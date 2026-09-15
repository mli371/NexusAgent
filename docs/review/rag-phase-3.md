# 第三步 Review：双范围知识问答与学习前端

## 本次交付

已按批准方案实现「整个知识库」和「指定文档」两种范围，默认进入知识问答。保留原 Pi 文档处理和人工审批，问答不借 Pi 执行处理操作。

| 部分 | 行为与 review 重点 |
| --- | --- |
| 默认全库 | tenant/PRIVATE owner 过滤后选择当前模型向量全覆盖文档；两条搜索路径同范围；返回未就绪原因计数 |
| 指定文档 | 1–10 份，全部就绪才允许问答；旧带 IDs 请求兼容 |
| 全库边界 | 200 份可访问文档，超限明确 400；零就绪 409，均不调用模型，不偷偷只取前十 |
| 聊天 | 实际 OpenAI 模式能力检查、外发许可、单轮请求、纯文本答案和引用；不回退模板 |
| 流程图 | 实际 stage 状态、耗时、并行检索分支；无事件则未执行，不伪造 token/调用栈/隐藏推理 |
| 右侧详情 | 最终授权结果中的候选/上下文 + 单独标识的静态源码职责 |
| 引用 | 点击 marker，显示实际父块片段及 child 全局范围高亮；保留 chunk IDs、文件名和裁剪提示 |
| 准备 | 文档就绪/模型不匹配可见；跳转 Pi 只预填；替换向量单独确认，绝不自动批准 |
| 隔离/断线 | 只发一次 POST，completed 才展示答案；身份/视图切换取消旧请求并清空，迟到响应不回填 |

## 本次文件范围

- 后端：`QueryRequest`、`QueryResponse`、`QueryScopeSummary`、`QueryCapabilitiesController`；`LiveQueryInput`、`LiveQueryConfiguration`、`LiveQueryGuard`、`LiveQueryService`；新增 `QueryLibraryRepository`。
- 后端测试：`LiveQueryServiceTest`、`LiveQueryIntegrationTest`、`QueryLibraryScopeTest`。
- 前端：`src/query/` 下的协议/请求、状态 hook、聊天、DAG、引用/源码详情、样式与测试；`App.tsx` 双视图入口；文档准备面板；API client；`main.tsx` 样式接线。
- 浏览器：新增 `e2e/query.spec.ts`，原 Pi 测试改为显式 `?view=documents`，测试配置覆盖两种视图。
- 文档：README、ROADMAP、API、architecture、limitations、interview-defense、demo、troubleshooting、工作台指南、设计状态和本次学习/review；第二步笔记增加历史范围说明。

没有新 migration；没有改 worker/Pi 审批逻辑；没有改 `.env` 或自动替换现有向量。工作区原有大量未提交改动和私人笔记仍保留，未 stage/commit/push。

## 实际验证结果

| 检查 | 结果 |
| --- | --- |
| `mvn -Dapi.version=1.44 test` | 278 项通过，0 失败，0 跳过；含真实隔离 PostgreSQL/PgVector |
| `npm --prefix workers/pi-worker test` | 26 项通过；原协议/审批/恢复回归 |
| `npm --prefix frontend test` | 35 项通过 |
| `npm --prefix frontend run build` | TypeScript 与 Vite 构建通过 |
| `npm --prefix frontend run test:e2e` | 12 项通过：新增问答 8 项 + 原文档处理 4 项 |
| `git diff --check` | 通过 |

后端模型 transport 是合成响应；浏览器 HTTP 是测试 fixture。没有真实付费模型端到端验收，也没有测试答案质量。完整测试仍出现既有 Netty macOS DNS fallback、ByteBuf 未释放告警和容器清理附近的连接重置；本次未定位/修复，不能因为测试绿色就忽略，沿用第二步限制说明。

新增检查包含：全库超过十份不截断、tenant/PRIVATE 和模型就绪排除、空库不调模型、201 越界；断流不重发、不提前显示 message、trace/sequence/引用不一致拒绝、模板模式拒绝、取消后的迟到响应、12 条内存上限、身份变化清空、纯文本防 HTML 执行、UTF-16 child 高亮与并行分支状态。

Chrome 验证桌面 1440、1920 和手机 390/320 宽度。截图人工检查后修复手机页脚覆盖输入框，并加入不重叠断言。截图在忽略的 `frontend/test-results/qa-*.png`，其中答案来自测试 fixture，不能当作 live model 成果。

## 你怎么验收

1. 前端使用 http://127.0.0.1:5173/，默认知识问答。现有前端服务可复用。
2. 本次只读检查发现 8080 的旧后端对 capabilities 返回 404。先停止旧 Java 进程，再按 [第三步学习笔记](../learning/rag-03-learning-frontend.md#本地运行与手动检查) 启动新版本、显式开启 OpenAI 问答/embedding。未替你中断已有 Pi 任务。
3. 准备少量合成资料，确认当前 embedding 模型就绪后，才勾选外发许可并发送真实问题。旧向量不要通过问答自动迁移。
4. 先全库提问，再指定一份文档比较结果。检查中栏分支与右栏 RRF/父块/预算；点击答案引用查看 child 高亮。
5. 真正的 API 额度/账号/模型可用性和回答效果尚需这次手动验收，不以 capabilities=true 代替。

## 仍然不承诺

没有生产鉴权、持续授权撤回、多轮记忆、查询持久化恢复或自动付费重试；没有 cross-encoder、事实性评分或检索基准。全库快照和后续 chunks 复查是有界但较粗粒度实现，不是大型知识库性能方案。字符预算不是 token 预算。实时 context cache 继续 bypass，Redis 仅保存短期受限摘要。

到此停止第三步，不扩展新的产品功能。先 review，再决定是否用合成文档做明确同意的付费验收。
