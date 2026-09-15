# NexusAgent 学习工作台：首版设计

日期：2026-09-11
状态：三栏布局已确认；本文供实现前审阅，功能尚未接入。

## 1. 目标与边界

把已有 Agent Harness 的命令行演示变成可操作、可解释的学习界面：上传虚构文档，提出文档处理请求，看工具调用、状态流转与源码职责，在页面上批准或拒绝具体操作。

选择已经确认的三栏布局：左侧对话与审批，中间执行流程，右侧步骤详情。流程优先会压缩对话空间，对话优先会压缩调度信息；首版只实现三栏工作台，不保留草图中的三套布局切换。小屏幕使用三个视图标签切换。

首版只接入 `/api/v1/agent/runs` 的文档处理流程。现有 RAG query 和早期 `/api/v1/agent/query` 是不同接口，不混用，也不将其包装成 Harness 的对话能力。RAG 问答暂不进入本次实现。

不做正式登录、OAuth、RBAC、后台管理、流程编辑器、完整调用栈追踪、多轮持久记忆、模型配置页面或新 agent 工具。不改变现有人工审批策略，不增加强制重分块入口。

## 2. 页面与实际交互

### 顶部与文档选择

- 显示当前 tenant / actor，允许切换本地演示身份；始终标注“Header 演示身份，不是生产认证”。默认使用已有本地演示身份 `default / anonymous`，不暗示已登录。
- 文档面板从现有列表接口加载、分页，显示文件名、状态、可见性和大小。上传使用本地文件选择，支持本项目当前可提取的 UTF-8 `.txt` / `.md`，逐个上传，不做目录自动导入。
- 上传时明确选择 `TENANT` 或 `PRIVATE`；仅上传不自动 chunk、embed 或批准操作。错误直接显示后端安全错误信息；文件后缀检查不替代后端校验。
- 默认上传提示上限 25 MiB，与当前默认配置一致；界面注明实际限制由后端配置决定，服务返回的 413 必须正确呈现。
- 选择 1 至 10 份已有文档后才能提交 run，问题非空且最多 2,000 个字符；后端仍是最终校验者。
- 语料来自 `examples/learning-corpus/events/` 与 `standards/`，不自动上传 README 或答案表。

### 左侧：对话与审批

每次发送创建一个独立诊断任务，显示本次问题、选中文档与最终报告。不会把本地聊天记录悄悄发送给模型作为历史记忆；同一时刻只跟踪一个选中的 run。支持通过 runId 重新打开已有任务，暂不新增服务端历史任务列表。

执行期间的进度文字来自实际事件与状态，明确标为系统状态，不伪造模型逐 token 输出。最后将 `report.summary / findings / unresolved` 按实际结果展示，区分 `scripted` 和 `pi`。

审批面板展示 action、documentId、文件名、规范化参数、reason、有效期与当前状态。批准或拒绝时绑定当前 runId / approvalId，并防止双击并发提交。批准不显示为执行完成：等待实际 actionResults 与事件确认。若审批已过期或状态冲突，刷新真实状态并提示，不自动换一个审批继续提交。

提供取消请求按钮与确认提示；`cancellationPending=true` 时显示“取消已请求，等待后端确认”，不能提前宣称写操作回滚或任务已停止。

### 中间：真实流程与事件

使用普通 React 组件和 CSS 连线绘制纵向流程，无需可拖拽的图编辑库。流程由 run 快照、持久化事件、工具调用与执行记录共同投影，不按固定定时器播放。

工具实例以 observationId 标识，审批以 approvalId 标识，执行以 executionId 标识；同一工具再次调用、重试或恢复时保留独立记录，不能按工具名合并。显示 attempt / retryOf 关联，状态以服务端数据为准。

允许展开事件时间线、查看已有历史，但不增加时间旅行回放播放器。未来可能发生的动作只能放在“条件分支说明”中，不能当作已经调度的节点。创建任务、worker 领取、工具调用、人工审批、动作执行和重新检查需明确区分。

本次可观察的是工作流与工具边界，不是每个 Java 私有方法或每次数据库调用。模型内部思维链不展示，也不尝试推断。

### 右侧：步骤详情与学习说明

- 运行记录：真实输入、经筛选的结果、状态、关联 ID、记录时间与错误码。无结果显示“尚无结果”，不填造成功响应。
- 源码映射：维护少量静态的 Java / TypeScript 文件路径、函数名称、职责与关键调用顺序。与运行数据分开标注，不冒充 profiler 调用栈。
- 首版不复制整个源文件到浏览器，不提供任意文件读取 API，不增加源码编辑器。需要深读时通过文件路径回到 IDE。
- 长 JSON 可滚动；省略内容必须有明确标记。模型报告、文件名和 reason 使用文本渲染，不执行文档中的 HTML、脚本或指令。

## 3. 接口与数据来源

### 复用现有接口

| 用途 | 接口 |
| --- | --- |
| 上传、列出、查看文档 | `POST /api/v1/documents`；`GET /api/v1/documents`；`GET /api/v1/documents/{id}` |
| 创建与恢复查看任务 | `POST /api/v1/agent/runs`；`GET /api/v1/agent/runs/{runId}` |
| 持久化事件与订阅 | `GET /api/v1/agent/runs/{runId}/events`；`GET /api/v1/agent/runs/{runId}/events/stream` |
| 人工决定 | `POST /api/v1/agent/runs/{runId}/approvals/{approvalId}` |
| 取消 | `POST /api/v1/agent/runs/{runId}/cancel` |

扩展已有、经 ownership 检查的 run GET 响应，附带 `question` 和 `documentIds`，用于刷新后还原本次任务。只对有权访问该 run 及其全部文档的请求返回，不扩大读取范围。现有 POST 请求格式和 worker 内部协议保持不变。

### 最小新增：工具观察记录的只读投影

目前完整工具结果只供内部 worker 读取。新增两条浏览器可用的读取接口：

1. `GET /api/v1/agent/runs/{runId}/tools?limit=25&offset=0`：返回工具摘要、总页是否还有数据；limit 范围 1–50，offset 非负，按 created_at、id 稳定排序。
2. `GET /api/v1/agent/runs/{runId}/tools/{observationId}`：返回单次调用的有限输入与结果。

共同响应包含 schemaVersion、runId、traceId。摘要项包含 observationId、invocationId、toolName、attempt、retryOf、status、createdAt、finishedAt；详情补充 arguments、result、payloadOmitted 和 omittedFields。

实现复用 `AgentRunService.owned()` 与文档访问检查，并要求工具记录属于指定 run。跨 tenant、跨 actor 或不可访问文档统一遵守现有 404 隐藏策略。所有者检查必须覆盖列表与单条详情，不能只在页面隐藏按钮。

按已知三个工具的字段白名单投影：

- `inspect_document`：文档状态、计数、模型描述与 condition。
- `list_ingestion_jobs`：既有有界任务摘要与结构化错误类别，不返回原始异常堆栈。
- `propose_retry`：文档、action、有限 reason、审批状态和规范化参数。

不直接序列化完整数据库行；不返回 claimToken、claim_hash、worker token、请求鉴权 header、API key、raw document、向量或任意未来工具字段。单条响应 UTF-8 序列化大小不超过 32 KiB；若投影后仍过大，保持合法 JSON 并省略 arguments / result，设置省略标记，不截断 JSON 字符串伪装成完整数据。

这两条接口不授予新写权限，不改变内部 worker 认证方式；复用既有读取时的生命周期维护行为，不另建观察后台任务。无需新增表、Flyway migration 或 Redis 数据结构。

## 4. 事件、刷新与错误处理

使用 fetch 读取 SSE，使每次连接都可以携带 tenant / actor。采用成熟 SSE parser 处理分段、多个 data 行与 keep-alive，避免假设一个网络分片就是一条事件。

事件按 `(runId, sequence)` 去重。断线后保留最后一个已处理序号，通过现有游标接口补齐；重新读取 run 快照、工具与审批记录。快照和观察详情代表读取时状态，不能描述为某个历史序号的原子快照。

收到 terminal 事件或流结束时再读取任务状态，确认终态后停止重连。网络流断开不等于业务失败，显示“连接中断，状态待确认”。自动重连最多连续五次，退避从一秒递增并封顶十秒，成功收到事件后重置计数；耗尽后保留手动重连按钮。

认证、范围拒绝或 ACCESS_REVOKED 不持续重试；清空受限详情并显示访问不可用。不能用重连逻辑重新发送创建、批准、拒绝或取消等写请求。

创建 run 使用同一次提交生成的 Idempotency-Key；结果不确定时允许用户重试同一份请求及同一 key。修改问题或文档集合即视为新提交，必须产生新 key。文件上传当前没有相同幂等保证，连接中断时提示先检查文档列表，不自动重复上传。

## 5. 前端组织与本地运行

采用 React + TypeScript + Vite，lucide 图标，原生 CSS；首版不引入 Redux、流程编辑器或大规模设计系统。依赖版本在实现时固定在 lockfile，不使用浮动版本作为交付依据。

建议目录：

```text
frontend/
  src/api/          # Header 上下文、HTTP、SSE 与接口类型
  src/run/          # 当前任务控制、事件去重与流程投影
  src/components/   # 文档面板、对话、流程、步骤详情与审批
  src/learning/     # 静态源码入口与职责说明
  src/test/         # 状态、组件与接口模拟测试
  e2e/             # 浏览器流程测试
```

默认本地端口 5173，绑定回环地址，仅将 `/api/v1` 转发到本地后端 8080。端口占用时明确选择其他端口并给出实际地址，不终止现有进程。不要代理 `/internal/agent-worker`，不要扩大全局 CORS。

API key 只保留在现有服务端环境配置中。前端不读取仓库 `.env`，不使用 `VITE_*` 注入模型 key，不在界面切换 worker 模式或自动启动 worker。run 返回的 executionMode / provider / model 用于展示实际执行配置。

浏览器仅在 sessionStorage 按 tenant / actor 保存少量最近 runId，最多 20 个，不保存问题、原始文件、上下文、工具结果或凭证。切换身份时中止旧 SSE 与请求，清空旧视图；异步响应必须携带身份代次，防止旧请求晚返回后污染新身份页面。存储被禁用时降级为当前页面内存。

## 6. 测试与验收

后端测试：工具列表及详情的 ownership、跨租户和跨 actor 拒绝、PRIVATE 范围复查、工具必须属于 run、分页输入校验、字段白名单、超大值省略、无敏感字段泄露；run GET 的附加字段不能绕过既有鉴权。

前端测试：事件顺序与重复、同名工具不同实例、SSE 分段解析、断线恢复与终态停止、审批冲突、取消待确认、切换身份时旧响应丢弃、错误提示与字符串安全渲染。真实状态未到达前不能显示处理完成。

浏览器测试：文档选择、问题提交、节点与详情联动、批准和拒绝动作、重新打开 run，以及桌面和手机布局。以接口模拟覆盖失败分支，另用真实本地后端与 scripted worker 完成一轮上传、CHUNK 审批、EMBED_MISSING 审批、报告检查；测试使用独立合成文档，不破坏当前演示数据。真实模型联调另行明确，默认验证不调用付费 API。

交付同时更新运行说明、接口说明、中文 review 说明与 `docs/learning/learning-workbench-01.md`，记录测试结果、占用端口与已知限制。应用代码、后端测试、前端测试、构建和浏览器验证均完成才算接入完成；静态草图不计作已实现功能。

## 7. 实现顺序与已知限制

实现顺序：先完成受限读取投影及后端测试，再接文档面板与任务提交，再接事件流程与人工审批，最后联调、补学习文档与中文 review。保持范围集中，不在过程中扩展新 agent 能力。

限制始终可见：Header 身份可伪造；worker 需单独运行；当前页面不是实时调试器；对话不是持久多轮记忆；工具观察不是模型思维链；源码映射需要随实现维护；scripted / deterministic 组件不能冒充真实模型；RAG 问答接入不属于本次交付。

本地草图存放在 Git 忽略的 `.superpowers/` 中，不作为正式前端入口发布。本文和后续实现均不自动执行 git add、commit 或 push。
