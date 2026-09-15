# GitHub Actions：测试与构建

工作流：`.github/workflows/ci.yml`。push、pull_request、workflow_dispatch 触发。
本项目目前是 **CI + 构建产物归档**，不是自动部署到生产环境的 CD。

## 两条任务

| 任务 | 内容 |
| --- | --- |
| backend-worker | Java 17、Node 22；worker npm ci/test；Docker；Maven clean verify；检查没有测试被跳过；脚本语法检查 |
| frontend | Node 22；npm ci/test/build；安装 Chromium；运行合成浏览器测试 |

先构建 worker，防止 Java 跨进程测试因为缺少 dist 而被跳过。后端测试通过 Testcontainers 创建临时 PgVector/Redis，不连接开发机数据库。常规 Maven 测试不自动包含手动 opt-in 的 WorkbenchBrowserIT；不能把这条 CI 声称为全系统真实模型验收。
本地 Playwright 默认 Chrome，CI 用其固定依赖版本对应的 Chromium，避免依赖 runner 是否预装 Chrome。

## 权限与数据

- `contents: read`，checkout 不保留凭据；不使用 `pull_request_target` 运行外部 PR 代码。
- 官方 actions 固定提交 SHA；npm 用 lockfile 安装，Maven 保持项目已有依赖版本。
- 不添加 OpenAI 密钥、不上传 `.env`，测试只用显式的虚假测试 token/模型响应。
- 并发同一 ref 的旧 CI 可取消；job 有超时。没有忽略失败的 continue-on-error。
- 归档测试报告、后端 JAR、前端 dist/浏览器测试截图，保留 7 天。不上传开发机 logs 或真实问答记录。
- `upload-artifact` 是构建交付，不发布镜像、不配置云凭据、不部署。

配置参考：[GitHub Maven CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)、[权限与触发器语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)。

## 本地对应命令

```bash
npm ci --prefix workers/pi-worker
npm test --prefix workers/pi-worker
mvn --batch-mode --no-transfer-progress -Dapi.version=1.44 clean verify
npm ci --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
npm run test:e2e --prefix frontend
git diff --check
```

`api.version=1.44` 用于当前 Testcontainers/Docker API 兼容，不是模型 API 版本。CI 开始先检查 Docker 可用；最后检查 JUnit XML 中 skipped=0，避免“没跑集成测试但绿色”。

## 失败排查

1. 在仓库 Actions 中打开对应 commit 的 NexusAgent CI，查看失败 step 和报告，不要只看旧运行结果。
2. npm ci 失败先检查 lockfile、Node engine 和公开 registry 依赖可用性，不随意删除 lockfile。
3. 数据库测试失败查看 Flyway 和 Testcontainers 输出；不要在 CI 接入个人数据库绕过问题。
4. 浏览器失败查看合成测试截图，不重复触发真实模型请求。
5. 推送 workflow 被拒绝通常是 Git 凭据缺少 workflow 权限；在本机完成 GitHub 登录授权，不把 token 发到聊天或写进仓库。

没有配置分支保护或强制合并门禁，创建 workflow 不会自动启用这些仓库策略。CI 不包含负载评测、生产部署、模型质量评测或生产安全认证。
