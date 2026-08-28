# Security policy / 安全策略

[中文](#中文) · [English](#english) · [中文安全架构](docs/zh/security-architecture.md) · [English security architecture](docs/en/security-architecture.md)

## 中文

请勿在公开 Issue 中提交凭据、个人数据、隐藏测试、参与者源码、Provider Prompt、私有基础设施细节或可直接利用的攻击步骤。请通过仓库维护者 GitHub 资料中的已验证私密渠道报告，并只提供复现所需的最小证据。

安全修复面向当前 `main` 与最新正式 SemVer Release。服务器秘密和生产数据不得进入 GitHub、镜像层、Release 资产、命令参数或日志。

当前单节点生产拓扑包含 `privileged` go-judge Sandbox。正常提交仍受命名空间、cgroup、时间、进程、内存和输出限制，但这些限制不能证明能够抵御 Sandbox、Docker、容器运行时或 Linux 内核逃逸。成功逃逸可能导致宿主机 root 等价控制、数据与密钥泄露、评测结果篡改、资源耗尽或持久化入侵。维护者已明确接受该风险相对独立判题节点的安全降级。

## English

Do not open a public issue containing credentials, personal data, hidden tests, participant source, provider prompts, private infrastructure details, or directly usable exploitation steps. Report privately through the verified contact channel on the maintainer's GitHub profile and include only the minimum evidence needed to reproduce the issue.

Security fixes target current `main` and the latest formal SemVer release. Server secrets and production data must never enter GitHub, image layers, release assets, command arguments, or logs.

The selected single-node production topology includes a `privileged` go-judge Sandbox. Normal submissions remain bounded by namespaces, cgroups, time, process, memory, and output controls, but those controls do not prove resistance to Sandbox, Docker, container-runtime, or Linux-kernel escape. A successful escape could yield host-root-equivalent control, expose data and secrets, alter judge results, exhaust resources, or establish persistence. The maintainer has explicitly accepted this downgrade relative to an isolated judge node.
