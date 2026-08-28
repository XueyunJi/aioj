import * as React from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { Eye } from "lucide-react";
import { activeQueryRefetchInterval, api, type EntityId, type SubmissionScope, type SubmissionStatus } from "@aioj/api-client";
import { Button, cn } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PageSection, inputClass } from "../components/Common";
import { SubmissionDetailDialog } from "../components/SubmissionDetailDialog";
import { hasLiveSubmissions, SubmissionStatusBadge } from "../components/SubmissionStatusBadge";
import { useI18n } from "../lib/i18n";
import { formatDateTime } from "../lib/format";

const statuses: Array<SubmissionStatus | ""> = [
  "",
  "QUEUED",
  "RUNNING",
  "ACCEPTED",
  "WRONG_ANSWER",
  "COMPILE_ERROR",
  "RUNTIME_ERROR",
  "TIME_LIMIT_EXCEEDED",
  "MEMORY_LIMIT_EXCEEDED",
  "OUTPUT_LIMIT_EXCEEDED",
  "SYSTEM_ERROR"
];

const languages = ["", "cpp", "python", "java"] as const;

export function SubmissionsView() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as {
    status?: SubmissionStatus | "";
    problemId?: EntityId;
    contestId?: EntityId;
    contestRunId?: EntityId;
    contestProblemId?: EntityId;
    language?: string;
    scope?: SubmissionScope;
    page?: number;
  };
  const page = Number(search.page ?? 1);
  const scope: SubmissionScope = search.scope === "CONTEST" ? "CONTEST" : "PRACTICE";
  const [selectedId, setSelectedId] = React.useState<EntityId | null>(null);

  const submissionsQuery = useQuery({
    queryKey: ["submissions", "mine", search],
    queryFn: () => api.mySubmissions({
      page,
      pageSize: 12,
      status: search.status ?? "",
      problemId: search.problemId,
      contestId: search.contestId,
      contestRunId: search.contestRunId,
      contestProblemId: search.contestProblemId,
      language: search.language ?? "",
      scope
    }),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => hasLiveSubmissions(data?.records))
  });

  const total = submissionsQuery.data?.total ?? 0;
  const maxPage = Math.max(1, Math.ceil(total / 12));

  function updateSearch(next: Partial<typeof search>) {
    void navigate({ to: "/submissions", search: { ...search, ...next, page: next.page ?? 1 } });
  }

  function updateScope(nextScope: SubmissionScope) {
    const next: Partial<typeof search> = { scope: nextScope, page: 1 };
    if (nextScope === "PRACTICE") {
      next.contestId = undefined;
      next.contestRunId = undefined;
      next.contestProblemId = undefined;
    }
    void navigate({ to: "/submissions", search: { ...search, ...next } });
  }

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection eyebrow={t("submissions.eyebrow")} title={t("submissions.title")} description={t("submissions.description")} />

      <section className="flex flex-wrap items-center gap-3 rounded-2xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex w-full rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-1 sm:w-fit" role="group" aria-label={t("submissions.eyebrow")}>
          {(["PRACTICE", "CONTEST"] as const).map((item) => (
            <button
              key={item}
              type="button"
              className={cn(
                "h-9 flex-1 whitespace-nowrap rounded-lg px-3 text-sm font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] sm:flex-none",
                scope === item ? "bg-white text-[var(--oj-primary)] shadow-sm" : "text-[var(--oj-ink-muted)] hover:text-[var(--oj-ink)]"
              )}
              onClick={() => updateScope(item)}
            >
              {item === "PRACTICE" ? t("submissions.practiceScope") : t("submissions.contestScope")}
            </button>
          ))}
        </div>
        <select
          value={search.status ?? ""}
          onChange={(event) => updateSearch({ status: event.target.value as SubmissionStatus | "" })}
          className={cn(inputClass, "w-full sm:w-52")}
          aria-label={t("submissions.allStatuses")}
        >
          {statuses.map((status) => <option key={status || "all"} value={status}>{status ? t(`submissionStatus.${status}`) : t("submissions.allStatuses")}</option>)}
        </select>
        <select
          value={search.language ?? ""}
          onChange={(event) => updateSearch({ language: event.target.value || undefined })}
          className={cn(inputClass, "w-full sm:w-44")}
          aria-label={t("common.language")}
        >
          {languages.map((language) => (
            <option key={language || "all"} value={language}>
              {language ? t(`submissions.languages.${language}`) : t("submissions.allLanguages")}
            </option>
          ))}
        </select>
        <input
          value={search.problemId ?? ""}
          onChange={(event) => updateSearch({ problemId: event.target.value || undefined })}
          className={cn(inputClass, "w-full sm:w-64")}
          placeholder={t("submissions.keywordPlaceholder")}
        />
        <Button
          type="button"
          variant="outline"
          className="w-full sm:w-fit"
          onClick={() => void navigate({ to: "/submissions", search: { scope } })}
        >
          {t("submissions.resetFilters")}
        </Button>
      </section>
      <p className="text-sm text-[var(--oj-ink-muted)]">
        {scope === "PRACTICE" ? t("submissions.practiceScopeDescription") : t("submissions.contestScopeDescription")}
      </p>

      {submissionsQuery.isLoading ? (
        <LoadingPanel label={t("submissions.loading")} />
      ) : submissionsQuery.isError ? (
        <ErrorPanel title={t("submissions.loadFailed")} />
      ) : submissionsQuery.data?.records.length ? (
        <section className="overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[780px] table-fixed text-sm">
              <colgroup>
                <col className="w-[20%]" />
                <col className="w-[13%]" />
                <col className="w-[16%]" />
                <col className="w-[13%]" />
                <col className="w-[24%]" />
                <col className="w-[14%]" />
              </colgroup>
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="px-4 py-3 text-center">{t("submissions.problem")}</th>
                  <th className="px-4 py-3 text-left">{t("common.language")}</th>
                  <th className="px-4 py-3 text-left">{t("common.status")}</th>
                  <th className="px-4 py-3 text-left">{t("submissions.viewTimeLabel")}</th>
                  <th className="px-4 py-3 text-center">{t("common.created")}</th>
                  <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {submissionsQuery.data.records.map((submission) => (
                  <tr key={submission.id} className="align-middle">
                    <td className="px-4 py-4 text-center">
                      <Link to="/problems/$problemId" params={{ problemId: submission.problemId }} className="inline-block max-w-full truncate font-medium tabular-nums text-[var(--oj-primary)]">
                        #{submission.problemId}
                      </Link>
                    </td>
                    <td className="px-4 py-4 text-left text-[var(--oj-ink)]">{submission.language}</td>
                    <td className="px-4 py-4 text-left">
                      <SubmissionStatusBadge status={submission.status} label={t(`submissionStatus.${submission.status}`)} />
                    </td>
                    <td className="px-4 py-4 text-left tabular-nums text-[var(--oj-ink-muted)]">{submission.timeMillis ?? "--"} ms</td>
                    <td className="px-4 py-4 text-center tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(submission.createdAt)}</td>
                    <td className="px-4 py-4 text-center">
                      <Button className="mx-auto w-fit" size="sm" variant="outline" onClick={() => setSelectedId(submission.id)}>
                        <Eye className="size-4" aria-hidden="true" />
                        {t("submissions.view")}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-end gap-2 border-t border-[var(--oj-border-soft)] px-4 py-3">
            <Button variant="outline" disabled={page <= 1} onClick={() => updateSearch({ page: page - 1 })}>{t("common.previous")}</Button>
            <span className="text-sm tabular-nums text-[var(--oj-ink-muted)]">{page}/{maxPage}</span>
            <Button variant="outline" disabled={page >= maxPage} onClick={() => updateSearch({ page: page + 1 })}>{t("common.next")}</Button>
          </div>
        </section>
      ) : (
        <EmptyState title={t("submissions.emptyTitle")} description={t("submissions.emptyDescription")} actionLabel={t("submissions.goPractice")} />
      )}

      <SubmissionDetailDialog submissionId={selectedId} onOpenChange={(open) => !open && setSelectedId(null)} />
    </div>
  );
}
