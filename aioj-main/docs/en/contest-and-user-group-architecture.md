# Contest and user-group architecture

[中文](../zh/contest-and-user-group-architecture.md)

## Organization model

User groups are the only maintained teaching-organization model. They support membership management, contest-run eligibility, participant import, and teacher/administrator permission checks. Removed study-subgroup and legacy training-report models are not part of the current product architecture.

## Blueprint and run

`contest` is a reusable blueprint containing title, description, mode, scoring rules, and problem arrangement.

`contest_run` is one concrete opening containing start, end, and freeze time; registration policy; allowed groups; participants; submissions; scoreboard; audit; announcements; clarifications; plagiarism review; and postmortems. Every run-level operation carries a concrete `runId`; cross-run aggregation is not the default.

```text
contest blueprint
      |
      +-- contest_run A -> snapshots -> participants -> submissions -> reports
      |
      `-- contest_run B -> snapshots -> participants -> submissions -> reports
```

## Lifecycle

A draft run may be configured but is not student-visible. Publishing freezes rule, problem, visibility, and AI-policy snapshots. A non-draft run derives scheduled, running, or ended status from server time. When the end time has passed but the run is not archived, only archive-oriented governance remains available. Archive and soft-delete flags take precedence over time-derived status.

## Snapshots

- Required participant identity is snapshotted when a participant joins or is approved.
- Statement, visibility, rules, and AI policy are snapshotted when the run is published.
- Historical scoreboards, submissions, and reports use run snapshots and cannot be rewritten by later problem or profile edits.

## Registration and invitations

Public registration evaluates the time window, approval requirement, capacity, and group eligibility. Private invitations may be saved while a run is draft, but they become student-visible and generate notifications only after publication. Accepting an invitation and opening the run share the same server-side access conditions, preventing an accepted-but-unopenable state.

## Scoring

### ACM

Ranking uses solved count descending, penalty ascending, last accepted time, and stable display order. Submissions after freeze appear pending until unfreeze or resolver reveal.

### IOI/OI

Ranking uses total score descending and last improvement time. Subtask and case results may provide score evidence, while hidden testcase content remains private.

## Announcements and clarifications

Announcements and clarifications belong to one run. Announcements may be pinned or archived. A clarification is one question plus one official reply. Public replies are visible to eligible students while hiding asker identity; private replies are visible only to staff and the asking student.

## AI, plagiarism, and postmortems

- Contest-time AI policy comes from the run snapshot and server-side participation state.
- AI plagiarism review produces similarity evidence and risk signals, not disciplinary decisions.
- Personal and teacher postmortems use authorized run, submission, and judge summaries without hidden tests or other participants' private information.
- A student weakness candidate enters long-term memory only after explicit student acceptance.

## Audit and privacy

Teacher source access, evidence export, plagiarism review, postmortem generation, and run governance are audited. Exports and UI provide only the minimum necessary evidence and never expose complete third-party source, secrets, or full judge output by default.
