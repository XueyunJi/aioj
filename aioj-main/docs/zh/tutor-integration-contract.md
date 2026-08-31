# Tutor 集成契约 v1

本文档冻结 AIOJ 与 Tutor 的第一版推荐、提交和判题事件边界。所有接口均经 Gateway 调用，并使用当前 AIOJ 用户的 Bearer Token。

## 推荐

```text
GET /api/v1/tutor/recommendations?limit=10
```

`limit` 默认 10，范围为 1 到 50，超出范围按服务端限制处理。推荐只依据 Token 对应用户的练习提交历史生成，不接受 `userId`、`student_id` 或其他身份参数。

首版规则：

- 只返回 `PUBLIC`、未归档、未删除题目。
- 未提交过的题目优先。
- 提交过但尚未 `ACCEPTED` 的题目作为针对性复习候选。
- 已 `ACCEPTED` 的题目不重复推荐。
- 每项返回公开题目投影、规则分数和可展示推荐理由。
- 不返回源代码、隐藏测试、标准答案、测试包路径或管理字段。

空数组表示没有符合条件的候选；不得因此放宽题目可见性或用户权限。

实际实现规则：`Idempotency-Key` 按当前认证用户作用域保存。相同用户使用相同键重试同一题目、语言、源代码和竞赛上下文时，返回第一次提交，不会再次入判题队列；同一用户复用键但请求内容不同返回 `409 CONFLICT`。键会去除首尾空白，最长 96 个字符。未携带该请求头时保持旧客户端的非幂等兼容行为。

## 提交幂等和题目版本

当前提交接口的兼容请求体仍为 `problemId`、`language`、`code`。Tutor 在提交代理准备启用重试前，必须发送请求头：

```text
Idempotency-Key: <client-generated-key>
```

该键应由 Tutor 为一次用户提交动作生成，在网络重试中保持不变；不同提交动作必须使用不同键。AIOJ 后续实现按用户维度保存幂等关系，重复键且请求指纹一致时返回第一次提交结果，重复键但请求内容不同返回 `409 CONFLICT`。

题目版本使用 Tutor 题目响应中的 `version` 字段。版本校验正式启用前，Tutor 不得把版本缺失或过期解释为学习状态变化；题目更新后的新提交必须重新读取题目详情。

## 判题终态事件

AIOJ 向 Tutor 投递的事件采用 Outbox 语义，事件只在判题进入终态后产生：

```json
{
  "eventId": "evt-01...",
  "eventType": "SUBMISSION_JUDGED",
  "occurredAt": "2026-08-30T04:00:00Z",
  "userId": "123",
  "submissionId": "456",
  "problemId": "789",
  "problemVersion": "2026-08-29T01:23:45Z",
  "language": "cpp",
  "status": "ACCEPTED",
  "score": 100,
  "judgedAt": "2026-08-30T04:00:00Z"
}
```

`userId`、`submissionId`、`problemId` 以字符串传输，避免跨语言长整数精度丢失。事件不得包含源代码、标准答案、测试输入输出、测试包路径或管理字段。

投递要求：

- 使用服务间鉴权和请求签名；共享密钥只从部署密钥配置读取。
- 失败按退避策略重试，超过上限进入 DEAD 状态并可人工重放。
- Tutor 以 `eventId` 去重，以 `submissionId` 作为业务幂等边界。
- 允许重复和乱序投递；Tutor 不得因重复事件重复增加 revision。
- 只有终态事件进入学习状态同步，`QUEUED`、`RUNNING` 不产生学习证据。

`ACCEPTED` 是否等价于 Tutor 的“做对”，以及失败、重判、重复提交到学习状态的具体映射，仍需 Tutor 侧确认后冻结；在确认前 AIOJ 不要求 Tutor 自动写入学习状态。
