# Contributing / 参与贡献

[中文](#中文) · [English](#english)

## 中文

提交修改前，请先检查真实 Git 状态并保留无关改动。变更应范围清晰、尽量小，并说明产品影响、安全与隐私影响、数据库迁移、验证证据及回滚边界。

核心工程规则：

- 浏览器 API 统一经过 `/api/v1/**` 网关。
- 前端实体 ID 使用字符串，不能把 16 位以上 ID 转为 JavaScript `number`。
- 跨服务契约与 Flyway 迁移位于 `backend/api-contract`；历史迁移不可修改。
- 判题必须经过 RabbitMQ 和外部 Sandbox，judge-worker 不在进程内执行用户代码。
- AI 题目遵循“草稿 → 审核/批准 → 导入”，AI 查重与复盘结论保持建议性与可审计性。
- 不得提交凭据、`.env`、生产数据、隐藏测试、参与者源码、完整评测输出、构建产物或本地归档。

建议验证：

```powershell
mvn -f backend/pom.xml test
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:react
git diff --check
node scripts/ci/check-markdown-links.mjs
```

## English

Before changing code, inspect the real Git state and preserve unrelated work. Keep the scope explicit and minimal, and document product impact, security/privacy impact, database migrations, verification evidence, and rollback boundaries.

Core engineering rules:

- Browser APIs go through the `/api/v1/**` gateway.
- Frontend entity IDs remain strings; never coerce 16+ digit IDs to JavaScript `number`.
- Cross-service contracts and Flyway migrations live in `backend/api-contract`; historical migrations are immutable.
- Judging goes through RabbitMQ and the external Sandbox; judge-worker never executes user code in-process.
- AI problems follow draft → review/approve → import, while plagiarism and postmortem conclusions remain advisory and auditable.
- Never commit credentials, `.env`, production data, hidden tests, participant source, complete judge output, generated artifacts, or local archives.

Recommended verification:

```powershell
mvn -f backend/pom.xml test
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:react
git diff --check
node scripts/ci/check-markdown-links.mjs
```
