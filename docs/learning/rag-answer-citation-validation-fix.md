# 真实问答修复：模型引用契约与解析诊断

## 现象与证据

前端检索、RRF、重排、父块扩展和上下文构建完成，但 `answer_generation` 返回 `INVALID_MODEL_RESPONSE`。界面上的独立 `citation_validation` 尚未执行，因为回答适配器内部已经先做了一次引用校验。

旧实现把多种响应问题归为同一个错误，无法从原截图断言是 JSON、截断还是引用问题。增加不含正文的诊断后，用相同问题复现：

| 项目 | 结果 |
| --- | --- |
| 问题 | 苹果这几年发布会有什么亮眼的地方 |
| 范围 | library，topK=5，contextBudgetChars=4000 |
| 模型 | gpt-5.6-luna |
| 响应状态 | completed |
| 拒绝原因 | CITATION_SET_MISMATCH |
| 用量诊断 | outputTokens=488，reasoningTokens=67 |

这次复现证明：**模型已完成输出，正文引用与 `usedCitationMarkers` 声明集合不一致**。不是前端 JSON 解析故障，也不是本次请求的输出截断。未保存原始回答，因此不能声称旧回答具体用了哪种错误写法；组合引用只是本次补强的明确可处理情况之一。

## 修改内容

1. `OpenAiAnswerGenerator` 的响应 schema 将声明列表的每个元素限制为本次上下文真实存在的 marker，例如 `[C1]`，不再只是任意字符串。
2. 提示词要求正文使用独立标记 `[C1][C2]`，声明列表使用同样带括号的完整值，不声明未使用引用。
3. 对 `[C1, C2]`、`[C1，C2]` 这种可明确拆分、且所有成员都在证据列表中的正文格式，规范化为 `[C1][C2]`，再进行原来的严格集合比较。
4. 缺失声明、裸 `C1`、重复声明、未知引用和范围 `[C1-C2]` 不自动修复。不推断、不新增、不删除引用来凑成功。
5. 区分 `MODEL_OUTPUT_LIMIT`、`MODEL_RESPONSE_INCOMPLETE` 和带安全原因的 `INVALID_MODEL_RESPONSE`；部分回答一律不返回。
6. `LiveQueryService` 将请求 trace ID 放入 Reactor Context。日志只写受控原因、白名单状态、trace 和数值用量，不记录正文、证据、隐藏推理、密钥或原始上游错误。

本次未改回答模型、2000 输出 token 上限、检索算法或前端协议；没有自动重试付费回答，没有模板兜底，没有修改 `.env`、现有文档或向量。

## 测试与真实验证

可重复的本地自动化测试不需要真实 API key：

```bash
mvn -Dapi.version=1.44 -Dtest=OpenAiAdaptersTest,LiveQueryServiceTest test
mvn -Dapi.version=1.44 test
git diff --check
```

`api.version` 是本地 Docker/Testcontainers 兼容参数，不是 OpenAI API 版本。集成测试需要 Docker。

- 相关测试 55 项通过；全量后端 289 项通过，0 失败、0 错误、0 跳过。
- 新增 11 个测试执行项：已知组合引用规范化、未知/范围/畸形引用拒绝、声明列表约束、输出截断和错误信息不泄露正文；已有 schema 契约测试也补充 enum 断言。
- 用户明确允许最多两次真实问答，已使用两次，不会自动继续发送。每次问答可能包含问题 embedding 和回答调用，不代表仅两次 HTTP/API 请求。
- 第一次为上面的失败复现；第二次在修复后通过真实 Chrome 前端发送同一个问题，经过 Vite、Java、OpenAI、SSE，显示完整回答和 4 条引用，并可打开父块证据。只发送一次查询 POST，没有重试。
- 成功日志为 `answer_response_accepted` 和 `live_query_completed status=answered citationCount=4 cacheStatus=bypassed`。截图保存在被 git 忽略的 `logs/answer-fix-live-validation.png`。

两次测试使用当时可访问的合成资料。文档库仍可被用户正常修改，不是固定语料的离线评测；这只验证了一次失败复现和一次修复后真实链路成功，不是正确率、稳定性、性能或 Apple 产品事实评测。

## 面试答法

> I treat model output as untrusted input. The response schema restricts citation IDs to the evidence supplied for that request, and the server validates the declared references against the answer. I normalize only unambiguous formatting, not missing or fabricated evidence. Failures retain a trace ID and a safe diagnostic reason without logging source text. Citation consistency is a structural check, not proof that every claim is factually supported.

## 限制与排查

- Structured Outputs 不能替代正文与引用列表的业务一致性检查；模型未来仍可能返回需要拒绝的回答。
- 只兼容明确的逗号组合标记，不进行任意模型格式的宽松猜测。
- 输出上限仍为 2000，包含可见输出与 reasoning token；预算耗尽会明确报错，不偷偷扩大额度或再次调用。参见 [OpenAI Responses 参数说明](https://developers.openai.com/api/reference/cli/resources/responses/methods/create) 与 [Structured Outputs 指南](https://developers.openai.com/api/docs/guides/structured-outputs)。
- 没有新增事实性评测、检索评测集或 cross-encoder。真实请求通过不等于所有答案可信。
- 修改 Java 后需要重启后端，刷新前端不足以更新解析器。本次已重启 8080 后端；旧失败消息是页面历史，不会自动重新执行。
- 后续排查优先按 trace 找 `answer_response_rejected`，不要为了定位引用问题打开原始响应或密钥日志。重新发送问题必须视为新的可能付费请求。
