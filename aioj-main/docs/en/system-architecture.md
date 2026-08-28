# System architecture

[中文](../zh/system-architecture.md)

## Overview

```text
Browser -> web-user / web-admin -> gateway
                                  |-> auth-service -> MySQL
                                  |-> problem-service -> MySQL / Redis / RabbitMQ
                                  `-> ai-service -> compatible provider

RabbitMQ -> judge-worker -> go-judge Sandbox
```

Browsers access only `/api/v1/**` through the gateway. Cross-service contracts and Flyway migrations are centralized in `backend/api-contract`; shared responses, errors, tracing, and security filters live in `backend/common-lib`.

## Components

| Component | Responsibility |
| --- | --- |
| gateway-service | only browser API entry, routing, and CORS boundary |
| auth-service | login, JWT/refresh tokens, users, roles, groups, and Flyway |
| problem-service | problems, testcases, contests, submissions, notifications, and asynchronous jobs |
| judge-worker | consumes RabbitMQ judge messages and calls Sandbox; never executes code itself |
| ai-service | agent runtime, conversations, memory, drafts, plagiarism review, and postmortems |
| web-user / web-admin | student and administration React SPAs |
| MySQL / Redis / RabbitMQ | authoritative business data, cache/short-lived state, and judge queue |
| go-judge Sandbox | privileged runtime for isolated execution of untrusted code |

## Main data flows

### Authentication

The browser logs in through the gateway to auth-service. Passwords are verified only during login, then replaced by short-lived access tokens and controlled refresh tokens. Other services use shared security filters for identity and role checks.

### Judging

problem-service persists a submission and publishes a RabbitMQ job. judge-worker consumes the job, resolves authorized testcase references, and calls Sandbox. Results are written back to problem-owned data; browsers never connect directly to judge-worker or Sandbox.

### AI

ai-service calculates permission and policy on the server, builds minimal context, invokes the provider and authorized tools, and persists usage and audit evidence. AI accesses problem-service through minimal service contracts rather than reading another service's database tables.

### Notifications

Persistent notification records are the source of truth; SSE is only a real-time wake-up signal. After disconnect or refresh, the client uses REST recovery to restore unread state.

## Trust boundaries

- Browser input, client-supplied contest context, and model output are untrusted.
- Authorization, contest visibility, tool permission, and data minimization are enforced on the server.
- Frontends preserve 16+ digit entity IDs as strings.
- Hidden tests, participant source, provider secrets, and complete judge output cannot cross unauthorized APIs.
- AI output and tool calls never gain more authority than the current user and scenario policy.

## Persistence

- MySQL stores authoritative business data and Flyway history.
- Redis stores rebuildable cache and short-lived state.
- RabbitMQ stores asynchronous judge jobs and bounded retry state.
- Testcase packages, operation artifacts, AI-draft artifacts, and judge cache use file/volume storage; large hidden tests do not enter MySQL.

## Production topology

The selected production topology combines application services, data services, judge-worker, and Sandbox on one host. The application network carries business traffic, while an internal judge network connects only judge-worker and Sandbox. Sandbox has no public port, host network, Docker socket, business mount, or database/JWT/AI/deployment secret.

Sandbox remains `privileged`, so a successful escape could compromise the host and all colocated data. Network and resource controls cannot fully remove that risk; see the [security architecture](security-architecture.md).
