import http from "k6/http";
import { check, fail } from "k6";
import { Rate, Trend } from "k6/metrics";

const MAX_ACCOUNTS = 50;

const loginSuccessRate = new Rate("aioj_login_success_rate");
const loginDurationMs = new Trend("aioj_login_duration_ms", true);
const loginStartOffsetMs = new Trend("aioj_login_start_offset_ms", true);

function configurationError(message) {
  throw new Error(`Login load-test configuration error: ${message}`);
}

function requiredEnv(name) {
  const value = (__ENV[name] || "").trim();
  if (!value) {
    configurationError(`${name} is required`);
  }
  return value;
}

function intEnv(name, fallback, minimum, maximum) {
  const raw = (__ENV[name] || String(fallback)).trim();
  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    configurationError(`${name} must be an integer from ${minimum} to ${maximum}; got ${raw}`);
  }
  return value;
}

function rateEnv(name, fallback) {
  const raw = (__ENV[name] || String(fallback)).trim();
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    configurationError(`${name} must be a number from 0 to 1; got ${raw}`);
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

function normalizeLoginPath(value) {
  const normalized = value.trim();
  if (!/^\/[A-Za-z0-9/_-]+$/.test(normalized)) {
    configurationError("LOGIN_PATH must be an absolute path without a query string");
  }
  return normalized;
}

const config = {
  baseUrl: normalizeBaseUrl(requiredEnv("BASE_URL")),
  loginPath: normalizeLoginPath(__ENV.LOGIN_PATH || "/api/v1/auth/login"),
  accountPrefix: (__ENV.K6_ACCOUNT_PREFIX || "k6stu").trim(),
  accountCount: intEnv("K6_ACCOUNT_COUNT", MAX_ACCOUNTS, 1, MAX_ACCOUNTS),
  password: requiredEnv("K6_PASSWORD"),
  loginP95MaxMs: intEnv("LOGIN_P95_MAX_MS", 5000, 100, 120000),
  loginSuccessMinimum: rateEnv("LOGIN_SUCCESS_MINIMUM", 1),
  httpFailureMaximum: rateEnv("HTTP_FAILURE_MAXIMUM", 0.01),
  maxDuration: (__ENV.MAX_DURATION || "2m").trim(),
  runLabel: (__ENV.K6_RUN_LABEL || `login-${Date.now()}`).trim(),
};

if (!/^[A-Za-z0-9-]+$/.test(config.accountPrefix)) {
  configurationError("K6_ACCOUNT_PREFIX may contain only letters, digits, and hyphens");
}

export const options = {
  scenarios: {
    simultaneous_login: {
      executor: "per-vu-iterations",
      vus: config.accountCount,
      iterations: 1,
      maxDuration: config.maxDuration,
      gracefulStop: "5s",
    },
  },
  thresholds: {
    aioj_login_success_rate: [`rate>=${config.loginSuccessMinimum}`],
    aioj_login_duration_ms: [`p(95)<${config.loginP95MaxMs}`],
    "http_req_duration{name:auth_login}": [`p(95)<${config.loginP95MaxMs}`],
    http_req_failed: [`rate<${config.httpFailureMaximum}`],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function accountForVu(vu) {
  return `${config.accountPrefix}${String(vu).padStart(3, "0")}`;
}

function apiData(response) {
  try {
    const body = response.json();
    return body && body.data !== undefined ? body.data : null;
  } catch (error) {
    return null;
  }
}

export function setup() {
  console.info(
    `AI-OJ login load test target=${config.baseUrl}${config.loginPath} `
      + `run=${config.runLabel} simultaneousUsers=${config.accountCount}`,
  );
  return { startedAt: Date.now() };
}

export default function (setupData) {
  loginStartOffsetMs.add(Math.max(0, Date.now() - setupData.startedAt));
  const response = http.post(`${config.baseUrl}${config.loginPath}`, JSON.stringify({
    account: accountForVu(__VU),
    password: config.password,
  }), {
    headers: {
      "Content-Type": "application/json",
      "User-Agent": "aioj-k6-login-load-test/1",
      "X-Load-Test-Run": config.runLabel,
    },
    tags: { name: "auth_login" },
    timeout: "120s",
  });

  loginDurationMs.add(response.timings.duration);
  const data = response.status === 200 ? apiData(response) : null;
  const succeeded = Boolean(data && typeof data.accessToken === "string" && data.accessToken);
  loginSuccessRate.add(succeeded);
  check(response, { "login succeeded": () => succeeded });
  if (!succeeded) {
    fail(`Login failed for VU ${__VU}, HTTP ${response.status}`);
  }
}
