# 真实 RAG 第一步实施计划

范围：provider、模型空间一致性、受控重建、能力投影及测试。设计已获批准。本阶段不切换正在运行的配置，不调用付费 API，不替换用户数据，不接前端或 Pi 新工具。

- [x] 新增安全、有界的 WebClient OpenAI 客户端和 embedding/answer 适配器，用模拟 HTTP 测试。
- [x] embedding 状态与 SQL 按 provider/model/dimension 匹配；普通补向量拒绝模型覆盖。
- [x] 加入明确的 replaceExisting 入口、REEMBED job、生成在事务外和短事务原子提交，chunk 写入共享行锁。
- [x] 能力接口明确标记真实问答流水线仍待第二步；answer 适配器本阶段只作为可测试组件，不激活旧 query 流程。
- [x] 路由、租户、事务回滚、并发变更、模型匹配及 provider 边界测试；后端 230、worker 26 项通过。
- [x] 更新配置说明、README/API、中文学习笔记与 review，记录真实验证结果和 Netty 告警。

第二步才把已测试的 answer 适配器注册为 query 的生成器，并实现引用筛选、权限复查、缓存 bypass 和真实 SSE；在这些保障完成前不能只替换一个 bean 就宣称真实问答完成。

测试命令：`mvn -Dapi.version=1.44 test`、`npm --prefix workers/pi-worker test`、`git diff --check`。Docker 集成测试若跳过必须明确记录，不能计作通过。
