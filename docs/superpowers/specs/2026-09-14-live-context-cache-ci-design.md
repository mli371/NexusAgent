# 真实问答上下文缓存与 CI

## 已批准范围

只缓存检索与上下文结果，命中仍调用真实回答模型；不缓存最终答案。
GitHub Actions 覆盖 Java 17 后端、Node 22 worker、前端测试与构建，不部署、不读取真实模型密钥。
用户授权一起提交依赖的既有工作并推送，私人笔记、环境文件、日志及构建产物排除。

## 缓存契约

- 独立命名空间 `retrieval:live-context-v1:{sha256}:context`，不读取旧演示缓存。
- key 由结构化 JSON 描述生成：tenant、actor、精确的已 trim 问题、scope、排序后的文档 ID/版本、topK、预算、embedding 标识、RRF 参数、检索/上下文算法版本。保留问题大小写及内部空白，不误合并可能不同的 embedding 输入。
- PostgreSQL `documents.retrieval_revision` 是版本依据。分块、向量及授权相关元数据变化通过触发器在同一事务更新版本，包括同模型重建向量。不是 Redis 自行维护版本，也不依赖时间戳比较。
- 查询权限/就绪预检后读取文档版本快照；命中后复查证据与版本。重建前后、模型返回后也检查版本。库范围在请求开始解析，新加入的就绪文档改变后续请求 key。
- Redis JSON envelope 包含 schemaVersion、cacheKey、cachedAt 和有界 ContextBuildResult。问题字段留空并从本次请求还原；不缓存 trace、会话 ID、模型答案、隐藏推理或原始上传文件。包含的 parent 证据本身仍是需要保护的数据。
- 默认 TTL 15 分钟、最大 128 KiB、Redis 单次等待 500ms，可配置。读取不续期；空上下文、超限结果不缓存。
- Redis 故障/超时/反序列化错误视为 miss；写失败忽略。失效证据重建，但权限拒绝与数据库故障不伪装成缓存故障。
- 不引入分布式锁或答案重试；并发 miss 可以重复构建。TTL 回收不可达旧版本，不承担正确性保证。

## 前端与验证

cache_lookup 显示 hit/miss/bypassed 和原因，命中后被省略的七个检索阶段均显示缓存复用/未执行；候选数据标记来自缓存。仍保留全程新 trace、真实生成及引用校验事件。

验证覆盖 key 隔离/参数、TTL/损坏/超限/Redis 停机、首次 miss 再次 hit、重分块/同模型重建/权限修改/新增就绪文档、回滚与并发版本变化、最终权限复查、debug 隐藏及 SSE。模型测试均用模拟 transport，不新增付费请求。

## CI 边界

push、pull_request、手动触发；只读 contents 权限，不使用 pull_request_target。
Ubuntu runner + Java 17 + Node 22，锁定依赖安装；先构建 worker 再测试 Java，防止跨进程测试被跳过。
后端使用隔离的 PgVector/Redis Testcontainers；前端 Chromium 测试使用合成 HTTP。
归档测试报告与构建产物，设置保留时间。任何失败都不是可忽略的绿色状态；不自动部署到个人机器或云。

## 实施顺序

1. 版本 migration、快照/key、Redis adapter 与真实问答集成。
2. 前端缓存状态和协议兼容；后端/前端新增测试。
3. GitHub Actions、中文学习/review 文档、配置与当前文档更新。
4. 本地完整检查、敏感内容检查、提交推送并查看远端 CI。

## 已知取舍

行级触发器会产生版本更新写放大，适合当前有界学习项目，不是批量导入优化方案。
授权是 header 演示身份，不是生产认证；数据库复查不保证跨 LLM 网络调用的可串行化授权隔离。
缓存不能证明答案事实性，不缓存最终答案意味着命中仍产生回答用量。
