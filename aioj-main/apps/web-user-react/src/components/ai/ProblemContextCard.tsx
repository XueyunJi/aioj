import type { AiProblemContextSummary } from "@aioj/api-client";
import { Badge, cn } from "@aioj/ui-react";
import { difficultyTone, formatMemory } from "../../lib/format";
import { useI18n } from "../../lib/i18n";

export function ProblemContextCard({
  problemContext,
  className
}: {
  problemContext: AiProblemContextSummary;
  className?: string;
}) {
  const { t } = useI18n();
  const tags = problemContext.tags ?? [];
  const constraints = problemContext.constraints ?? [];
  const showLimits = problemContext.timeLimitMillis || problemContext.memoryLimitKb;

  return (
    <section className={cn(
      "mb-3 rounded-xl border border-blue-100 bg-white/80 px-3 py-2.5 text-xs text-[var(--oj-ink-muted)]",
      className
    )}
      data-ai-selectable="true"
      data-ai-source-type="problem_context"
      data-ai-section-title={problemContext.title || problemContext.problemId || t("aiAssistant.problemContextTitleFallback")}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-semibold text-[var(--oj-ink)]">
          {problemContext.title || problemContext.problemId || t("aiAssistant.problemContextTitleFallback")}
        </span>
        {problemContext.difficulty ? (
          <Badge tone={difficultyTone(problemContext.difficulty)} className="h-5 px-2">
            {t(`difficulty.${problemContext.difficulty}`, undefined, String(problemContext.difficulty))}
          </Badge>
        ) : null}
        {showLimits ? (
          <span className="text-[var(--oj-ink-soft)]">
            {problemContext.timeLimitMillis ? `${problemContext.timeLimitMillis} ms` : ""}
            {problemContext.timeLimitMillis && problemContext.memoryLimitKb ? " / " : ""}
            {problemContext.memoryLimitKb ? formatMemory(problemContext.memoryLimitKb) : ""}
          </span>
        ) : null}
      </div>

      {tags.length ? (
        <div className="mt-2 flex min-w-0 flex-wrap gap-1.5" aria-label={t("common.tags")}>
          {tags.slice(0, 6).map((tag) => (
            <Badge key={tag} tone="neutral" className="h-5 max-w-full px-2">
              <span className="truncate">{tag}</span>
            </Badge>
          ))}
        </div>
      ) : null}

      {constraints.length ? (
        <div className="mt-2 space-y-1">
          <p className="font-medium text-[var(--oj-ink)]">{t("aiAssistant.problemContextConstraints")}</p>
          <ul className="space-y-1">
            {constraints.slice(0, 3).map((constraint) => (
              <li key={constraint} className="line-clamp-2 leading-5">
                {constraint}
              </li>
            ))}
          </ul>
        </div>
      ) : problemContext.statementSummary ? (
        <p className="mt-2 line-clamp-2 leading-5">{problemContext.statementSummary}</p>
      ) : null}
    </section>
  );
}
