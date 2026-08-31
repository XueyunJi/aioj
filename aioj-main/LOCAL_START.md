# 本地启动 AIOJ

## 一键启动

在仓库根目录 `C:\aioj-8-27` 执行：

```powershell
.aioj-main\scripts\start-local.ps1
```

也可以双击或在命令行执行：

```text
aioj-main\scripts\start-local.cmd
```

脚本会复用项目名 `ai-oj-next`、`deploy/compose.yml`、`deploy/compose.local-legacy-ai.yml` 和仓库外的 `C:\aioj-8-27\.env`。AI 服务固定使用当前本地镜像 digest，并设置为不拉取；默认只执行恢复/启动，不重新构建镜像。

首次使用时，如不存在 `C:\aioj-8-27\.env`，先复制 `aioj-main\.env.example` 为该文件，并替换本机开发用配置。`.env` 已被 Git 忽略，不要提交真实密码或密钥。

当前启动脚本不提供构建参数，以免误将固定镜像替换。需要切换 AI 镜像时，应先明确更新 `deploy/compose.local-legacy-ai.yml` 中的 digest，再定向重建 `ai-service`。

## 地址

- 用户端：`http://localhost:5175`
- 管理端：`http://127.0.0.1:5176`
- Gateway：`http://127.0.0.1:8101`
- 健康检查：`http://127.0.0.1:8101/actuator/health`

查看状态：

```powershell
docker compose --project-name ai-oj-next --env-file .env --file aioj-main/deploy/compose.yml ps
```

停止本地环境时使用对应 Compose 配置执行 `down`。该操作会停止并移除容器，但不会删除数据卷。
