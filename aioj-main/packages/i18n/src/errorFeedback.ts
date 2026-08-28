import { messages, type Locale } from "./messages";

export interface ErrorResolveContext {
  errorKey?: string | null;
  errorParams?: Record<string, string | number> | null;
  details?: Record<string, string> | null;
  traceId?: string | null;
}

const DEFAULT_LOCALE: Locale = "zh-CN";

const EXACT_MESSAGE_KEYS: Record<string, string> = {
  "Invalid account or password": "auth.invalidCredentials",
  "Account is disabled. Please contact an administrator.": "auth.accountDisabled",
  "User is disabled": "auth.accountDisabled",
  "User account is disabled": "auth.accountDisabled",
  "Account already exists": "auth.accountExists",
  "Current password is incorrect": "auth.currentPasswordIncorrect",
  "New password cannot be the same as the current password": "auth.newPasswordSameAsCurrent",
  "Refresh token is invalid": "auth.refreshTokenInvalid",
  "Refresh token required": "auth.tokenRequired",
  "Bearer token required": "auth.tokenRequired",
  "Invalid token": "auth.invalidToken",
  "Public registration only supports student or teacher roles": "auth.publicRegistrationRole",
  "Invalid roles": "auth.invalidRoles",
  "Student and teacher roles are mutually exclusive": "auth.roleConflict",
  "User not found": "user.notFound",
  "Problem not found": "problem.notFound",
  "Imported problem has been deleted": "problem.importedDeleted",
  "Submission not found": "submission.notFound",
  "Language is required": "submission.languageRequired",
  "Cannot query other users' submissions": "submission.queryForbidden",
  "Cannot read other users' submissions": "submission.readForbidden",
  "AI quota exceeded": "ai.quotaExceeded",
  "AI rolling quota exceeded": "ai.rollingQuotaExceeded",
  "AI monthly quota exceeded": "ai.monthlyQuotaExceeded",
  "AI API key is not configured": "ai.keyMissing",
  "AI model configuration is disabled": "ai.configDisabled",
  "比赛进行中不能请求该题的完整解题代码，可以询问思路、复杂度、边界情况或调试方向。": "ai.contestCodeBlocked",
  "Contest not found": "contest.notFound",
  "Cannot access contest": "contest.forbidden",
  "Cannot manage contest": "contest.manageForbidden",
  "User group not found": "group.notFound",
  "Contest start time must be before end time": "contest.timeInvalid",
  "Contest freeze time must be inside contest time range": "contest.freezeInvalid",
  "Contest must have at least one problem": "contest.problemsRequired",
  "Contest problem labels must be unique": "contest.problemLabelDuplicate",
  "Contest problems must be unique": "contest.problemDuplicate",
  "Published contest problems cannot be changed": "contest.publishedLocked",
  "Published contest core fields cannot be changed": "contest.publishedLocked",
  "Published contest only allows description updates": "contest.publishedDescriptionOnly",
  "Confirmed contest blueprint problems cannot be changed": "contest.publishedDescriptionOnly",
  "Contest blueprint is not confirmed": "contest.blueprintNotConfirmed",
  "Contest title already exists": "contest.titleDuplicate",
  "Contest run title already exists": "contest.runTitleDuplicate",
  "Published contest run title already exists": "contest.runTitleDuplicate",
  "Archived contest cannot be managed": "contest.archivedCannotManage",
  "Only archived contests can be restored": "contest.restoreArchivedOnly",
  "Participant user is required": "contest.participantRequired",
  "Contest run participant limit reached": "contest.participantLimitReached",
  "Contest registration already exists": "contest.participantAlreadyExists",
  "Contest participant already exists": "contest.participantAlreadyExists",
  "Resolver requires a freeze time": "contest.resolverNoFreeze",
  "Contest run has no scoreboard freeze": "contest.publicScoreboardNoFreeze",
  "Only .zip testcase packages are supported": "testcase.zipOnly",
  "Testcase package manifest.json is required": "testcase.manifestRequired",
  "Testcase manifest cases are required": "testcase.manifestCasesRequired",
  "Not all testcase chunks have been uploaded": "testcase.chunksIncomplete",
  "Testcase package file name is required": "testcase.fileNameRequired",
  "Testcase package file size exceeds limit": "testcase.fileTooLarge",
  "Chunk size exceeds configured limit": "testcase.chunkTooLarge",
  "Testcase upload was interrupted. Please upload the package again.": "testcase.uploadInterrupted",
  "System error": "judge.systemError",
  "SYSTEM_ERROR": "judge.systemError"
};

const PREFIX_MESSAGE_KEYS: Array<[string, string]> = [
  ["Unsupported language:", "submission.unsupportedLanguage"],
  ["Testcase upload has failed:", "testcase.uploadFailed"],
  ["Unsafe testcase zip path:", "testcase.unsafePath"],
  ["Missing testcase upload chunk:", "testcase.missingChunk"],
  ["Uploaded testcase chunk is missing on disk:", "testcase.missingChunk"],
  ["Duplicate testcase zip entry:", "testcase.duplicateEntry"],
  ["Testcase zip entry is too large:", "testcase.entryTooLarge"],
  ["Provider returned HTTP 401", "ai.keyInvalid"],
  ["AI provider HTTP 401", "ai.keyInvalid"],
  ["Provider returned HTTP 429", "ai.rateLimited"],
  ["Provider returned HTTP 5", "ai.providerUnavailable"],
  ["AI provider HTTP 5", "ai.providerUnavailable"],
  ["AI provider request failed:", "ai.providerFailed"],
  ["AI provider call failed:", "ai.providerFailed"],
  ["Problem import failed:", "draft.importFailed"],
  ["Problem update failed:", "draft.importFailed"],
  ["Failed to fetch testcase blob:", "judge.testcaseUnavailable"],
  ["Failed to write testcase blob:", "judge.testcaseUnavailable"]
];

function readPath(source: unknown, key: string): unknown {
  const parts = key.split(".");
  let current: unknown = source;
  for (let i = 0; i < parts.length; i++) {
    if (!current || typeof current !== "object") return undefined;
    const record = current as Record<string, unknown>;
    // Keys are stored nested (errors.ai.contestCodeBlocked) and as flat literal keys
    // (errors['ai.contestProblemLeakBlocked']); check the literal remainder at each level.
    const literal = parts.slice(i).join(".");
    const flatValue = record[literal];
    if (typeof flatValue === "string") return flatValue;
    current = record[parts[i]];
  }
  return current;
}

function formatMessage(value: string, params?: Record<string, string | number> | null) {
  if (!params) return value;
  return value.replace(/\{(\w+)\}/g, (_, key: string) => String(params[key] ?? `{${key}}`));
}

function translate(locale: Locale, key: string, params?: Record<string, string | number> | null, fallback?: string): string | undefined {
  const localized = readPath(messages[locale], key);
  const fallbackValue = readPath(messages[DEFAULT_LOCALE], key);
  const value = typeof localized === "string"
    ? localized
    : typeof fallbackValue === "string"
      ? fallbackValue
      : fallback;
  return value ? formatMessage(value, params) : undefined;
}

export function resolveErrorMessageForLocale(
  locale: Locale,
  code: number,
  fallback: string,
  context: ErrorResolveContext = {}
): string {
  const params = {
    ...(context.errorParams ?? {}),
    traceId: context.traceId ?? ""
  };
  const explicitKey = context.errorKey || undefined;
  const fieldDetail = firstFieldDetail(context.details);
  if (explicitKey) {
    const localized = translate(locale, `errors.${explicitKey}`, params);
    if (localized) {
      return maybeAppendTrace(locale, localized, explicitKey, code, context.traceId);
    }
  }
  if (code === 40001 && context.details?.description && isLengthValidationDetail(context.details.description)) {
    const localized = translate(locale, "errors.contest.descriptionTooLong", params);
    if (localized) return localized;
  }
  if (fieldDetail && code === 40001) {
    const localized = translate(locale, "errors.request.validationWithDetail", { detail: fieldDetail });
    if (localized) return localized;
  }
  const exact = EXACT_MESSAGE_KEYS[fallback];
  if (exact) {
    const localized = translate(locale, `errors.${exact}`, params);
    if (localized) return maybeAppendTrace(locale, localized, exact, code, context.traceId);
  }
  const prefix = PREFIX_MESSAGE_KEYS.find(([prefixText]) => fallback.startsWith(prefixText));
  if (prefix) {
    const localized = translate(locale, `errors.${prefix[1]}`, params);
    if (localized) return maybeAppendTrace(locale, localized, prefix[1], code, context.traceId);
  }
  const key = semanticKey(fallback);
  if (key) {
    const localized = translate(locale, `errors.${key}`, params);
    if (localized) return maybeAppendTrace(locale, localized, key, code, context.traceId);
  }
  const codeMessage = translate(locale, `errors.${code}`, params);
  if (codeMessage) return maybeAppendTrace(locale, codeMessage, "", code, context.traceId);
  const unknown = translate(locale, "errors.unknown", params, fallback) ?? fallback;
  return maybeAppendTrace(locale, unknown, "", code, context.traceId);
}

export function resolveStoredErrorMessageForLocale(locale: Locale, value?: string | null, source?: "ai" | "judge" | "operation" | "testcase") {
  const clean = value?.trim();
  if (!clean) {
    return source === "judge"
      ? translate(locale, "errors.judge.systemError") ?? ""
      : translate(locale, "errors.unknown") ?? "";
  }
  const key = semanticKey(clean) || sourceDefaultKey(source);
  return resolveErrorMessageForLocale(locale, key ? 50000 : 0, clean, { errorKey: key });
}

function firstFieldDetail(details?: Record<string, string> | null) {
  if (!details) return null;
  const entry = Object.entries(details).find(([, value]) => Boolean(value?.trim()));
  return entry?.[1] ?? null;
}

function isLengthValidationDetail(value: string) {
  const normalized = value.toLowerCase();
  return (
    normalized.includes("2000") ||
    normalized.includes("长度") ||
    normalized.includes("超过") ||
    normalized.includes("too long") ||
    normalized.includes("size must be between") ||
    normalized.includes("length")
  );
}

function semanticKey(fallback: string): string | undefined {
  const normalized = fallback.trim().toLowerCase();
  if (!normalized) return undefined;
  if (
    normalized.includes("imported problem has been deleted") ||
    normalized.includes("linked problem has been deleted") ||
    normalized.includes("对应题目已被删除") ||
    normalized.includes("题目已被删除") ||
    normalized.includes("题目不存在或已被删除")
  ) {
    return "problem.importedDeleted";
  }
  if (normalized.includes("cannot read properties") || normalized.includes("undefined") || normalized.includes("typeerror")) {
    return "client.unexpected";
  }
  if (normalized.includes("request failed: 504") || normalized.includes("gateway time-out") || normalized.includes("timeout") || normalized.includes("timed out")) {
    return "request.timeout";
  }
  if (normalized.includes("request failed: 403") || normalized === "forbidden") return "auth.forbidden";
  if (normalized.includes("request failed: 401") || normalized === "unauthorized" || normalized.includes("login expired")) return "auth.required";
  if (normalized.includes("request failed: 404") || normalized === "not found") return "resource.notFound";
  if (normalized.includes("invalid authentication") || normalized.includes("authentication fails") || normalized.includes("api key") && normalized.includes("invalid")) {
    return "ai.keyInvalid";
  }
  if (normalized.includes("ai api key is not configured") || normalized.includes("api key is not configured")) return "ai.keyMissing";
  if (normalized.includes("ai model configuration is disabled")) return "ai.configDisabled";
  if (normalized.includes("rate limit") || normalized.includes("quota")) return "ai.rateLimited";
  if (normalized.includes("ai provider returned empty content")) return "ai.emptyContent";
  if (normalized.includes("比赛进行中不能请求该题的完整解题代码")) return "ai.contestCodeBlocked";
  if (normalized.includes("provider") && normalized.includes("could not be parsed")) return "ai.responseParseFailed";
  if (normalized.includes("ai stream failed")) return "ai.providerFailed";
  if (normalized.includes("streaming is not supported")) return "client.streamingUnsupported";
  if (normalized.includes("system-error") || normalized === "system_error" || normalized === "system error") return "judge.systemError";
  if (normalized.includes("sandbox") && normalized.includes("unavailable")) return "judge.sandboxUnavailable";
  if (normalized.includes("testcase") && normalized.includes("unavailable")) return "judge.testcaseUnavailable";
  if (normalized.includes("resolver requires a freeze time")) return "contest.resolverNoFreeze";
  if (normalized.includes("contest run has no scoreboard freeze")) return "contest.publicScoreboardNoFreeze";
  if (
    normalized.includes("time must be before") ||
    normalized.includes("freeze time") ||
    normalized.includes("start time") ||
    normalized.includes("end time") ||
    (normalized.includes("registration") && normalized.includes("time"))
  ) {
    return "request.timeRangeInvalid";
  }
  if (normalized.includes("too long")) return "request.tooLong";
  if (
    normalized.includes("must be positive") ||
    normalized.includes("must not be negative") ||
    normalized.includes("cannot be negative")
  ) {
    return "request.positiveRequired";
  }
  if (
    normalized.includes("does not belong") ||
    normalized.includes("do not belong") ||
    normalized.includes("not in this") ||
    normalized.includes("not part of")
  ) {
    return "request.relationInvalid";
  }
  if (
    normalized.includes("only draft") ||
    normalized.includes("only archived") ||
    normalized.includes("only published") ||
    normalized.includes("cannot be changed") ||
    normalized.includes("cannot be modified") ||
    normalized.includes("cannot be deleted") ||
    normalized.includes("cannot be archived") ||
    normalized.includes("cannot be restored")
  ) {
    return "request.lifecycleInvalid";
  }
  if (
    normalized.includes("not accepting") ||
    normalized.includes("not open") ||
    normalized.includes("not visible yet") ||
    normalized.includes("available after") ||
    normalized.includes("not available yet") ||
    normalized.includes("already ended")
  ) {
    return "request.stateUnavailable";
  }
  if (
    normalized.includes("requires") ||
    normalized.includes(" must ") ||
    normalized.startsWith("must ") ||
    normalized.includes("at least one") ||
    normalized.includes("has no") ||
    normalized.includes("no deterministic statistics")
  ) {
    return "request.precondition";
  }
  if (normalized.includes("invalid")) return "request.invalidOption";
  if (normalized.includes("unsupported")) return "request.unsupported";
  if (normalized.includes("required")) return "request.required";
  return undefined;
}

function sourceDefaultKey(source?: "ai" | "judge" | "operation" | "testcase") {
  if (source === "ai") return "ai.providerFailed";
  if (source === "judge") return "judge.systemError";
  if (source === "operation") return "operation.failed";
  if (source === "testcase") return "testcase.processFailed";
  return undefined;
}

function maybeAppendTrace(locale: Locale, message: string, key: string, code: number, traceId?: string | null) {
  if (!traceId || code < 50000 && !key.startsWith("system.") && !key.startsWith("service.")) {
    return message;
  }
  const suffix = translate(locale, "errors.traceSuffix", { traceId });
  return suffix ? `${message} ${suffix}` : message;
}
