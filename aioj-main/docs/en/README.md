# AIOJ design documentation

[中文](../zh/README.md)

This directory contains current product and technical design only. Agent operating instructions, implementation plans, phase trackers, project memory, production runbooks, and one-off migration records are intentionally excluded from the public design library.

## Contents

| Design | Scope | 中文 |
| --- | --- | --- |
| [Product design](product-design.md) | users, goals, core experience, and non-goals | [中文](../zh/product-design.md) |
| [System architecture](system-architecture.md) | services, data flow, persistence, and production topology | [中文](../zh/system-architecture.md) |
| [Contest and user-group architecture](contest-and-user-group-architecture.md) | blueprints, runs, snapshots, scoring, and audit | [中文](../zh/contest-and-user-group-architecture.md) |
| [Agent Core V3 architecture](agent-core-v3-architecture.md) | trusted control plane, tools, recall, memory, and contest safety | [中文](../zh/agent-core-v3-architecture.md) |
| [Security architecture](security-architecture.md) | trust boundaries, data protection, and privileged-Sandbox risk | [中文](../zh/security-architecture.md) |
| [Delivery architecture](delivery-architecture.md) | CI, GHCR, immutable images, deployment, and rollback boundaries | [中文](../zh/delivery-architecture.md) |

For build and contribution entry points, see the repository [README.md](../../README.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md).
