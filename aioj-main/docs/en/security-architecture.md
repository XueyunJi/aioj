# Security architecture

[中文](../zh/security-architecture.md)

## Goal

AIOJ handles untrusted user code, credentials, hidden tests, contest problems, participant source, and AI-provider data. The security architecture combines least privilege, server-side authorization, data minimization, asynchronous isolation, auditability, and defense in depth.

## Data classification

| Data | Protection |
| --- | --- |
| secrets, tokens, database credentials | server secret storage only; never Git, image layers, logs, or clients |
| hidden tests and testcase packages | file/volume storage, authorized by problem and judge job, never public download |
| participant source | visible to its student; teacher access uses audited APIs; plagiarism exports omit full source by default |
| private contest statements | visible only to requests satisfying participant and time policy |
| AI conversations, memory, and profiles | minimal collection, user-scoped authorization, traceable provenance, user-confirmed candidates |
| judge output | clients receive product-level summaries; complete internal output remains bounded and restricted |

## Identity and authorization

The gateway is the browser entry, but target services enforce final business authorization. JWT, role, and resource ownership jointly determine access; an internal service token never replaces user-level resource checks. Hiding a frontend button is not a security control.

## Judge isolation

problem-service publishes jobs, while judge-worker only orchestrates and calls Sandbox. Sandbox has no Docker socket, host network, business directory, database, JWT, AI, or deployment secret. Testcases are read-only; temporary workspace is isolated; CPU, memory, PID, time, output, and logs are bounded.

### Privileged-Sandbox risk

The current go-judge integration uses a `privileged` container. Normal code remains constrained by namespaces, cgroups, and execution limits, but a successful exploit against Sandbox, Docker, the container runtime, or the Linux kernel could still:

- obtain host-root-equivalent control;
- read or alter databases, user data, secrets, and tokens;
- modify judge results, images, containers, or internal traffic;
- access MySQL, Redis, RabbitMQ, and business services;
- exhaust CPU, memory, processes, disk, or network;
- establish host-level persistence.

Single-node deployment increases blast radius and single-point-of-failure impact. Internal networks, read-only mounts, resource limits, and secret isolation are mandatory compensating controls, but they cannot make the single-node topology security-equivalent to an isolated judge node.

## AI security

- The model and its output are untrusted; authorization and tool execution remain server-side.
- Private statements, hidden tests, and unrelated personal data are not sent to the provider.
- Prompts, tool results, and recalled content distinguish instruction authority from data provenance.
- AI plagiarism and postmortem output is advisory evidence, not an automatic disciplinary decision or final grade.
- Memory writes require a candidate, evidence, conflict checks, and user confirmation.

## Secrets and delivery

GitHub stores only restricted deployment identity and fixed host-key material required by the release path. Application and GHCR-read credentials remain in root-only server configuration. Production uses immutable image digests rather than `latest`, and the server stores no Git worktree.

## Recovery boundary

Production backups originate only from the production server and protect the database, broker state, testcase packages, and required artifacts. Image rollback uses current data volumes; a successful Flyway migration is not automatically reversed. Restore and cleanup are separately authorized operations, never incidental effects of an application release.

## Responsible disclosure

Report security issues through the maintainer's verified private channel. Never publish credentials, personal data, hidden tests, private source, infrastructure details, or directly actionable exploitation steps in a public issue.
