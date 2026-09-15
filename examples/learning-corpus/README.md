# 学习与人工审批测试语料

新增八份可上传的 UTF-8 Markdown 文档，配合已有的 [2026 年演示文档](../apple-fall-event-2026-demo.md) 使用。历史摘要依据各文件内的 Apple 官方来源，虚构计划与规则均显式标注。所有金额、设备数量、组织和内部规则都是合成数据。

## 可上传文件

| 文件 | 主要用途 |
| --- | --- |
| [2022 年重点摘要](events/apple-fall-2022.md) | 年份区分、发布与到货日期、缺失执行记录 |
| [2023 年重点摘要](events/apple-fall-2023.md) | 型号限定、父段落上下文、不能泛化到整个系列 |
| [2024 年重点摘要](events/apple-fall-2024.md) | 功能介绍与实际验收的区别、业务状态区分 |
| [2025 年重点摘要](events/apple-fall-2025.md) | 历史采购规则、已申请不等于已批准 |
| [采购标准 v1（归档）](standards/procurement-v1-archived.md) | 旧版本、10,000 美元、八个工作日 |
| [采购标准 v2（当前）](standards/procurement-v2-current.md) | 生效日期、15,000 美元、十个工作日、三项确认 |
| [数据处理标准](standards/data-handling-standard.md) | PRIVATE / TENANT、合成隔离标记、敏感数据边界 |
| [设备验收标准](standards/device-acceptance-standard.md) | 明确规则、例外、阻塞项、证据不足 |

“当前”只指本虚构语料在 2026-09-11 的设定，不是现实公司的制度。年度文件是重点摘要，不是发布会完整逐字记录。不要把模拟标准包装成 ISO、NIST、法规或真实企业政策。

## 推荐测试顺序

1. 先上传 2023 年摘要，保留 STORED 状态，作为 agent 诊断和 CHUNK 审批的独立输入。
2. 通过实际审批 API 批准 CHUNK，确认真实状态，再处理 EMBED_MISSING 的下一次审批。模型提议不等于已经获批。
3. 完成后查看 chunks、embedding-status、ingestion-jobs 和 run actionResults，按实际返回核对，不预设 chunk 数量或固定 UUID。
4. 再上传其他年度与标准文档，准备年份比较、版本冲突和引用检查。新上传的每份文件有自己的 documentId；已经健康的文件通常不会再次需要相同的缺失处理。
5. 在独立测试租户执行 PRIVATE 场景，避免与已有 default / anonymous 演示数据混在一起。

可使用 [学习工作台](../../docs/learning-workbench.md) 上传并逐步观察，或使用 [Agent Harness 操作说明](../../docs/agent-harness.md) 中的命令。本语料包不自动上传文件、不启动 worker、不批准操作、不调用付费模型。

## 身份与隔离建议

普通样本可上传为 tenant `demo-lab-a`、actor `demo-reader-a`、visibility `TENANT`。数据处理标准可单独上传为相同身份的 `PRIVATE` 文件，其中包含无实际秘密含义的 `CANARY-PRIVATE-ORCHID-27`。

切换为同租户 `demo-reader-b` 或其他租户 `demo-lab-b` 后，检查文档读取、检索、上下文、引用和缓存是否保持范围隔离。若想让其他租户拥有同一公开摘要，应使用其身份另行上传。正文中的组织名或 PRIVATE 字样不会设置权限。

这些身份可被客户端伪造，适用于本地演练，不是登录、OAuth/JWT 或生产租户安全保证。审批也必须使用 run 所属的正确 tenant / actor，而不是随意更换 header。

## 测试题与预期证据

单独的 [人工核对题目](review-questions.md) 包含问题、证据位置和判断要点。只上传 `events/` 和 `standards/` 内的八份文件；不要递归上传本目录的 README 或答案表，避免把测试答案当成知识来源。

跨文档引用题需要相关文档都完成处理。先用指定 `documentIds` 验证证据，再测试不限定文档的混合检索。文档版本只是正文内容：当前检索系统不会自动执行“最新版本优先”的权限或规则引擎。

## 已知限制

- 本地确定性 embedding 不是生产语义模型；中文语料配合英文 full-text 配置的召回能力也有限。英文产品名、规则编号和精确关键词可用于基础检查，不能保证复杂中文同义问句命中。
- `/api/v1/query` 的本地模板答案不等于真实 LLM 推理；配置 pi worker 的模型 API 不会自动替换原来的 query 答案生成器。
- Harness 的文档处理诊断与知识问答是不同流程。CHUNK / EMBED_MISSING 的审批不是文档中虚构采购的审批。
- 单份 run 完成或 HEALTHY 不代表检索质量合格。这里提供人工验证素材，不声称完成了自动化评测或获得任何质量指标。
- 没有 PDF / Word 文件，因为当前抽取范围是纯文本和 Markdown。这批样本不是极大文件、恶意上传或系统压力测试集。
