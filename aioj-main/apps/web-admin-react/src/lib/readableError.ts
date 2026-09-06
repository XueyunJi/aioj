import { ApiError, type SubmissionStatus } from "@aioj/api-client";
import { resolveStoredErrorMessageForLocale, type Locale } from "@aioj/i18n/vanilla";

type StoredErrorSource = "ai" | "judge" | "operation" | "testcase";

export function readableCaughtError(caught: unknown, locale: Locale, fallback: string) {
  if (caught instanceof ApiError) {
    return caught.userMessage;
  }
  if (caught instanceof Error) {
    return resolveStoredErrorMessageForLocale(locale, caught.message);
  }
  return fallback;
}

export function readableStoredError(
  value: string | null | undefined,
  locale: Locale,
  fallback: string,
  source?: StoredErrorSource
) {
  if (!value?.trim()) {
    return fallback;
  }
  return resolveStoredErrorMessageForLocale(locale, value, source);
}

export function readableJudgeMessage(
  value: string | null | undefined,
  status: SubmissionStatus,
  locale: Locale,
  fallbackStatus: string
) {
  if (status === "SYSTEM_ERROR") {
    return resolveStoredErrorMessageForLocale(locale, value, "judge");
  }
  if (!value?.trim()) {
    return fallbackStatus;
  }
  if (locale === "zh-CN") {
    return localizeJudgeDetail(value);
  }
  return value;
}

function localizeJudgeDetail(value: string) {
  return value
    .replace(/^Compile phase:/, "编译阶段：")
    .replace(/^Run phase:/, "运行阶段：")
    .replace(/^Checker phase:/, "校验器阶段：")
    .replace("Memory Limit Exceeded", "内存超限")
    .replace("Time Limit Exceeded", "时间超限")
    .replace("Output Limit Exceeded", "输出超限")
    .replace("SIGSEGV", "段错误 SIGSEGV")
    .replace("SIGFPE", "算术异常 SIGFPE")
    .replace("SIGABRT", "程序中止 SIGABRT")
    .replace("SIGKILL", "被强制终止 SIGKILL")
    .replace("SIGPIPE", "管道错误 SIGPIPE")
    .replace("SIGTERM", "被终止 SIGTERM");
}
