import { i18n } from './locale';
import { resolveErrorMessageForLocale, type ErrorResolveContext } from './errorFeedback';
import type { Locale } from './messages';

export function resolveApiErrorMessage(code: number, fallback: string, context?: ErrorResolveContext): string | undefined {
  return resolveErrorMessageForLocale(i18n.global.locale.value as Locale, code, fallback, context);
}
