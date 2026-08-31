# Tutor 作答闭环接口契约

## 当前阶段

题目查询和详情接口已经提供。AIOJ 用户端作答地址已通过 Tutor 题目响应的 `solveUrl` 提供，格式为：

```text
${AIOJ_USER_BASE_URL}/problems/{problemId}
```

本地默认值为 `http://localhost:5175`，云端必须配置为用户实际访问 AIOJ 的地址。`solveUrl` 不包含 access token。Tutor 应在新标签页打开该地址，由用户端自己的登录态完成作答。

## 身份要求

所有接口都通过 AIOJ Gateway 使用当前用户的 Bearer Token。Tutor 不得接受前端传入的任意 `userId` 或 `student_id` 作为身份依据。

```text
Authorization: Bearer <access_token>
```

不得将 Token 放入 URL、日志或接口响应。

## 提交代码

AIOJ 已有提交接口，Tutor 后续可复用：

```text
POST http://127.0.0.1:8101/api/v1/submissions
Authorization: Bearer <access_token>
Content-Type: application/json
```

请求体：

```json
{
  "problemId": "123",
  "language": "cpp",
  "code": "#include <iostream>\\n..."
}
```

`problemId` 接受数字或数字字符串，服务端按 Long 解析。支持语言为 `cpp`、`python`、`java`。实践题不能提交 PRIVATE、已归档或已删除题目。

提交成功返回 `data` 中的提交记录，包含 `id`、`problemId`、`status`、`judgeMessage`、创建时间等字段。初始状态通常为 `QUEUED`。

## 当前用户提交记录

```text
GET http://127.0.0.1:8101/api/v1/submissions?mine=true&page=1&pageSize=20&problemId=123
Authorization: Bearer <access_token>
```

服务端始终以 Token 对应用户为查询边界。即使调用方传入其他 `userId`，普通用户也不能读取其他用户记录。

响应为 AIOJ 标准分页结构，使用 `data.records`、`data.total`、`data.page` 和 `data.pageSize`。

## 提交结果

```text
GET http://127.0.0.1:8101/api/v1/submissions/{submissionId}
Authorization: Bearer <access_token>
```

结果状态包括：`QUEUED`、`RUNNING`、`ACCEPTED`、`WRONG_ANSWER`、`COMPILE_ERROR`、`RUNTIME_ERROR`、`TIME_LIMIT_EXCEEDED`、`MEMORY_LIMIT_EXCEEDED`、`OUTPUT_LIMIT_EXCEEDED` 和 `SYSTEM_ERROR`。

Tutor 可以轮询提交详情，或以后接入 AIOJ 判题事件。当前已有提交详情和分页查询接口，但尚未冻结专门的 Tutor Outbox/Webhook 契约。

## 当前明确缺口

以下内容需要 AIOJ 与 Tutor 联合定稿后再实现：

1. 提交请求幂等键及重复提交返回规则。
2. 题目版本字段在提交时的校验规则。
3. 判题完成 Outbox/Webhook 事件格式、签名、重试和去重规则。
4. Tutor 学习状态中 `ACCEPTED`、失败、重判和重复提交的映射规则。

在这些规则冻结前，Tutor 不应自行写入 AIOJ 数据库，也不应根据页面轮询结果猜测学习状态事件。

## 安全和可见性

- Tutor 只能展示 AIOJ `PUBLIC`、未归档、未删除的题目。
- 作答地址只允许指向符合上述条件的题目。
- 不向 Tutor 返回标准答案、隐藏测试、测试包路径或管理字段。
- 提交详情只允许当前用户读取自己的实践提交。
- Tutor 知识点体系与 AIOJ `tags` 暂不合并。
