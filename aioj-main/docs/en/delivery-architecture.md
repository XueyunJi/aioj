# Delivery architecture

[中文](../zh/delivery-architecture.md)

## Goal

AIOJ uses GitHub Actions, private GHCR packages, immutable image digests, and a protected production environment to create a traceable relationship between source, build artifacts, and the version running in production. The production server neither pulls a Git worktree nor builds source in place.

## Delivery flow

```text
reviewed main
   -> formal SemVer GitHub Release
   -> CI tests and repository policy checks
   -> build eight Linux/amd64 images
   -> private GHCR packages + SBOM + provenance
   -> service -> image@digest manifest
   -> production approval
   -> restricted SSH deployment gate
   -> health validation and version record
```

The eight application images are gateway, auth, problem, ai, judge-worker, sandbox, web-user, and web-admin. Third-party infrastructure images are also pinned to reviewed platform digests.

## Immutable version identity

A production version has three complementary identities:

- a Git release tag for humans;
- a Git commit for source;
- an OCI digest for the exact runtime image content.

Production Compose accepts complete `image@sha256:...` references rather than `latest` or movable tags. The release manifest records one digest per service and has its own checksum.

## CI and supply-chain evidence

CI checks the backend, React applications, Compose, Markdown links, repository hygiene, and secret leakage. Image builds produce SBOM and provenance attestations. Third-party Actions are pinned to reviewed commit SHAs to reduce supply-chain drift.

## Production boundary

- The production root stores image-only Compose, environment files, current/previous digest sets, and deployment history, but no Git worktree.
- Application secrets and GHCR-read credentials stay in root-only server configuration, never GitHub Releases or image layers.
- The GitHub deploy identity has no Docker group or general sudo; a forced command can invoke only the root-owned deployment entrypoint.
- The entrypoint accepts only a valid release tag and manifest checksum and validates namespace, platform, and digest.

## Data and migration

Production data always originates on the production server; workstation databases, user exports, and local testcase directories are never substitutes. auth-service applies Flyway during a controlled startup stage. Migrations are forward-only; rolling back application images does not reverse the schema automatically.

## Startup dependencies

The production stack forms a dependency graph: infrastructure becomes healthy first, auth completes schema checks, the judge path starts only after problem is available, and AI, gateway, and web follow. Health checks pass only when dependencies are actually serviceable, avoiding a process-running-but-application-unavailable state.

## Rollback model

Routine rollback restores the previous image-digest set while retaining current data volumes. A failed deployment may roll back images automatically; database structure and newly written data are not reversed automatically. During an initial cutover, the complete old stack remains recoverable only before writes reopen. After the new version writes data, old volumes are stale recovery points and must not be put back online directly.

## Single-node impact

The selected delivery target combines application, data, and judging on one node. Release acceptance therefore observes container health, restart/OOM state, host memory and disk, broker state, the judge path, and the privileged-Sandbox boundary together. Capacity results from the former split-node topology do not establish a single-node SLA.
