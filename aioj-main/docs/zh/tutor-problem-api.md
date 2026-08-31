# Tutor 题目接口契约

## 边界

这些接口由 AIOJ 提供，Tutor 通过 Gateway 携带当前用户的 AIOJ Bearer Token 调用。接口只返回未归档、未删除且 `visibility=PUBLIC` 的题目；PRIVATE 题目不会因为调用方角色或参数而返回。

AIOJ 的 `tags` 是 AIOJ 原生题目标签，不代表 Tutor 知识点。两套知识点体系暂时独立，不在接口中建立映射。

## 基础信息

- Gateway：`http://127.0.0.1:8101`
- 认证：`Authorization: Bearer <access_token>`
- 成功响应：AIOJ 标准 `ApiResponse`，业务数据位于 `data`
- 未认证：HTTP `401`
- 题目不存在、已归档、已删除或非 PUBLIC：HTTP `404`

## Capabilities

```text
GET /api/v1/tutor/capabilities
```

响应 `data` 包含：

- `apiVersion`: `v1`
- `visibility`: `PUBLIC`
- `tagSource`: `AIOJ_PROBLEM_TAGS`
- `operations`: `search`, `detail`, `recommendations`
- `sortableFields`: `NEWEST`, `OLDEST`, `DIFFICULTY_ASC`, `DIFFICULTY_DESC`
- `difficultyValues`: `EASY`, `MEDIUM`, `HARD`, `CHALLENGE`

## Search

```text
GET /api/v1/tutor/problems?page=1&pageSize=20&keyword=binary&difficulty=MEDIUM&tag=二分&sort=NEWEST
```

所有参数可选：`page` 从 1 开始，`pageSize` 使用服务端上限；`keyword` 匹配题目标题或题目 ID，`difficulty` 和 `tag` 使用 AIOJ 题目字段，`sort` 默认为 `NEWEST`。

响应 `data` 使用 `records`, `total`, `page`, `pageSize` 分页结构。每条记录包含：

```json
{
  "problemId": 123,
  "version": "2026-08-29T01:23:45Z",
  "updatedAt": "2026-08-29T01:23:45Z",
  "solveUrl": "http://localhost:5175/problems/123",
  "title": "...",
  "difficulty": "MEDIUM",
  "statement": "...",
  "notes": "...",
  "tags": ["..."],
  "samples": [],
  "timeLimitMillis": 1000,
  "memoryLimitKb": 262144
}
```

`version` 当前取题目的 `updatedAt` 字符串。题目更新时该值变化，Tutor 可据此实现缓存失效和增量同步。

`solveUrl` 是对应的 AIOJ 用户端作答地址，格式为 `${AIOJ_USER_BASE_URL}/problems/{problemId}`，不包含 Token。打开后使用当前 AIOJ 会话；若未登录，用户需要重新登录。

## Detail

```text
GET /api/v1/tutor/problems/{problemId}
```

返回与 Search 记录相同的题目投影。不会返回标准答案、隐藏测试、测试包或管理字段。

## 推荐边界

推荐功能归 AIOJ 负责，不属于 Tutor 题目同步接口。AIOJ 后续应基于当前登录学生自己的提交记录、判题结果和相关学习信号生成推荐；Tutor 只消费 AIOJ 已公开的题目和推荐结果，不直接读取或计算 AIOJ 学生的判题数据。

推荐结果中的题目仍必须遵守本契约的可见性规则：只能引用未归档、未删除且 `visibility=PUBLIC` 的题目。Tutor 知识点体系与 AIOJ 题目 `tags` 暂时不建立映射。

## 当前未包含

## Recommendations

```text
GET /api/v1/tutor/recommendations?limit=10
```

接口只根据当前 Bearer Token 对应用户的练习提交记录生成推荐，不接受 `userId` 或 `student_id`。`limit` 默认为 10，服务端限制为 50。

响应 `data` 是数组，每项包含 `problem`（与本页 Detail 相同的公开题目投影）、`score` 和 `reason`。规则型首版优先推荐未提交过的公开题目，其次推荐提交过但尚未通过的题目；已通过题目不重复推荐。推荐结果始终过滤未归档、未删除且 `visibility=PUBLIC` 的题目。

推荐服务不可用时返回标准依赖错误；空数组表示当前没有符合条件的推荐，不代表权限放宽。

推荐接口暂不负责 Tutor 知识点映射，也不写入学习状态。判题 Outbox 事件将在单独契约中定义。
