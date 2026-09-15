# 当前页面多轮追问：实现 Review

## 范围与行为

依据已批准的 [设计稿](../superpowers/specs/2026-09-14-page-follow-up-resolution-design.md)。本次只增加当前页面的有界追问解析，不修改 chunk/embedding 数据、不新增 Redis 会话记忆、不改变 Pi 审批或权限模型。

- 请求新增可选 history；最多最近 3 轮已完成且 answered 的问答，旧单轮调用保持兼容。
- preflight 后新增 `query_resolution`，先调用已配置真实模型解析，再进入精确/语义缓存。首问跳过解析。
- 原话继续展示；独立问题用于缓存、embedding、检索、重排及回答，debug 展示两者区别。
- 模糊指代返回 needs_clarification，全部检索/答案阶段跳过；历史不是证据，澄清没有假引用。
- 超时、拒绝、非法结果、取消均有明确行为；不自动重试收费请求，不回退模板。
- 页面历史不递归复制、不写浏览器持久存储；身份、视图、范围、文档集合变化、刷新、清空会清除历史并更新 sessionId。
- 外发确认覆盖有界历史和额外模型费用。现有缓存命中仍不等于免除解析/回答调用。

## 关键改动

后端新增 `ConversationTurn`、`QuestionResolver`、`QuestionResolution`、`OpenAiQuestionResolver`、`QueryResolutionDebug`。修改 QueryRequest/Response、live 编排与能力接口、QueryProperties 和配置；离线编排明确拒绝非空 history。沿用现有 HTTP transport、缓存与权限重查，不新增服务依赖或 Flyway migration。

前端增加 `history.ts` 选择纯函数；修改 useQuery、Workbench、请求协议验证、学习流程和详情面板。阶段显示来自实际事件，不展示模型隐藏推理。清空和切换仍使用原有取消/迟到响应隔离。

文档更新 README、API、架构、学习工作台、限制、配置样例与 [中文学习笔记](../learning/page-follow-up-resolution.md)。工作区同时保留之前未提交的语义缓存实现；不是此次追问功能新引入的全部改动。

## 验证证据

2026-09-15：

| 检查 | 结果 |
| --- | --- |
| `mvn -Dapi.version=1.44 clean verify` | 353 项通过，0 失败/错误/跳过，打包成功 |
| `npm --prefix frontend test` | 50 项通过 |
| `npm --prefix frontend run build` | 类型检查与 Vite 构建通过 |
| `npm --prefix frontend run test:e2e` | 16 项通过 |
| `npm --prefix workers/pi-worker test` | 26 项通过 |
| 桌面与移动截图 | 已检查，追问/澄清/详情无重叠或横向溢出 |
| `git diff --check` | 通过 |

重点测试：首问零解析调用；历史类型与长度限制；严格响应解析、拒绝、超时、取消；先权限后外发；解析结果用于最终 prompt；旧答案不进入 prompt；澄清没有下游执行或引用；SSE trace/顺序/debug 校验；独立问题相同精确命中、不同年份不复用；Redis 值无历史正文；连续追问保留主题；topK 改变保留历史；清空/切范围/刷新更新会话。

所有模型测试使用合成响应。没有执行真实付费请求，没有验证新 resolver 在当前账号/模型下的实际效果；需要后续单独授权小样本 live 验收。没有读取或修改 `.env`，没有修改业务文档/向量，没有提交或推送此次实现。

## 建议人工 Review

1. 从 `history.ts` 看为什么下一轮使用上一轮 resolvedQuestion，避免第三轮起主题再次丢失。
2. 从 `LiveQueryService.resolve` 看到 `retrieveAndAnswer`，核对解析在缓存前、身份/范围不由模型改写。
3. 检查 `OpenAiQuestionResolver` 的输入边界、状态组合与错误脱敏；schema 不是语义正确性证明。
4. 在页面测试完整问题后接短追问，点击“问题补全”查看独立问题；确认澄清时没有伪造耗时、检索或引用。
5. 切换范围/身份和刷新页面，确认历史不继承。保持现有访问检查，不能靠 sessionId 代表权限。

## 限制

仅三轮摘录，不能完整编辑/复述过去回答，澄清提示本身不进入后续历史。有历史时新增一次模型调用；取消不能回滚远端费用。历史可被用户篡改或包含错误，提示词无法保证抗注入或正确改写。只检查当前访问范围，不重建客户端旧答案的来源权限。语义缓存仍有误复用风险，缺少真实追问质量评估；不是持久聊天系统或生产认证。

安全面试表述：“在现有 RAG 缓存与检索之前增加了有界、多轮问题消解，并通过严格契约、澄清分支、访问重查和阶段观测控制失败；历史只用于指代解析，事实依据仍来自文档。”
