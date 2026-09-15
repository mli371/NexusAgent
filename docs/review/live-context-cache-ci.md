# Review：真实上下文缓存与 GitHub Actions

## 改动

1. 真实问答使用独立 Redis namespace，带 tenant/actor、数据库文档版本和检索参数。
2. V10 触发器使分块、向量、授权元数据变化与版本更新同事务，含同模型重建与回滚。
3. 缓存值有 TTL/大小/版本校验；命中仍检查证据与权限，仍调用回答模型。
4. 前端接受 hit/miss/bypassed；复用阶段明确未执行，调试候选标注缓存来源。
5. CI 分后端/worker 与前端两条任务，自动测试、构建、归档；不自动部署、不使用真实模型密钥。

## 本地验证

| 检查 | 结果 |
| --- | --- |
| Maven clean verify | 307 项通过，0 失败，0 跳过；生成可执行 JAR |
| worker npm test | 26 项通过 |
| 前端 npm test | 40 项通过 |
| 前端 build | TypeScript 与 Vite 构建通过 |
| Playwright | 13 项通过；缓存命中、真实事件结构、桌面/手机布局 |
| 脚本语法、make -n demo、git diff --check | 通过 |

模型为测试 transport；PostgreSQL/PgVector 与 Redis 使用真实隔离容器，含主动停止测试 Redis 后问答继续成功。浏览器用合成 HTTP。未新增真实付费问答，也未迁移既有文档向量。
第一次本地 clean 卡在旧 target 目录的文件系统读取，已终止该次构建，将旧产物保留在忽略的 logs 目录；随后干净构建完整通过。既有 Netty/macOS DNS 和测试容器关闭告警不属于本次修复。

首轮 GitHub Actions 前端通过，后端暴露 Java 17 长段落正则栈溢出。已简化 DOTALL 下的多余正则分支，保留原分段语义；新增长段落全局偏移/父子关系回归测试和单换行/空行边界测试。回归测试修复前失败、修复后在 `-Xss256k` 下通过，随后本地完整 307 项通过。远端重新运行，不通过跳过测试或增大线程栈掩盖失败。

## 保留边界

- 缓存命中仍有数据库检查和回答模型用量，不缓存答案。
- PostgreSQL 版本是逻辑失效；旧 Redis 值等 TTL 回收，不是即时敏感副本删除。
- Header 身份不是认证，不承诺跨远程模型调用的强一致授权。
- 行级版本触发器有写放大，并发 miss 可能重复构建。
- 没有添加 OAuth、分布式锁、生产部署或性能指标。

运行配置、排查和面试答法见 [学习笔记](../learning/live-context-cache-ci.md)；CI 权限和测试范围见 [CI 说明](../ci.md)。远端结果以该提交在 GitHub Actions 的实际运行结论为准，不将本地通过冒充远端通过。
