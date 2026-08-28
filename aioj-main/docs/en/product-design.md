# Product design

[中文](../zh/product-design.md)

## Positioning

AIOJ is a campus-oriented online judge, not a general cloud IDE and not a contest-only system. It is designed around the loop of reading a problem, writing code, submitting, understanding feedback, and continuing to learn. Teachers receive complementary tools for problems, testcase packages, contests, user groups, audit, and governed AI assistance.

## Users

- Students practise problems, join contests, inspect submissions and feedback, use constrained AI assistance, generate personal postmortems, and decide whether weakness candidates may enter long-term memory.
- Teachers and administrators manage users and groups, problems and testcase packages, contest blueprints and runs, AI problem drafts, plagiarism evidence, postmortems, and operational audit.

## Core principles

1. **Learning first:** AI should advance reasoning and debugging rather than replace the learning process.
2. **Stable task surfaces:** statements, code, submissions, conversations, and history have predictable layout and scrolling.
3. **Visible but compact context:** current problem, contest, code, and memory state use concise status, badges, and summaries.
4. **Audit before opaque automation:** AI generation, contest protection, source access, plagiarism review, and postmortems retain the evidence required for review.
5. **Historically reproducible runs:** contest snapshots prevent later profile or problem edits from rewriting a past run.
6. **Server-side authority:** client context improves experience but never grants permission.

## Main experiences

### Practice

Students find problems by number, title, difficulty, and tags, read statements and samples in one surface, edit language templates, and inspect asynchronous judge results and submission history.

### Contests

A contest blueprint describes reusable rules; a contest run describes one concrete opening. Registration or invitation gives access to the run. Problems, participants, rules, scoreboards, announcements, clarifications, submissions, postmortems, and audit remain run-scoped.

### AI assistance

The tutor uses authorized current context, auditable tools, and layered memory. During a contest, server-side policy constrains private-problem questions and requests for complete solutions to public contest problems. AI problem generation always follows draft → review/approve → import.

### Learning memory

Long-term memory and learning profiles are separate. AI may propose a memory candidate but cannot activate it. A candidate enters long-term memory or a weakness profile only after explicit student acceptance.

## Non-goals

- Do not restore removed study-subgroup, training-report, or gradebook implementations.
- Do not execute user code inside judge-worker.
- Do not treat AI plagiarism, risk, or postmortem conclusions as disciplinary decisions or final grades.
- Do not expose hidden tests, complete judge output, other participants' source, or private statements to unauthorized users.

## Visual and accessibility direction

The interface uses a restrained blue-and-white study-workspace style and prioritizes readability of dense information. Student surfaces target WCAG AA contrast, interactions retain keyboard and visible-focus behavior, and motion is short, state-driven, and respectful of reduced-motion preferences.
