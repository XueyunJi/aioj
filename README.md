原项目作者：Mubai0628
当前推进迭代者：XueyunJi
维护起始时间：2026年8月

### 当前状态
- 代码已备份并迁移至本仓库
- AI 出题模块问题已识别，修复方案已规划
- 项目持续迭代中

### 相关文档
- [系统架构白皮书](docs/系统架构白皮书_v1.0.md)
- [AI出题问题分析](docs/AI出题问题分析.md)

# AIOJ

[中文](#中文) · [English](#english) · [中文设计文档](docs/zh/README.md) · [English design docs](docs/en/README.md)

## 中文

AIOJ 是面向校园教学的在线判题平台，提供题库练习、比赛运行、异步判题、受审计的 AI 辅助、AI 题目草稿与赛后复盘。学生端和管理端均使用 React；浏览器请求统一经过网关；判题任务通过 RabbitMQ 交给 judge-worker，再由 go-judge Sandbox 执行。

> [!CAUTION]
> 当前生产拓扑在同一主机上运行应用、数据库与 `privileged` Sandbox。这是相对独立判题节点的明确安全降级：若容器运行时或内核漏洞导致逃逸，可能获得宿主机 root 等价权限并影响全部服务与数据。隔离网络、资源限制、只读测试数据和秘密分离只能降低风险，不能消除风险。详见[安全架构](docs/zh/security-architecture.md)。

### 目录

```text
backend/                    Spring Boot / Maven 多模块后端
apps/web-user-react/        学生 React 应用
apps/web-admin-react/       教师与管理员 React 应用
packages/                   API 客户端、国际化与共享 UI
deploy/                     Dockerfile 与镜像式生产 Compose
scripts/                    CI、评测、压测与发布工具
docs/zh/                    中文设计文档
docs/en/                    English design documents
archive/                    本机私有资料，Git 忽略
```

### 构建与验证

```powershell
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:user:react
npm.cmd run build:admin:react
mvn -f backend/pom.xml test
git diff --check
```

本地秘密、端口、账户和数据库信息只能放在未跟踪的本机配置中，不得复制到服务器或提交到 Git。

### 发布

正式 SemVer GitHub Release 会触发 GitHub Actions：测试代码、构建八个私有 GHCR 镜像、生成 SBOM/构建证明与不可变 digest 清单，并在 `production` 环境等待人工批准。生产服务器只按 digest 部署镜像，不执行 `git pull`，也不使用 `latest`。数据库迁移是向前兼容的，不随镜像回滚自动回退。

公开文档只包含产品与技术设计；Agent 操作指令、实施计划、进度跟踪、生产运行手册和项目记忆不在公开仓库中。

## English

AIOJ is a campus-oriented online judge for practice, contest operations, asynchronous judging, audited AI assistance, AI problem drafts, and post-contest reviews. Both web applications use React. Browser traffic enters through the gateway, while judge jobs flow through RabbitMQ to judge-worker and then to the go-judge Sandbox.

> [!CAUTION]
> The selected production topology colocates the application, databases, and a `privileged` Sandbox on one host. This is an explicit security downgrade from an isolated judge node: a successful container-runtime or kernel escape could yield host-root-equivalent control over every service and dataset. Network isolation, resource limits, read-only testcase data, and secret separation reduce risk but cannot remove it. See the [security architecture](docs/en/security-architecture.md).

### Layout

```text
backend/                    Spring Boot / Maven multi-module backend
apps/web-user-react/        student React application
apps/web-admin-react/       teacher and administrator React application
packages/                   API client, i18n, and shared UI
deploy/                     Dockerfiles and image-only production Compose
scripts/                    CI, evaluation, load-test, and release tools
docs/zh/                    Chinese design documents
docs/en/                    English design documents
archive/                    local private material, ignored by Git
```

### Build and verification

```powershell
npm ci --include=optional
npm.cmd run typecheck:react
npm.cmd run test:auth
npm.cmd run build:user:react
npm.cmd run build:admin:react
mvn -f backend/pom.xml test
git diff --check
```

Local secrets, ports, accounts, and database details belong only in untracked workstation configuration. They must never be copied to a server or committed to Git.

### Release

A formal SemVer GitHub Release triggers GitHub Actions to test the code, build eight private GHCR images, produce SBOM/provenance evidence and an immutable digest manifest, and wait for approval in the protected `production` environment. Production deploys digests only: it does not run `git pull` or use `latest`. Database migrations are forward-only and are not automatically reversed by an image rollback.

The public documentation contains product and technical design only. Agent operating instructions, implementation plans, progress trackers, production runbooks, and project memory are intentionally excluded from the public repository.

## Design documentation

- [中文设计文档](docs/zh/README.md)
- [English design documentation](docs/en/README.md)

Licensed under the [Apache License 2.0](LICENSE). See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
