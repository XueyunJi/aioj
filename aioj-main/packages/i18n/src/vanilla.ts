import { messages, type Locale } from "./messages";
import {
  resolveErrorMessageForLocale,
  resolveStoredErrorMessageForLocale,
  type ErrorResolveContext
} from "./errorFeedback";

export { resolveStoredErrorMessageForLocale };
export type { ErrorResolveContext };

export type { Locale } from "./messages";

export const LOCALE_STORAGE_KEY = "aioj-locale";
export const DEFAULT_LOCALE: Locale = "zh-CN";

export const localeOptions: Array<{ value: Locale; label: string }> = [
  { value: "zh-CN", label: "中文" },
  { value: "en-US", label: "English" }
];

export function isLocale(value: string | null | undefined): value is Locale {
  return value === "zh-CN" || value === "en-US";
}

export function getStoredLocale(): Locale {
  if (typeof localStorage === "undefined") return DEFAULT_LOCALE;
  const value = localStorage.getItem(LOCALE_STORAGE_KEY);
  return isLocale(value) ? value : DEFAULT_LOCALE;
}

export function setStoredLocale(locale: Locale) {
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale);
  }
  if (typeof document !== "undefined") {
    document.documentElement.lang = locale;
  }
}

function readPath(source: unknown, key: string): unknown {
  return key.split(".").reduce<unknown>((current, part) => {
    if (!current || typeof current !== "object") return undefined;
    return (current as Record<string, unknown>)[part];
  }, source);
}

function formatMessage(value: string, params?: Record<string, string | number>) {
  if (!params) return value;
  return value.replace(/\{(\w+)\}/g, (_, key: string) => String(params[key] ?? `{${key}}`));
}

export function translate(
  locale: Locale,
  key: string,
  params?: Record<string, string | number>,
  fallback?: string
): string {
  const localized = readPath(messages[locale], key);
  const fallbackValue = readPath(messages[DEFAULT_LOCALE], key);
  const value = typeof localized === "string"
    ? localized
    : typeof fallbackValue === "string"
      ? fallbackValue
      : fallback;
  return value ? formatMessage(value, params) : key;
}

export function translateList(locale: Locale, key: string): string[] {
  const localized = readPath(messages[locale], key);
  const fallbackValue = readPath(messages[DEFAULT_LOCALE], key);
  if (Array.isArray(localized)) return localized.filter((item): item is string => typeof item === "string");
  if (Array.isArray(fallbackValue)) return fallbackValue.filter((item): item is string => typeof item === "string");
  return [];
}

export function resolveApiErrorMessageForLocale(
  code: number,
  fallback: string,
  locale = getStoredLocale(),
  context?: ErrorResolveContext
) {
  return resolveErrorMessageForLocale(locale, code, fallback, context);
}
