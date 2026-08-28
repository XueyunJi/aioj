import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { activeQueryRefetchInterval, api, type ProblemDraftGenerationJobResponse, type ProblemDraftGenerationJobStatus } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PaginationRow, TableShell, selectClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { formatDateTime, shortId } from "../lib/format";

const PAGE_SIZE = 20;
const REFERENCE_CHECK_DISABLED_FOR_HIGH_RATING = "REFERENCE_CHECK_DISABLED_FOR_HIGH_RATING";
type StatusFilter = "" | ProblemDraftGenerationJobStatus;
type TFunction = ReturnType<typeof useI18n>["t"];

export function AiDraftJobsPanel({ onOpenDraft }: { onOpenDraft?: (draftId: NonNullable<ProblemDraftGenerationJobResponse["draftId"]>) => void }) {
  const { t } = useI18n();
  const [page, setPage] = React.useState(1);
  const [status, setStatus] = React.useState<StatusFilter>("");
  const [now, setNow] = React.useState(() => Date.now());

  const jobsQuery = useQuery({
    queryKey: ["admin-ai-draft-generation-jobs", page, status],
    queryFn: () => api.problemDraftGenerationJobs({
      page,
      pageSize: PAGE_SIZE,
      status: status || undefined
    }),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) =>
      Boolean(data?.records.some((job) => isActiveJob(job))), {
        fastMs: 2000,
        slowMs: 5000,
        hiddenMs: 10000
      })
  });

  const records = jobsQuery.data?.records ?? [];
  const total = jobsQuery.data?.total ?? 0;

  React.useEffect(() => {
    if (!jobsQuery.data) return;
    const totalPages = Math.max(1, Math.ceil(jobsQuery.data.total / PAGE_SIZE));
    if (page > totalPages) setPage(totalPages);
  }, [jobsQuery.data, page]);

  const hasActiveJob = records.some((job) => isActiveJob(job));
  React.useEffect(() => {
    if (!hasActiveJob) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [hasActiveJob]);

  return (
    <div className="space-y-4">
      <section className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <h2 className="text-sm font-semibold text-[var(--oj-ink)]">{t("draftJobs.listTitle")}</h2>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("draftJobs.listHint")}</p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <select
            className={`${selectClass} w-full sm:w-48`}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as StatusFilter);
              setPage(1);
            }}
            aria-label={t("draftJobs.statusFilter")}
          >
            <option value="">{t("draftJobs.statusAll")}</option>
            <option value="QUEUED">{t("draftJobs.status.QUEUED")}</option>
            <option value="RUNNING">{t("draftJobs.status.RUNNING")}</option>
            <option value="SUCCEEDED">{t("draftJobs.status.SUCCEEDED")}</option>
            <option value="FAILED">{t("draftJobs.status.FAILED")}</option>
            <option value="CANCELLED">{t("draftJobs.status.CANCELLED")}</option>
          </select>
          <Button variant="outline" disabled={jobsQuery.isFetching} onClick={() => void jobsQuery.refetch()}>
            <RefreshCw className={cn("size-4", jobsQuery.isFetching && "animate-spin")} aria-hidden="true" />
            {t("common.refresh")}
          </Button>
        </div>
      </section>

      {jobsQuery.isLoading ? (
        <LoadingPanel label={t("draftJobs.loading")} />
      ) : jobsQuery.isError ? (
        <ErrorPanel title={t("draftJobs.loadFailed")} action={<Button variant="outline" onClick={() => void jobsQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : records.length ? (
        <div className="space-y-3">
          <TableShell>
            <table className="w-full min-w-[980px] text-sm">
              <colgroup>
                <col className="w-[28%]" />
                <col className="w-[14%]" />
                <col className="w-[22%]" />
                <col className="w-[18%]" />
                <col className="w-[18%]" />
              </colgroup>
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="px-4 py-3 text-left">{t("draftJobs.topic")}</th>
                  <th className="px-4 py-3 text-left">{t("common.status")}</th>
                  <th className="px-4 py-3 text-left">{t("draftJobs.progress")}</th>
                  <th className="px-4 py-3 text-left">{t("draftJobs.updatedAt")}</th>
                  <th className="px-4 py-3 text-left">{t("draftJobs.note")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {records.map((job) => {
                  const canOpen = Boolean(job.draftId && job.status === "SUCCEEDED");
                  const note = jobNote(job, t);
                  return (
                    <tr
                      key={job.id}
                      className={cn(
                        "align-top transition-colors hover:bg-[var(--oj-surface-muted)]",
                        canOpen && "cursor-pointer focus-within:bg-[var(--oj-surface-muted)]"
                      )}
                      role={canOpen ? "button" : undefined}
                      tabIndex={canOpen ? 0 : undefined}
                      onClick={() => {
                        if (canOpen && job.draftId) onOpenDraft?.(job.draftId);
                      }}
                      onKeyDown={(event) => {
                        if (!canOpen || !job.draftId) return;
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          onOpenDraft?.(job.draftId);
                        }
                      }}
                    >
                      <td className="px-4 py-4">
                        <strong className="block truncate text-[var(--oj-ink)]">{job.topicSnapshot || t("draftJobs.topicEmpty")}</strong>
                        <span className="mt-1 block text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(job.id)}</span>
                        {job.sourceDraftId ? (
                          <span className="mt-1 block text-xs tabular-nums text-[var(--oj-ink-muted)]">
                            {t("draftJobs.sourceDraft", { id: shortId(job.sourceDraftId) })}
                          </span>
                        ) : null}
                      </td>
                      <td className="px-4 py-4">
                        <div className="flex flex-wrap gap-1.5">
                          <Badge tone={statusTone(job.status)}>{jobStatusLabel(job.status, t)}</Badge>
                          <Badge tone="neutral">{jobStageLabel(job.stage, t)}</Badge>
                          <Badge tone="neutral">{jobTypeLabel(job, t)}</Badge>
                        </div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="space-y-2">
                          <div className="flex items-center justify-between gap-3 text-xs tabular-nums text-[var(--oj-ink-muted)]">
                            <span>{progressLabel(job, t)}</span>
                            <span>{durationLabel(job, t, now)}</span>
                          </div>
                          <div className="h-1.5 overflow-hidden rounded-full bg-[var(--oj-surface-muted)]">
                            <div className={cn("h-full rounded-full", isActiveJob(job) ? "bg-[var(--oj-primary)]" : "bg-slate-400")} style={{ width: `${progressPercent(job)}%` }} />
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-4">
                        <span className="block whitespace-nowrap text-sm text-[var(--oj-ink)]">{formatDateTime(job.updatedAt)}</span>
                        <span className="mt-1 block whitespace-nowrap text-xs text-[var(--oj-ink-muted)]">{completedLabel(job, t)}</span>
                      </td>
                      <td className="px-4 py-4">
                        <p
                          className={cn(
                            "line-clamp-2 max-w-[24rem] break-words text-sm leading-6",
                            job.status === "FAILED" ? "text-red-700" : "text-[var(--oj-ink-muted)]"
                          )}
                          title={note}
                        >
                          {note}
                        </p>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableShell>
          <PaginationRow
            page={page}
            total={total}
            pageSize={PAGE_SIZE}
            onPageChange={setPage}
            previousLabel={t("common.previous")}
            nextLabel={t("common.next")}
            disabled={jobsQuery.isFetching}
          />
        </div>
      ) : (
        <EmptyState title={t("draftJobs.emptyTitle")} description={t("draftJobs.emptyDescription")} />
      )}
    </div>
  );
}

function isActiveJob(job: ProblemDraftGenerationJobResponse) {
  return job.status === "QUEUED" || job.status === "RUNNING";
}

function progressPercent(job: ProblemDraftGenerationJobResponse) {
  if (job.status === "SUCCEEDED") return 100;
  const total = Math.max(1, job.progressTotal || 1);
  const current = Math.min(total, Math.max(0, job.progressCurrent || 0));
  return Math.min(100, Math.max(0, Math.round((current / total) * 100)));
}

function statusTone(status: string): "blue" | "green" | "amber" | "red" | "neutral" {
  if (status === "SUCCEEDED") return "green";
  if (status === "FAILED") return "red";
  if (status === "RUNNING") return "blue";
  if (status === "QUEUED") return "amber";
  return "neutral";
}

function jobStatusLabel(status: string, t: TFunction) {
  return t(`draftJobs.status.${status}`, undefined, t("draftJobs.status.UNKNOWN"));
}

function jobStageLabel(stage: string, t: TFunction) {
  return t(`draftJobs.stage.${stage}`, undefined, t("draftJobs.stage.UNKNOWN"));
}

function jobTypeLabel(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  return isRegenerationJob(job) ? t("draftJobs.type.REGENERATE") : t("draftJobs.type.GENERATE");
}

function isRegenerationJob(job: ProblemDraftGenerationJobResponse) {
  return job.jobType === "REGENERATE" || Boolean(job.sourceDraftId);
}

function jobNote(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  if (job.status === "FAILED") {
    return job.errorMessage || t("draftJobs.errorFallback");
  }
  if (job.status === "SUCCEEDED" && job.draftId) {
    if (isRegenerationJob(job)) {
      return t("draftJobs.rewriteCreated", { id: shortId(job.draftId) });
    }
    if (job.progressMessage === REFERENCE_CHECK_DISABLED_FOR_HIGH_RATING) {
      return t("draftJobs.draftCreatedReferenceRisk", { id: shortId(job.draftId) });
    }
    return t("draftJobs.draftCreated", { id: shortId(job.draftId) });
  }
  return jobStageNote(job.stage, t);
}

function progressLabel(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  return t("draftJobs.progressPercent", { percent: progressPercent(job) });
}

function durationLabel(job: ProblemDraftGenerationJobResponse, t: TFunction, now: number) {
  const start = job.startedAt ? Date.parse(job.startedAt) : Date.parse(job.createdAt);
  const end = job.completedAt ? Date.parse(job.completedAt) : now;
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
    return t("draftJobs.durationUnknown");
  }
  return t("draftJobs.durationSeconds", { seconds: Math.max(0, Math.round((end - start) / 1000)) });
}

function jobStageNote(stage: string, t: TFunction) {
  return t(`draftJobs.stageNote.${stage}`, undefined, t("draftJobs.noNote"));
}

function completedLabel(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  if (!job.completedAt) {
    return isActiveJob(job) ? t("draftJobs.notCompleted") : t("draftJobs.notStarted");
  }
  return t("draftJobs.completedAt", { time: formatDateTime(job.completedAt) });
}
