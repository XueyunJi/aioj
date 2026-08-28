# AI-OJ 平台 Embedding 语义检索能力调研与部署报告

> 生成时间：2026-08-19
> 基于服务器：`VM-0-7-ubuntu`
> 项目路径：`/opt/ai-oj-next-react/app/`
> 部署路径：`/opt/aioj/`

---

## 一、新的发现

### 1.1 项目背景：AI 辅助开发的交接项目

- 原开发者使用 **GitHub Copilot / Codex / Claude** 作为编程助手进行开发。
- 项目根目录存在 `HANDOVER.md`，是写给**下一个 AI 会话**的交接文档，而非传统人员交接。
- 开发者于 **2026-08-09** 完成了核心语义检索引擎（`AiRetrievalService.java`），10 天后（当前）项目处于交接状态。
- 代码质量高（完整的降级保护、安全脱敏、混合评分策略），不是临时草稿。

### 1.2 部署架构：Docker Compose 多环境变量

- 生产部署使用 `compose.production.yml`。
- 环境变量分散在两个文件：
  - `env/app.env`：运行时配置（密码、API Key、功能开关）
  - `deploy.env`：镜像地址（GHCR 预构建镜像）
- 重启时必须同时加载两个 env 文件：
  ```bash
  docker compose -f compose.production.yml --env-file env/app.env --env-file deploy.env up -d
  ```

### 1.3 后端语义检索引擎已完整，但未暴露接口

- `backend/ai-service/src/main/java/.../domain/AiRetrievalService.java` 是一个**近 600 行、高度工程化**的检索引擎：
  - 支持余弦相似度语义搜索
  - 混合评分：语义相似度 + 关键词匹配 + 时效性衰减 + 来源优先级
  - 容量保护：Embedding 超限时自动降级为纯关键词搜索
  - 安全脱敏：自动过滤代码块、API Key、密码、Token
- **但没有 REST Controller 暴露给前端**，目前仅在 AI 辅导内部链路中被调用。

### 1.4 向量数据库已有 1477 条数据，但缺少题目向量

表 `ai_retrieval_chunks` 中的数据分布：

| owner_type | 数量 | 说明 |
|-----------|------|------|
| `submission_analysis` | 662 | 学生提交代码后 AI 生成的代码分析点评 |
| `profile_evidence` | 400 | 学生学习行为证据片段 |
| `conversation_summary` | 184 | AI 与学生对话的摘要 |
| `message` | 91 | 单条聊天消息向量化 |
| `learning_profile` | 83 | 学生学习画像 |
| `code_snapshot` | 36 | 代码快照 |
| `TURN_DIGEST` | 19 | 对话轮次结构化摘要 |
| `memory` | 2 | 长期记忆条目 |
| **`problem`** | **0** | **题目本身未被索引** |

**结论**：AI 辅导的 RAG（检索增强生成）链路已在后台运行，但**题目库完全没有被向量化**，因此无法提供"语义搜题"功能。

### 1.5 前端状态：React 已部署，搜索仍是传统关键词

- 学生端和管理端前端已迁移到 **React 19 + TypeScript + Vite**（`apps/web-user-react/`、`apps/web-admin-react/`）。
- 题目列表页（`ProblemsView.tsx`）的搜索框使用传统的 `api.problems({ keyword })`，基于 SQL `LIKE` 匹配。
- `packages/api-client/src/index.ts` 中**没有语义搜索的 API 定义**。

### 1.6 比赛系统规划（Phase 10-15）

从 `docs/ROADMAP.md` 和 `HANDOVER.md` 可知：

| Phase | 功能 | 状态 |
|-------|------|------|
| Phase 10 | 学生个人赛后分析 + 个性化学习闭环 | ✅ 已验证 |
| Phase 11 | 抄袭关系图谱 + 公平性告警 | ✅ 已验证 |
| Phase 12 | 训练报告 + 成绩册 | ❌ 已取消 |
| Phase 13 | 比赛澄清提问 + 公告 + 工作人员回复 | ✅ 已验证 |
| Phase 14 | IOI 子任务聚合 + 自定义判题器 + 部分分 | ✅ 已验证 |
| Phase 15 | 规模化治理：异步任务、全局审计中心、归档/恢复/软删除 | ⚠️ 已实现，未验证 |

---

## 二、已在服务器端完成的工作

### 2.1 Embedding 服务配置修复

| 步骤 | 操作 | 结果 |
|------|------|------|
| 定位部署目录 | `find / -name "compose.production.yml"` | 确认主目录为 `/opt/aioj/` |
| 修改功能开关 | `sed -i 's/AI_EMBEDDING_ENABLED=false/AI_EMBEDDING_ENABLED=true/' env/app.env` | 启用 Embedding 服务 |
| 替换失效 Key | 将失效的 `sk-cd0e88f6...` 替换为新的百炼 Key `sk-0a51763d...` | API Key 有效 |
| 重启服务 | `docker compose ... down && up -d` | 全部 13 个容器 Healthy |
| 验证连通性 | `curl` 直接调用 DashScope Embedding API | HTTP 200，返回向量数据 |
| 平台测试 | 管理控制台 → Embedding → 测试调用 | **测试成功** |

### 2.2 代码库初始化

- 在 `/opt/ai-oj-next-react/app/` 初始化 Git 仓库：
  ```bash
  git init
  git config --global --add safe.directory /opt/ai-oj-next-react/app
  git add . && git commit -m "initial backup before development"
  ```
- 确认代码结构为 monorepo：后端 Spring Boot 多服务 + 前端 React + 共享 API 客户端。

### 2.3 后端能力摸底

- 确认 `AiRetrievalService` 被 6 个核心模块调用：
  - `AiContextService`（AI 对话上下文）
  - `AiMemoryService` / `AiMemoryMergeService` / `AiMemoryCandidateService`（记忆系统）
  - `AiLearningProfileService`（学习画像）
  - `AiConversationContextV2Service`（对话上下文 V2）
- 确认数据库迁移脚本 `V45__ai_retrieval_metadata.sql` 已执行，向量表已存在。

### 2.4 浏览器问题排查

- 原浏览器（无 F12 功能）无法访问平台，判定为国产浏览器兼容模式或阉割版。
- 更换 Chrome/Edge 后正常访问，确认是浏览器端问题，非服务端问题。

---

## 三、接下来的规划方向

### 3.1 短期：让语义搜题可用（用户可见价值最高）

**目标**：学生在题目列表页输入"二分查找的变形题"，即使标题中没有"二分"二字，也能通过语义理解返回相关题目。

**前提**：必须先解决"无米之炊"——题目库向量化。

### 3.2 中期：验证并优化 AI 辅导的 RAG 效果

**目标**：确认 `AiContextService` 在每次学生提问时是否有效调用了 `searchDetailed()`，并观察检索结果对 AI 回答质量的提升。

**方法**：在 `AiContextService` 中增加日志观察，或放宽检索触发条件。

### 3.3 长期：比赛系统 Phase 15 验证与后续扩展

**目标**：完成 Phase 15（规模化治理）的验收测试，确认异步任务、全局审计中心、归档/恢复/软删除治理功能稳定。

**可选**：根据业务需求，重新评估 Phase 12（训练报告）或团队比赛功能是否恢复。

---

## 四、需要如何去实现

### 4.1 语义搜题功能实现路径

实现语义搜题需要**后端暴露接口 + 批量索引题目 + 前端接入**，三步缺一不可。

#### 步骤 1：批量索引现有题目（必须先做）

当前 `ai_retrieval_chunks` 中 `owner_type = 'problem'` 的数量为 0。需要将所有历史题目的标题 + 描述生成向量并入库。

**方案 A：Python 一次性脚本（最快，1 小时内完成）**
- 直接读取 MySQL 的 `problem` 表。
- 调用 DashScope `text-embedding-v3` API 生成向量。
- 直接 `INSERT` 到 `ai_retrieval_chunks` 表。
- 优点：无需构建 Java 后端，不引入新 Bug。
- 缺点：非正规工程方案，后续新增题目需要手动重跑或补脚本。

**方案 B：Java 内部接口（更规范）**
- 在 `ai-service` 中新增 `POST /internal/ai/index-all-problems` 接口。
- 调用 `problem-service` 获取所有题目，循环调用 `AiRetrievalService.indexChunk()`。
- 优点：复用现有基础设施，新增题目可自动触发索引。
- 缺点：需要编译、构建镜像、部署，周期较长。

#### 步骤 2：后端暴露 REST 搜索接口

在 `ai-service` 中新建 `AiRetrievalController.java`：

```java
@GetMapping("/api/v1/ai/retrieval/problems")
public List<AiRetrievalHit> searchProblems(
    @RequestParam String q,
    @RequestParam(defaultValue = "10") int limit
) {
    // 调用 retrievalService.searchDetailed()
    // ownerType 过滤为 ["problem"]
}
```

#### 步骤 3：API 客户端新增接口

在 `packages/api-client/src/index.ts` 中新增：

```typescript
semanticSearchProblems: (params: { q: string; limit?: number }) =>
    request<AiRetrievalHit[]>(`/api/v1/ai/retrieval/problems${queryString(params)}`),
```

同时补充 `AiRetrievalHit` 的 TypeScript 类型定义。

#### 步骤 4：前端搜索框增加"智能搜索"模式

修改 `apps/web-user-react/src/views/ProblemsView.tsx`：
- 增加 `semanticMode` 状态（布尔值）。
- 搜索框旁增加开关（如复选框或按钮）："🔍 智能搜索"。
- 当 `semanticMode = true` 且输入关键词时，调用 `api.semanticSearchProblems()` 替代 `api.problems()`。
- 结果展示复用现有题目卡片组件，但数据来源从 `problemsQuery` 切换为 `semanticQuery`。
- 考虑加载态和空状态的处理。

#### 步骤 5：构建与部署

```bash
cd /opt/ai-oj-next-react/app

# 前端构建
npm run build:user:react
npm run build:admin:react

# 后端构建（如果只改了 ai-service）
cd backend/ai-service
mvn clean package -DskipTests
docker build -t my-aioj-ai:latest .

# 更新 deploy.env 中的 AI_IMAGE
# 重启服务
cd /opt/aioj
docker compose -f compose.production.yml --env-file env/app.env --env-file deploy.env up -d --no-deps --force-recreate ai-service
```

### 4.2 AI 辅导 RAG 效果验证路径

1. **日志观察**：
   ```bash
   docker logs -f aioj-ai-service-1 2>&1 | grep -i "searchDetailed\|retrieval"
   ```
   让学生账号与 AI 对话，观察是否有检索调用。

2. **代码审查**：
   - 打开 `AiContextService.java`，确认 `AiRetrievalService` 的注入点和调用条件。
   - 如果调用条件过于严格（如仅在特定 intent 下触发），放宽条件。

3. **效果评估**：
   - 对比开启/关闭检索时，AI 回答是否开始引用历史对话、相关题目或学生过往提交。

### 4.3 工程规范建议

1. **Git 版本控制**：代码库已初始化 Git，后续所有修改应通过 commit 管理，避免直接在生产环境文件上反复编辑。
2. **环境变量管理**：`app.env` 中的 Key 属于敏感信息，不应提交到 Git。建议将 `env/` 目录加入 `.gitignore`。
3. **前端技术栈约束**：
   - React 19 + TypeScript + Vite
   - TanStack Router / Query
   - Tailwind CSS v4
   - 禁止在 React 状态/路由参数中将雪花 ID 转为 number 类型
4. **API 调用规范**：前端请求必须通过 `@aioj/api-client`，禁止页面级直接 `fetch`。

---

## 五、当前平台关键配置快照

| 配置项 | 值 |
|--------|-----|
| Embedding Provider | DashScope |
| Embedding 模型 | `text-embedding-v3` |
| Embedding 维度 | 1024 |
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings` |
| API Key | `sk-0000000000000000000000000000000000000(百炼apikey) |
| 功能开关 | `AI_EMBEDDING_ENABLED=true` |
| 向量表 | `ai_retrieval_chunks`（1477 条，无题目） |
| 前端版本 | React 19（已部署） |
| 后端版本 | Spring Boot 3.5.x（多服务） |
| 部署方式 | Docker Compose |
| 主部署目录 | `/opt/aioj/` |
| 代码目录 | `/opt/ai-oj-next-react/app/` |

---

*本文档基于 2026-08-19 的服务器实地调研和代码审查生成。*
