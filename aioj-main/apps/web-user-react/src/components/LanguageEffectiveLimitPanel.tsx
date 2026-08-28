import type { ProblemLanguageTimeLimitMultipliers } from "@aioj/api-client";
import { useI18n } from "../lib/i18n";

const TIME_LIMIT_LANGUAGES = ["cpp", "python", "java"] as const;

export function LanguageEffectiveLimitPanel({
  baseTimeLimitMillis,
  multipliers
}: {
  baseTimeLimitMillis: number;
  multipliers?: ProblemLanguageTimeLimitMultipliers | null;
}) {
  const { t } = useI18n();

  return (
    <section className="mb-5 rounded-2xl border border-blue-100 bg-blue-50/60 p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.languageEffectiveLimitTitle")}</h3>
          <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("problems.languageEffectiveLimitCopy")}</p>
        </div>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {TIME_LIMIT_LANGUAGES.map((language) => {
          const multiplier = normalizeMultiplier(multipliers?.[language]);
          const timeLimitMillis = Math.ceil(baseTimeLimitMillis * multiplier);

          return (
            <span
              key={language}
              className="inline-flex max-w-full items-center gap-1 whitespace-nowrap rounded-full bg-white px-3 py-1.5 text-xs font-medium text-slate-700 ring-1 ring-inset ring-blue-100"
              title={`${t(`problems.languages.${language}`)} ${timeLimitMillis} ms`}
            >
              <span>{t(`problems.languages.${language}`)}</span>
              <span className="tabular-nums text-[var(--oj-ink)]">{timeLimitMillis} ms</span>
            </span>
          );
        })}
      </div>
    </section>
  );
}

function normalizeMultiplier(value?: number | null) {
  return typeof value === "number" && Number.isFinite(value) ? value : 1;
}
