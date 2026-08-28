# 系统架构

[English](../en/system-architecture.md)

## 总览

```text
浏览器 -> web-user / web-admin -> gateway
                                   |-> auth-service -> MySQL
                                   |-> problem-service -> MySQL / Redis / RabbitMQ
                                   `-> ai-service -> 兼容 Provider

RabbitMQ -> judge-worker -> go-judge Sandbox
```

浏览器只访问网关公开的 `/api/v1/**`。服务间契约和 Flyway 迁移集中在 `backend/api-contract`，共享响应、错误、追踪和安全过滤集中在 `backend/common-lib`。

## 组件

| 组件 | 职责 |
| --- | --- |
| gateway-service | 唯一浏览器 API 入口、路由与跨域边界 |
| auth-service | 登录、JWT/刷新令牌、用户、角色、用户组与 Flyway |
| problem-service | 题目、测试包、比赛、提交、通知和异步任务 |
| judge-worker | 消费 RabbitMQ 判题消息并调用 Sandbox，不直接执行代码 |
| ai-service | Agent 运行时、对话、记忆、题目草稿、查重与复盘 |
| web-user / web-admin | 学生与管理 React 单页应用 |
| MySQL / Redis / RabbitMQ | 权威业务数据、缓存/短期状态与判题队列 |
| go-judge Sandbox | 隔离执行不可信代码的特权运行时 |

## 主要数据流

### 认证

浏览器经网关向 auth-service 登录。密码只在登录时校验，成功后换取短期访问令牌与受控刷新令牌。其他服务使用统一安全过滤校验身份与角色。

### 判题

problem-service 持久化提交并向 RabbitMQ 发布判题任务。judge-worker 消费任务、读取授权测试包引用并调用 Sandbox。结果回写 problem-service 所属数据，不通过浏览器直连 judge-worker 或 Sandbox。

### AI

ai-service 在服务器端计算权限与策略，构建最小上下文，调用 Provider 和受权工具，并持久化用量与审计证据。AI 访问 problem-service 使用最小跨服务契约，不直接读取其他服务的数据库表。

### 通知

持久化通知记录是事实来源；SSE 只负责实时唤醒。断线或刷新后，客户端通过 REST 补偿并恢复未读状态。

## 信任边界

- 浏览器输入、客户端比赛上下文和模型输出均不可信。
- 授权、比赛可见性、工具许可和数据裁剪在服务端执行。
- 前端 16 位以上实体 ID 始终保留为字符串。
- 隐藏测试、参与者源码、Provider 密钥与完整评测输出不能跨越未授权 API。
- AI 输出和工具调用不能获得高于当前用户与场景策略的权限。

## 持久化

- MySQL 保存权威业务数据和 Flyway 历史。
- Redis 保存可重建的缓存与短期状态。
- RabbitMQ 保存异步判题队列与受控重试状态。
- 测试包、运维产物、AI 草稿产物和判题缓存使用文件/卷存储；大体积隐藏测试不进入 MySQL。

## 生产拓扑

当前生产把应用、数据服务、judge-worker 与 Sandbox 合并在单节点。应用网络承载业务服务，内部 judge 网络只连接 judge-worker 与 Sandbox。Sandbox 不公开端口、不使用 host network、不挂载 Docker socket 或业务目录，也不接收数据库、JWT、AI 与部署秘密。

Sandbox 仍是 `privileged` 容器，成功逃逸可能危及整台主机与全部数据。这一风险不能被网络和资源控制完全消除，详见[安全架构](security-architecture.md)。
