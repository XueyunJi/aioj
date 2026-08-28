import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const LOAD_TEST_ACK = "I_UNDERSTAND_THIS_CREATES_REAL_SUBMISSIONS";
const MAX_ACCOUNTS = 50;
const TERMINAL_STATUSES = new Set([
  "ACCEPTED",
  "WRONG_ANSWER",
  "COMPILE_ERROR",
  "RUNTIME_ERROR",
  "TIME_LIMIT_EXCEEDED",
  "MEMORY_LIMIT_EXCEEDED",
  "OUTPUT_LIMIT_EXCEEDED",
  "SYSTEM_ERROR",
]);

const completedSubmissions = new Counter("aioj_completed_submissions");
const acceptedSubmissions = new Counter("aioj_accepted_submissions");
const tleSubmissions = new Counter("aioj_tle_submissions");
const systemErrorSubmissions = new Counter("aioj_system_error_submissions");
const unexpectedSubmissions = new Counter("aioj_unexpected_submissions");
const loginSuccessRate = new Rate("aioj_login_success_rate");
const submissionAcceptedRate = new Rate("aioj_submission_accepted_rate");
const pollSuccessRate = new Rate("aioj_poll_success_rate");
const terminalCompletionRate = new Rate("aioj_terminal_completion_rate");
const expectedResultRate = new Rate("aioj_expected_result_rate");
const loginDurationMs = new Trend("aioj_login_duration_ms", true);
const submissionAcceptedMs = new Trend("aioj_submission_accepted_ms", true);
const submitToTerminalMs = new Trend("aioj_submit_to_terminal_ms", true);
const iterationDurationMs = new Trend("aioj_iteration_duration_ms", true);

function configurationError(message) {
  throw new Error(`Load-test configuration error: ${message}`);
}

function parseEnvFile(path) {
  if (!path) {
    return {};
  }
  let text;
  try {
    text = open(path);
  } catch (error) {
    configurationError(`cannot read K6_ENV_FILE (${path})`);
  }
  const result = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const equals = line.indexOf("=");
    if (equals < 1) {
      continue;
    }
    const key = line.slice(0, equals).trim();
    let value = line.slice(equals + 1).trim();
    if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

const envFile = parseEnvFile(__ENV.K6_ENV_FILE || "");

function env(name, fallback = "") {
  if (__ENV[name] !== undefined && __ENV[name] !== "") {
    return __ENV[name];
  }
  if (envFile[name] !== undefined && envFile[name] !== "") {
    return envFile[name];
  }
  return fallback;
}

function intEnv(name, fallback, minimum = Number.MIN_SAFE_INTEGER, maximum = Number.MAX_SAFE_INTEGER) {
  const raw = env(name, String(fallback));
  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    configurationError(`${name} must be an integer from ${minimum} to ${maximum}; got ${raw}`);
  }
  return value;
}

function rateEnv(name, fallback) {
  const raw = env(name, String(fallback));
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    configurationError(`${name} must be a number from 0 to 1; got ${raw}`);
  }
  return value;
}

function requiredEnv(name) {
  const value = env(name).trim();
  if (!value) {
    configurationError(`${name} is required`);
  }
  return value;
}

function normalizeBaseUrl(value) {
  const normalized = value.replace(/\/+$/, "");
  if (!/^https?:\/\/[^/]+/i.test(normalized)) {
    configurationError("BASE_URL must be an explicit http(s) origin");
  }
  return normalized;
}

function problemConfig(key) {
  const total = intEnv(`${key}_TOTAL`, 0, 0, MAX_ACCOUNTS);
  const tle = intEnv(`${key}_TLE`, 0, 0, MAX_ACCOUNTS);
  const id = env(`${key}_ID`).trim();
  if (total > 0 && !/^\d+$/.test(id)) {
    configurationError(`${key}_ID must be an explicit decimal ID when ${key}_TOTAL is greater than zero`);
  }
  if (tle > total) {
    configurationError(`${key}_TLE cannot exceed ${key}_TOTAL`);
  }
  return { key, id, total, tle };
}

function loadCodes(path) {
  let parsed;
  try {
    parsed = JSON.parse(open(path));
  } catch (error) {
    configurationError(`cannot parse K6_CODES_FILE (${path})`);
  }
  if (!parsed || typeof parsed !== "object" || !parsed.problems || typeof parsed.problems !== "object") {
    configurationError("K6_CODES_FILE must contain a problems object");
  }
  return parsed;
}

const config = {
  acknowledgement: env("AIOJ_LOAD_TEST_ACK"),
  baseUrl: normalizeBaseUrl(requiredEnv("BASE_URL")),
  accountPrefix: env("K6_ACCOUNT_PREFIX", "k6stu"),
  accountCount: intEnv("K6_ACCOUNT_COUNT", MAX_ACCOUNTS, 1, MAX_ACCOUNTS),
  password: requiredEnv("K6_PASSWORD"),
  codesPath: requiredEnv("K6_CODES_FILE"),
  pollIntervalMs: intEnv("POLL_INTERVAL_MS", 1500, 250, 10000),
  pollTimeoutMs: intEnv("POLL_TIMEOUT_MS", 180000, 10000, 600000),
  terminalP95MaxMs: intEnv("TERMINAL_P95_MAX_MS", 120000, 1000, 600000),
  expectedResultMinimum: rateEnv("EXPECTED_RESULT_MINIMUM", 0.95),
  httpFailureMaximum: rateEnv("HTTP_FAILURE_MAXIMUM", 0.05),
  maxDuration: env("MAX_DURATION", "10m"),
  runLabel: env("K6_RUN_LABEL", `manual-${Date.now()}`),
  problems: [problemConfig("P1"), problemConfig("P2"), problemConfig("P3")],
};

if (config.acknowledgement !== LOAD_TEST_ACK) {
  configurationError(`AIOJ_LOAD_TEST_ACK must equal ${LOAD_TEST_ACK}`);
}
if (!/^[A-Za-z0-9-]+$/.test(config.accountPrefix)) {
  configurationError("K6_ACCOUNT_PREFIX may contain only letters, digits, and hyphens");
}

const codes = loadCodes(config.codesPath);

function buildAssignments() {
  const assignments = [];
  for (const problem of config.problems) {
    if (problem.total === 0) {
      continue;
    }
    const problemCodes = codes.problems[problem.id];
    if (!problemCodes || !problemCodes.standardCode || !problemCodes.timeoutCode) {
      configurationError(`K6_CODES_FILE is missing standardCode or timeoutCode for ${problem.key} (${problem.id})`);
    }
    for (let index = 0; index < problem.total; index += 1) {
      assignments.push({
        problemKey: problem.key,
        problemId: problem.id,
        expected: index < problem.tle ? "TIME_LIMIT_EXCEEDED" : "ACCEPTED",
      });
    }
  }
  if (assignments.length < 1 || assignments.length > config.accountCount) {
    configurationError(`total submissions must be from 1 to K6_ACCOUNT_COUNT (${config.accountCount}); got ${assignments.length}`);
  }
  return assignments;
}

const assignments = buildAssignments();

export const options = {
  scenarios: {
    submissions: {
      executor: "per-vu-iterations",
      vus: assignments.length,
      iterations: 1,
      maxDuration: config.maxDuration,
      gracefulStop: "15s",
    },
  },
  thresholds: {
    aioj_login_success_rate: [`rate>=${config.expectedResultMinimum}`],
    aioj_submission_accepted_rate: [`rate>=${config.expectedResultMinimum}`],
    aioj_poll_success_rate: [`rate>=${config.expectedResultMinimum}`],
    aioj_terminal_completion_rate: [`rate>=${config.expectedResultMinimum}`],
    aioj_expected_result_rate: [`rate>=${config.expectedResultMinimum}`],
    aioj_system_error_submissions: ["count==0"],
    aioj_submit_to_terminal_ms: [`p(95)<${config.terminalP95MaxMs}`],
    http_req_failed: [`rate<${config.httpFailureMaximum}`],
  },
};

function accountForVu(vu) {
  return `${config.accountPrefix}${String(vu).padStart(3, "0")}`;
}

function requestHeaders(token = "") {
  const headers = {
    "Content-Type": "application/json",
    "User-Agent": "aioj-k6-submission-load-test/2",
    "X-Load-Test-Run": config.runLabel,
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return headers;
}

function apiData(response) {
  try {
    const body = response.json();
    if (body && body.data !== undefined && body.data !== null) {
      return body.data;
    }
  } catch (error) {
    // The caller records a sanitized failure without printing response bodies.
  }
  return null;
}

function extractEntityId(response) {
  const body = typeof response.body === "string" ? response.body : "";
  const markerIndex = body.indexOf('"data"');
  const searchable = markerIndex >= 0 ? body.slice(markerIndex) : body;
  const match = searchable.match(/"id"\s*:\s*"?(\d+)"?/);
  return match ? match[1] : "";
}

function login(account) {
  const startedAt = Date.now();
  const response = http.post(`${config.baseUrl}/api/v1/auth/login`, JSON.stringify({
    account,
    password: config.password,
  }), {
    headers: requestHeaders(),
    tags: { name: "auth_login" },
  });
  loginDurationMs.add(Date.now() - startedAt);
  const data = response.status === 200 ? apiData(response) : null;
  const succeeded = Boolean(data && typeof data.accessToken === "string" && data.accessToken);
  loginSuccessRate.add(succeeded);
  check(response, { "login succeeded": () => succeeded });
  if (!succeeded) {
    fail(`Login failed for load-test account ${account}, HTTP ${response.status}`);
  }
  return data.accessToken;
}

function codeFor(assignment) {
  const problemCodes = codes.problems[assignment.problemId];
  if (assignment.expected === "TIME_LIMIT_EXCEEDED") {
    return {
      language: problemCodes.timeoutLanguage || "cpp",
      code: problemCodes.timeoutCode,
    };
  }
  return {
    language: problemCodes.standardLanguage || codes.language || "cpp",
    code: problemCodes.standardCode,
  };
}

function submit(token, assignment) {
  const source = codeFor(assignment);
  const startedAt = Date.now();
  const response = http.post(`${config.baseUrl}/api/v1/submissions`, JSON.stringify({
    problemId: assignment.problemId,
    language: source.language,
    code: source.code,
  }), {
    headers: requestHeaders(token),
    tags: {
      name: "create_submission",
      problem: assignment.problemKey,
      expected: assignment.expected,
    },
  });
  submissionAcceptedMs.add(Date.now() - startedAt);
  const data = response.status === 200 ? apiData(response) : null;
  const submissionId = response.status === 200 ? extractEntityId(response) : "";
  const succeeded = Boolean(data && submissionId);
  submissionAcceptedRate.add(succeeded);
  check(response, { "submission accepted": () => succeeded });
  if (!succeeded) {
    fail(`Submission request failed for ${assignment.problemKey}, HTTP ${response.status}`);
  }
  return { id: submissionId, startedAt };
}

function waitForTerminal(token, assignment, submitted) {
  while (Date.now() - submitted.startedAt < config.pollTimeoutMs) {
    const response = http.get(`${config.baseUrl}/api/v1/submissions/${submitted.id}`, {
      headers: requestHeaders(token),
      tags: { name: "submission_status", problem: assignment.problemKey },
    });
    const data = response.status === 200 ? apiData(response) : null;
    const succeeded = Boolean(data && typeof data.status === "string");
    pollSuccessRate.add(succeeded);
    check(response, { "submission poll succeeded": () => succeeded });
    if (!succeeded) {
      fail(`Submission poll failed for ${submitted.id}, HTTP ${response.status}`);
    }
    if (TERMINAL_STATUSES.has(data.status)) {
      terminalCompletionRate.add(true);
      submitToTerminalMs.add(Date.now() - submitted.startedAt);
      return data;
    }
    sleep(config.pollIntervalMs / 1000);
  }
  terminalCompletionRate.add(false);
  fail(`Submission ${submitted.id} did not reach a terminal status within ${config.pollTimeoutMs}ms`);
}

export function setup() {
  console.info(
    `AI-OJ load test target=${config.baseUrl} run=${config.runLabel} submissions=${assignments.length} `
      + `distribution=${config.problems.map((item) => `${item.key}:${item.total}/${item.tle}TLE`).join(",")}`,
  );
}

export default function () {
  const iterationStartedAt = Date.now();
  const assignment = assignments[__VU - 1];
  const account = accountForVu(__VU);
  const token = login(account);
  const submitted = submit(token, assignment);
  const terminal = waitForTerminal(token, assignment, submitted);

  completedSubmissions.add(1);
  systemErrorSubmissions.add(terminal.status === "SYSTEM_ERROR" ? 1 : 0);
  if (terminal.status === "ACCEPTED") {
    acceptedSubmissions.add(1);
  } else if (terminal.status === "TIME_LIMIT_EXCEEDED") {
    tleSubmissions.add(1);
  }

  const expected = terminal.status === assignment.expected;
  expectedResultRate.add(expected);
  if (!expected) {
    unexpectedSubmissions.add(1);
    console.error(
      `Unexpected terminal status account=${account} problem=${assignment.problemKey} `
        + `submission=${submitted.id} expected=${assignment.expected} actual=${terminal.status}`,
    );
  }
  iterationDurationMs.add(Date.now() - iterationStartedAt);
}
