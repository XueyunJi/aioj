import * as React from "react";
import {
  getStoredLocale,
  localeOptions,
  setStoredLocale,
  translate,
  translateList,
  type Locale
} from "@aioj/i18n/vanilla";

interface I18nContextValue {
  locale: Locale;
  localeOptions: Array<{ value: Locale; label: string }>;
  setLocale: (locale: Locale) => void;
  t: (key: string, params?: Record<string, string | number>, fallback?: string) => string;
  list: (key: string) => string[];
}

const I18nContext = React.createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [locale, setLocaleState] = React.useState<Locale>(() => getStoredLocale());

  React.useEffect(() => {
    setStoredLocale(locale);
  }, [locale]);

  const value = React.useMemo<I18nContextValue>(() => ({
    locale,
    localeOptions,
    setLocale: setLocaleState,
    t: (key, params, fallback) => translate(locale, key, params, fallback),
    list: (key) => translateList(locale, key)
  }), [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const value = React.useContext(I18nContext);
  if (!value) throw new Error("useI18n must be used inside I18nProvider");
  return value;
}
