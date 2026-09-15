# Agent Harness 第三步验收记录

日期：2026-09-11。范围：恢复、取消协调、可重放 SSE。

## 实际执行结果

| 检查 | 结果 |
| --- | --- |
| `npm --prefix workers/pi-worker test` | 26 个测试通过，0 失败、0 跳过；包含 TypeScript 编译 |
| `mvn -Dapi.version=1.44 test` | 188 个测试通过，0 失败、0 错误、0 跳过 |
| `bash -n scripts/agent-demo.sh` | 通过 |
| `bash -n scripts/agent-events.sh` | 通过 |
| `make -n agent-worker agent-run-demo agent-run-events DOC_ID=... RUN_ID=... AFTER=5` | 命令展开正确；这是 dry run，不是运行服务 |
| `git diff --check` | 通过；本次没有 stage/commit/push |
| 忽略项检查 | `.env`、`target/`、worker `node_modules/` 与 `dist/` 均被忽略 |

环境：本机 JDK 21 执行 Maven，编译目标 Java 17；Docker 29；PostgreSQL/PgVector 与
Redis 的既有 integration tests 使用 Testcontainers。全量测试日志报告 BUILD SUCCESS。
新增 migration V9 通过测试应用的 Flyway 启动流程应用，不改 V7/V8 的历史内容。

最初尝试 `mvn ... clean test` 时，Maven 卡在旧 `target/` 清理阶段，尚未开始测试。
线程栈定位到 clean 插件的目录读取；目录中存在 `classes 5`、`surefire-reports 4` 等
异常旧构建目录。已核实并停止该次测试进程，没有擅自删除这些目录。
最终使用 `mvn ... test`，日志确认重新编译全部主/测试源码后跑完全套。
**不能把本记录说成 clean test 通过**；旧目录清理是仍需处理的本机环境问题。

## 新增覆盖

Java 本阶段增加 20 个测试，并更新旧阶段的租约丢失断言。重点覆盖：

- 同 runId/traceId 恢复，新 attempt/claim 隔离旧 worker；累计工具和模型预算保留。
- 三次恢复上限、工具预算不足、active deadline、访问撤销。
- 真实 Node scripted worker 在诊断中被终止，新 worker 接管同一个 run 并完成。
- 取消排队、运行、等待审批、已批准但未派发的任务；重复取消幂等。
- 取消与批准竞争不会派发未批准/已取消的写操作。
- 在途业务 I/O 收到取消时仍能读到已提交 job，先返回 pending，确认后保留真实写入结果。
- 关联 job 未完成时停在 RECOVERY_REQUIRED；不误用另一个成功 job；不盲目重派发。
- 精确 job 成功但当前业务状态不匹配时保持待核对；已知失败可继续诊断或结束待处理取消。
- 已完成写入后的 worker 丢失不产生新 job/新 chunk；PENDING 的原 approval 可以首次派发。
- 未知 execution 不保存一个误导性的 finished_at。
- SSE 跨 100 条分页回放、实时衔接、断线重连、游标校验、trace 一致、权限撤销。
- SSE 初始存储错误为 503；打开后的错误为安全的无 id error 事件；keep-alive 不推进游标。

worker 本阶段增加 4 个测试：受控停止不覆盖持久状态、取消/旧 claim 不误报失败、
正常停进程交给租约机制恢复、continuation 中已完成的动作不重新派发。

## 测试边界与未做事项

- Java/worker 跨进程测试确实经过 HTTP 和真实测试 PostgreSQL，不是只 mock service。
- CHUNK 测试的 extraction 返回合成文本 fixture；不声称本阶段验证了真实 MinIO/PDF 流程。
- 未发送真实模型请求、未使用收费 API；离线 Pi SDK 测试不等于真实模型恢复验收。
- 没有整台主机断电、生产网络分区、真实提供商取消计费或多机压力测试。
- 没有对用户已有文档执行取消/重处理，没有修改本地 `.env`，没有提交或推送。
- 前端、登录与新的产品功能未实现。实现说明和保留限制见 [中文 Review](../review/agent-harness-phase-3.md)。
