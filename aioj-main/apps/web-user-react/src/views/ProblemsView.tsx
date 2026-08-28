import * as React from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { FilterX, Search } from "lucide-react";
import { api, type Difficulty } from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PageSection, inputClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { difficultyTone } from "../lib/format";

const difficulties: Array<Difficulty | ""> = ["", "EASY", "MEDIUM", "HARD", "CHALLENGE"];

export function ProblemsView() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { keyword?: string; difficulty?: Difficulty | ""; tag?: string; page?: number };
  const [keyword, setKeyword] = React.useState(search.keyword ?? "");
  const [tag, setTag] = React.useState(search.tag ?? "");
  const difficulty = search.difficulty ?? "";
  const page = Number(search.page ?? 1);

  const problemsQuery = useQuery({
    queryKey: ["problems", { page, keyword: search.keyword ?? "", difficulty, tag: search.tag ?? "" }],
    queryFn: () => api.problems({
      page,
      pageSize: 12,
      keyword: search.keyword,
      difficulty,
      tag: search.tag
    })
  });

  function updateSearch(next: Partial<typeof search>) {
    void navigate({
      to: "/problems",
      search: {
        ...search,
        ...next,
        page: next.page ?? 1
      }
    });
  }

  function applyFilters(event: React.FormEvent) {
    event.preventDefault();
    updateSearch({ keyword: keyword.trim() || undefined, tag: tag.trim() || undefined });
  }

  const total = problemsQuery.data?.total ?? 0;
  const maxPage = Math.max(1, Math.ceil(total / 12));

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection
        eyebrow={t("problems.eyebrow")}
        title={t("problems.title")}
        description={t("problems.subtitle", { count: total })}
      />

      <form onSubmit={applyFilters} className="grid gap-3 rounded-2xl border border-[var(--oj-border)] bg-white p-4 lg:grid-cols-[minmax(240px,1fr)_220px_180px_auto]">
        <label className="relative block">
          <span className="sr-only">{t("problems.searchPlaceholder")}</span>
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--oj-ink-soft)]" aria-hidden="true" />
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            className={`${inputClass} pl-9`}
            placeholder={t("problems.searchPlaceholder")}
          />
        </label>
        <select
          value={difficulty}
          onChange={(event) => updateSearch({ difficulty: event.target.value as Difficulty | "" })}
          className={inputClass}
          aria-label={t("common.difficulty")}
        >
          {difficulties.map((item) => (
            <option key={item || "all"} value={item}>{item ? t(`difficulty.${item}`) : t("problems.allDifficulties")}</option>
          ))}
        </select>
        <input
          value={tag}
          onChange={(event) => setTag(event.target.value)}
          className={inputClass}
          placeholder={t("problems.tagPlaceholder")}
        />
        <div className="flex gap-2">
          <Button>{t("common.apply")}</Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setKeyword("");
              setTag("");
              void navigate({ to: "/problems", search: {} });
            }}
          >
            <FilterX className="size-4" aria-hidden="true" />
            {t("problems.resetFilters")}
          </Button>
        </div>
      </form>

      {problemsQuery.isLoading ? (
        <LoadingPanel label={t("problems.loading")} />
      ) : problemsQuery.isError ? (
        <ErrorPanel title={t("problems.loadFailed")} />
      ) : problemsQuery.data?.records.length ? (
        <>
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {problemsQuery.data.records.map((problem) => (
              <Link
                key={problem.id}
                to="/problems/$problemId"
                params={{ problemId: problem.id }}
                className="group flex min-h-52 flex-col justify-between rounded-2xl border border-[var(--oj-border)] bg-white p-5 outline-none transition-colors hover:border-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
              >
                <div>
                  <div className="mb-3 flex flex-wrap gap-2">
                    <Badge tone={difficultyTone(problem.difficulty)}>{t(`difficulty.${problem.difficulty}`)}</Badge>
                    {problem.aiGenerated ? <Badge tone="blue">{t("aiAssistant.title")}</Badge> : null}
                  </div>
                  <h2 className="line-clamp-2 text-balance text-lg font-semibold text-[var(--oj-ink)]">{problem.title}</h2>
                  <p className="mt-3 line-clamp-3 text-sm leading-6 text-[var(--oj-ink-muted)]">
                    {problem.notes || t("problems.openDetailFallback")}
                  </p>
                </div>
                <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
                  <div className="flex flex-wrap gap-2">
                    {problem.tags.slice(0, 3).map((tagValue) => <Badge key={tagValue}>{tagValue}</Badge>)}
                  </div>
                  <span className="text-sm font-medium text-[var(--oj-primary)]">{t("problems.startPractice")}</span>
                </div>
              </Link>
            ))}
          </section>
          <div className="flex items-center justify-end gap-2">
            <Button variant="outline" disabled={page <= 1} onClick={() => updateSearch({ page: page - 1 })}>{t("common.previous")}</Button>
            <span className="text-sm tabular-nums text-[var(--oj-ink-muted)]">{page}/{maxPage}</span>
            <Button variant="outline" disabled={page >= maxPage} onClick={() => updateSearch({ page: page + 1 })}>{t("common.next")}</Button>
          </div>
        </>
      ) : (
        <EmptyState title={t("problems.noResultsTitle")} description={t("problems.noResultsDescription")} />
      )}
    </div>
  );
}
