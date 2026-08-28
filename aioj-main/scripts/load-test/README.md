# AIOJ k6 capacity tools

These scripts generate real authentication traffic and, for the mixed test,
real submissions. They are not a deployment health check and must never run
against production without a quiet maintenance/test window and explicit
authorization.

## Safety boundary

- Production server data is the only source for test accounts, problem IDs,
  and standard solutions. Never import a local database or code file.
- Credentials and standard solutions stay in an ACL-restricted local directory
  and are never committed, logged, sent to an Agent, or included in k6 HTTP
  debug output.
- Run k6 from an external machine; running it on the server competes for the
  same CPU and invalidates capacity evidence.
- Stop if contests/jobs are active, judge ready/unacked/DLQ is nonzero before
  the test, health is degraded, swap is active, disk is pressured, or a
  container restarted/OOMed.
- Use a new run label each time and never start two rounds in parallel.
- `k6-submissions.js` requires
  `AIOJ_LOAD_TEST_ACK=I_UNDERSTAND_THIS_CREATES_REAL_SUBMISSIONS`.

## Scripts

- `k6-login.js`: 50 unique accounts, one login each, no submission.
- `k6-submissions.js`: unique accounts, three server-selected public problems,
  one real submission per VU, terminal-result polling, and expected AC/TLE mix.
- `create-k6-accounts.sh`: root-only, separately authorized production helper
  that creates/verifies test students and writes restricted env/code files.

## Server-side preparation

The image-only server does not keep a Git worktree. Copy only the reviewed
preparation script for the authorized test, then run a dry-run first:

```bash
P1_ID='<server-problem-id>' \
P2_ID='<server-problem-id>' \
P3_ID='<server-problem-id>' \
DRY_RUN=1 \
bash /root/create-k6-accounts.sh
```

Defaults target `/opt/aioj`, `/opt/aioj/env/app.env`,
`/opt/aioj/deploy.env`, and `/opt/aioj/compose.production.yml`. Review the
dry-run and repeat without `DRY_RUN=1` only with explicit approval. The helper
does not print database/JWT values and verifies every generated account login.

Copy only the generated k6 env and code JSON to an ACL-restricted workstation
directory. Do not copy the database backup.

## Login burst

```powershell
$root = "$HOME\.aioj\load-test"
$env:K6_PASSWORD = ((Get-Content "$root\k6-load-test.env" |
  Where-Object { $_ -match '^K6_PASSWORD=' } | Select-Object -Last 1) -replace '^K6_PASSWORD=', '')
$env:BASE_URL = "https://student.example.edu"
$env:K6_ACCOUNT_PREFIX = "k6stu"
$env:K6_ACCOUNT_COUNT = "50"
$env:LOGIN_P95_MAX_MS = "7000"
$env:K6_RUN_LABEL = "single-node-login-50-r1"
k6 run scripts/load-test/k6-login.js
Remove-Item Env:K6_PASSWORD
```

Run three rounds and capture auth cgroup `usage_usec`, `nr_throttled`, and
`throttled_usec`, container restarts/OOM, host swap, and gateway health. The
previous split-host 5.80-6.26 second P95 is a regression reference, not a new
single-host acceptance threshold.

## Controlled AC/TLE smoke

```powershell
$root = "$HOME\.aioj\load-test"
$env:K6_ENV_FILE = "$root\k6-load-test.env"
$env:K6_CODES_FILE = "$root\k6-submission-codes.json"
$env:BASE_URL = "https://student.example.edu"
$env:AIOJ_LOAD_TEST_ACK = "I_UNDERSTAND_THIS_CREATES_REAL_SUBMISSIONS"
$env:K6_RUN_LABEL = "single-node-smoke-ac"
$env:P1_TOTAL = "1"
$env:P1_TLE = "0"
$env:P2_TOTAL = "0"
$env:P2_TLE = "0"
$env:P3_TOTAL = "0"
$env:P3_TLE = "0"
k6 run scripts/load-test/k6-submissions.js
```

Repeat a one-submission expected-TLE smoke before a larger test. Any mismatch,
5xx, SYSTEM_ERROR, queue stall, Sandbox error, restart, OOM, or swap stops the
test.

## Historical 50-submission distribution

The retained regression model is 20 + 15 + 15 submissions with expected TLE
counts 5 + 3 + 2. On the merged host, judge concurrency stays one and queue
drain is part of the result; do not raise concurrency during a test.

```powershell
$env:K6_RUN_LABEL = "single-node-50-mixed-r1"
$env:P1_TOTAL = "20"; $env:P1_TLE = "5"
$env:P2_TOTAL = "15"; $env:P2_TLE = "3"
$env:P3_TOTAL = "15"; $env:P3_TLE = "2"
$env:POLL_INTERVAL_MS = "1500"
$env:POLL_TIMEOUT_MS = "300000"
$env:TERMINAL_P95_MAX_MS = "240000"
$env:EXPECTED_RESULT_MINIMUM = "0.95"
$env:HTTP_FAILURE_MAXIMUM = "0.05"
New-Item -ItemType Directory -Force "$root\results" | Out-Null
k6 run --summary-export "$root\results\single-node-50-mixed-r1.json" scripts/load-test/k6-submissions.js
```

Minimum evidence is 50 completed iterations and unique submissions, at least
95% success for login/create/poll/terminal/expected-result metrics, HTTP failure
below 5%, zero SYSTEM_ERROR, eventual queue drain, and no OOM/restart/sustained
swap. This still does not prove a classroom SLA. Capacity acceptance requires
separate combined browsing, contest, notification, AI-adjacent, and
failure-boundary evidence retained outside the public design documentation.
