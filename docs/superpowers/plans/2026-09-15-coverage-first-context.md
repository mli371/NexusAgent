# Child 优先覆盖实施计划

依据用户已批准的 coverage-first-context 设计。当前环境没有 writing-plans skill，采用项目实施清单。保留此前未提交的缓存与多轮改动。

- [x] 阅读现有上下文、引用、缓存、阶段事件及前端契约。
- [x] 实现完整 child 预留、均衡 parent 扩展和有界诊断。
- [x] 更新实际阶段、引用完整性与缓存版本。
- [x] 前端增加 child 阶段、覆盖与排除提示。
- [x] 增加分配、缓存、REST/SSE 和浏览器回归测试。
- [x] 完整验证、中文学习/review 文档、重启本地服务。

验证：backend 366、frontend unit 57、browser 18、worker 26 全部通过；补充语义缓存阶段断言定向 6 项通过。前端构建与 diff 检查通过。后端 8080 健康、前端 5173 可访问；仅检查健康/配置，没有发付费问答。详情见中文 review。

不修改预算、文档/向量或 .env，不提交/push，不调用付费 API。
