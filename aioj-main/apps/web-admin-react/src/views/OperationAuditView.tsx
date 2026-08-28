import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Download, RefreshCw, RotateCcw } from "lucide-react";
import { api, type OperationAuditEventResponse, type OperationJobResponse, type OperationJobStatus, type OperationJobType } from "@aioj/api-client";
import { Badge, Button, Card, CardBody } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PageHeader, TableShell, selectClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { downloadOperationJobArtifact } from "../lib/operationJobDownloads";
import { readableStoredError } from "../lib/readableError";

type TabKey = "jobs" | "audit";
type TFunction = ReturnType<typeof useI18n>["t"];
const OPERATION_JOB_TYPES: OperationJobType[] = [
  "EXPORT_SCOREBOARD",
  "EXPORT_SUBMISSIONS",
  "EXPORT_PLAGIARISM_REPORT",
  "RUN_PLAGIARISM_CHECK",
  "GENERATE_SCOREBOARD_TIMELINE",
  "GENERATE_CONTEST_POSTMORTEM",
  "GENERATE_STUDENT_POSTMORTEM",
  "BATCH_GENERATE_STUDENT_POSTMORTEMS"
];

export function OperationAuditView() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = React.useState<TabKey>("jobs");
  const [jobPage, setJobPage] = React.useState(1);
  const [auditPage, setAuditPage] = React.useState(1);
  const [status, setStatus] = React.useState<OperationJobStatus | "">("");
  const [type, setType] = React.useState<OperationJobType | "">("");
  const [selectedJob, setSelectedJob] = React.useState<OperationJobResponse | null>(null);
  const [selectedAuditEvent, setSelectedAuditEvent] = React.useState<OperationAuditEventResponse | null>(null);
  const [downloadError, setDownloadError] = React.useState<string | null>(null);

  const jobsQuery = useQuery({
    queryKey: ["admin-operation-jobs", jobPage, status, type],
    queryFn: () => api.operationJobs({ page: jobPage, pageSize: 20, status, type })
  });
  const auditQuery = useQuery({
    queryKey: ["admin-operation-audit-events", auditPage],
    queryFn: () => api.operationAuditEvents({ page: auditPage, pageSize: 20 })
  });

  const retryMutation = useMutation({
    mutationFn: (jobId: string) => api.retryOperationJob(jobId),
    onSuccess: async () => {
      toast.success(t("operations.retryQueuedMessage"));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["admin-operation-audit-events"] })
      ]);
    },
    onError: (caught) => {
      toast.error(caught instanceof Error ? caught.message : t("common.errorFallback"));
    }
  });

  const jobs = jobsQuery.data?.records ?? [];
  const auditEvents = auditQuery.data?.records ?? [];
  const jobTotalPages = Math.max(1, Math.ceil((jobsQuery.data?.total ?? 0) / (jobsQuery.data?.pageSize ?? 20)));
  const auditTotalPages = Math.max(1, Math.ceil((auditQuery.data?.total ?? 0) / (auditQuery.data?.pageSize ?? 20)));

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("operations.title")}
        description={t("operations.subtitle")}
        actions={(
          <Button
            variant="outline"
            disabled={jobsQuery.isFetching || auditQuery.isFetching}
            onClick={() => {
              void jobsQuery.refetch();
              void auditQuery.refetch();
            }}
          >
            <RefreshCw className="size-4" aria-hidden="true" />
            {t("common.refresh")}
          </Button>
        )}
      />

      <div className="flex flex-wrap gap-2">
        {(["jobs", "audit"] as const).map((item) => (
          <button
            key={item}
            type="button"
            onClick={() => setTab(item)}
            className={`h-10 rounded-lg px-4 text-sm font-medium transition-colors ${
              tab === item ? "bg-[var(--oj-primary)] text-white" : "border border-[var(--oj-border)] bg-white text-[var(--oj-ink)] hover:bg-[var(--oj-surface-muted)]"
            }`}
          >
            {t(`operations.tabs.${item}`)}
          </button>
        ))}
      </div>

      {tab === "jobs" ? (
        <Card className="rounded-xl shadow-none">
          <CardBody className="space-y-4">
            <section className="flex flex-wrap items-center gap-3">
              <select className={`${selectClass} w-full sm:w-48`} value={status} onChange={(event) => { setStatus(event.target.value as OperationJobStatus | ""); setJobPage(1); }}>
                <option value="">{t("operations.filters.allStatuses")}</option>
                {(["QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"] as const).map((item) => (
                  <option key={item} value={item}>{t(`operations.status.${item}`)}</option>
                ))}
              </select>
              <select className={`${selectClass} w-full sm:w-64`} value={type} onChange={(event) => { setType(event.target.value as OperationJobType | ""); setJobPage(1); }}>
                <option value="">{t("operations.filters.allTypes")}</option>
                {OPERATION_JOB_TYPES.map((item) => (
                  <option key={item} value={item}>{t(`operations.type.${item}`)}</option>
                ))}
              </select>
            </section>
            {downloadError ? <ErrorPanel title={downloadError} /> : null}

            {jobsQuery.isLoading ? (
              <LoadingPanel label={t("operations.jobs.loading")} />
            ) : jobsQuery.isError ? (
              <ErrorPanel title={t("operations.jobs.loadFailed")} action={<Button variant="outline" onClick={() => void jobsQuery.refetch()}>{t("common.refresh")}</Button>} />
            ) : jobs.length === 0 ? (
              <EmptyState title={t("operations.jobs.empty")} />
            ) : (
              <>
                <TableShell>
                  <table className="min-w-[1120px] text-left text-sm">
                    <thead className="bg-[var(--oj-surface-muted)] text-xs uppercase tracking-wide text-[var(--oj-ink-muted)]">
                      <tr>
                        <th className="px-4 py-3">{t("operations.job")}</th>
                        <th className="px-4 py-3">{t("common.status")}</th>
                        <th className="px-4 py-3">{t("operations.resource")}</th>
                        <th className="px-4 py-3">{t("operations.progress")}</th>
                        <th className="px-4 py-3">{t("operations.attempts")}</th>
                        <th className="px-4 py-3">{t("operations.result")}</th>
                        <th className="px-4 py-3">{t("operations.error")}</th>
                        <th className="px-4 py-3">{t("common.created")}</th>
                        <th className="px-4 py-3 text-right">{t("common.actions")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--oj-border-soft)]">
                      {jobs.map((job) => (
                        <OperationJobRow
                          key={job.id}
                          job={job}
                          retrying={retryMutation.isPending}
                          onRetry={() => retryMutation.mutate(job.id)}
                          onDownload={() => void downloadArtifact(job.id)}
                          onInspect={() => setSelectedJob(job)}
                          t={t}
                          locale={locale}
                        />
                      ))}
                    </tbody>
                  </table>
                </TableShell>
                <Pagination page={jobPage} totalPages={jobTotalPages} onPageChange={setJobPage} t={t} />
              </>
            )}
          </CardBody>
        </Card>
      ) : (
        <Card className="rounded-xl shadow-none">
          <CardBody className="space-y-4">
            {auditQuery.isLoading ? (
              <LoadingPanel label={t("operations.audit.loading")} />
            ) : auditQuery.isError ? (
              <ErrorPanel title={t("operations.audit.loadFailed")} action={<Button variant="outline" onClick={() => void auditQuery.refetch()}>{t("common.refresh")}</Button>} />
            ) : auditEvents.length === 0 ? (
              <EmptyState title={t("operations.audit.empty")} />
            ) : (
              <>
                <TableShell>
                  <table className="w-full min-w-[1040px] table-fixed text-left text-sm">
                    <colgroup>
                      <col className="w-[22%]" />
                      <col className="w-[14%]" />
                      <col className="w-[19%]" />
                      <col className="w-[8%]" />
                      <col className="w-[22%]" />
                      <col className="w-[7%]" />
                      <col className="w-[8%]" />
                    </colgroup>
                    <thead className="bg-[var(--oj-surface-muted)] text-xs uppercase tracking-wide text-[var(--oj-ink-muted)]">
                      <tr>
                        <th className="px-4 py-3">{t("operations.action")}</th>
                        <th className="px-4 py-3">{t("operations.actor")}</th>
                        <th className="px-4 py-3">{t("operations.resource")}</th>
                        <th className="px-4 py-3">{t("common.status")}</th>
                        <th className="px-4 py-3">{t("operations.summary")}</th>
                        <th className="px-4 py-3">{t("operations.traceId")}</th>
                        <th className="px-4 py-3">{t("common.created")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--oj-border-soft)]">
                      {auditEvents.map((event) => <AuditRow key={event.id} event={event} onInspect={() => setSelectedAuditEvent(event)} t={t} />)}
                    </tbody>
                  </table>
                </TableShell>
                <Pagination page={auditPage} totalPages={auditTotalPages} onPageChange={setAuditPage} t={t} />
              </>
            )}
          </CardBody>
        </Card>
      )}
      {selectedJob ? <OperationJobDetailDialog job={selectedJob} onClose={() => setSelectedJob(null)} t={t} locale={locale} /> : null}
      {selectedAuditEvent ? <AuditEventDetailDialog event={selectedAuditEvent} onClose={() => setSelectedAuditEvent(null)} t={t} /> : null}
    </div>
  );

  async function downloadArtifact(jobId: string) {
    setDownloadError(null);
    try {
      await downloadOperationJobArtifact(jobId);
    } catch {
      setDownloadError(t("operations.artifactDownloadFailed"));
    }
  }
}

function OperationJobRow({
  job,
  retrying,
  onRetry,
  onDownload,
  onInspect,
  t,
  locale
}: {
  job: OperationJobResponse;
  retrying: boolean;
  onRetry: () => void;
  onDownload: () => void;
  onInspect: () => void;
  t: TFunction;
  locale: "zh-CN" | "en-US";
}) {
  const resource = formatResourceSummary(t, job.resourceType, job.resourceId, job.contestId, job.contestRunId);
  const result = formatJobResult(t, job);
  const readableError = readableStoredError(job.errorMessage, locale, "--", "operation");
  return (
    <tr className="align-top">
      <td className="px-4 py-3">
        <div className="font-medium text-[var(--oj-ink)]">{t(`operations.type.${job.jobType}`)}</div>
        <div className="mt-1 text-xs text-[var(--oj-ink-muted)]">#{shortId(job.id)}</div>
      </td>
      <td className="px-4 py-3"><Badge tone={jobStatusTone(job.status)}>{t(`operations.status.${job.status}`)}</Badge></td>
      <td className="px-4 py-3 text-[var(--oj-ink-muted)]">
        <div className="font-medium text-[var(--oj-ink)]">{resource.primary}</div>
        <div className="mt-1 text-xs">{resource.context}</div>
      </td>
      <td className="max-w-[220px] px-4 py-3 text-[var(--oj-ink-muted)]">
        <div className="tabular-nums">{formatProgress(job)}</div>
        <div className="mt-1 line-clamp-2 text-xs" title={formatProgressMessage(t, job)}>{formatProgressMessage(t, job)}</div>
      </td>
      <td className="px-4 py-3 tabular-nums">{job.attemptCount}/{job.maxAttempts}</td>
      <td className="max-w-[220px] px-4 py-3 text-[var(--oj-ink-muted)]">
        <span className="line-clamp-2" title={result}>{result}</span>
      </td>
      <td className="max-w-[260px] px-4 py-3 text-[var(--oj-danger)]">
        <span className="line-clamp-2" title={job.errorMessage ?? ""}>{readableError}</span>
      </td>
      <td className="px-4 py-3 whitespace-nowrap text-[var(--oj-ink-muted)]">{formatDate(job.createdAt)}</td>
      <td className="px-4 py-3">
        <div className="flex justify-end gap-2">
          {job.status === "COMPLETED" && job.artifact ? (
            <Button variant="outline" size="sm" onClick={onDownload}>
              <Download className="size-4" aria-hidden="true" />
              {t("operations.download")}
            </Button>
          ) : null}
          {(job.status === "FAILED" || job.status === "CANCELLED") ? (
            <Button variant="outline" size="sm" disabled={retrying} onClick={onRetry}>
              <RotateCcw className="size-4" aria-hidden="true" />
              {t("operations.retry")}
            </Button>
          ) : null}
          <Button variant="outline" size="sm" onClick={onInspect}>{t("operations.details")}</Button>
        </div>
      </td>
    </tr>
  );
}

function formatProgress(job: OperationJobResponse) {
  const current = job.progressCurrent ?? 0;
  const total = job.progressTotal ?? 0;
  if (total > 0) return `${current}/${total}`;
  if (job.status === "COMPLETED") return "100%";
  if (job.status === "RUNNING") return current > 0 ? String(current) : "--";
  return "--";
}

function AuditRow({ event, onInspect, t }: { event: OperationAuditEventResponse; onInspect: () => void; t: TFunction }) {
  const resource = formatResourceSummary(t, event.resourceType, event.resourceId, event.contestId, event.contestRunId);
  const status = formatAuditStatus(t, event.status);
  const trace = event.traceId ? shortenTraceId(event.traceId) : "--";
  const actionLabel = formatAuditAction(t, event);
  const summary = formatEvidenceSummary(t, event.summaryJson);
  return (
    <tr
      role="button"
      tabIndex={0}
      aria-label={`${actionLabel} ${t("operations.details")}`}
      className="h-16 cursor-pointer align-middle transition-colors hover:bg-[var(--oj-surface-muted)] focus-visible:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[var(--oj-focus)]"
      onClick={onInspect}
      onKeyDown={(keyboardEvent) => {
        if (keyboardEvent.key === "Enter" || keyboardEvent.key === " ") {
          keyboardEvent.preventDefault();
          onInspect();
        }
      }}
    >
      <td className="px-4 py-3 align-middle">
        <span className="line-clamp-2 text-sm font-medium leading-5 text-[var(--oj-ink)]" title={actionLabel}>{actionLabel}</span>
      </td>
      <td className="px-4 py-3 align-middle text-[var(--oj-ink-muted)]">
        <div className="truncate tabular-nums" title={`#${event.actorUserId}`}>#{event.actorUserId}</div>
      </td>
      <td className="px-4 py-3 align-middle text-[var(--oj-ink-muted)]">
        <div className="line-clamp-1 font-medium text-[var(--oj-ink)]" title={resource.primary}>{resource.primary}</div>
        <div className="mt-1 line-clamp-1 text-xs" title={resource.context}>{resource.context}</div>
      </td>
      <td className="px-4 py-3 align-middle">{event.status ? <Badge tone={auditStatusTone(event.status)}>{status}</Badge> : "--"}</td>
      <td className="px-4 py-3 align-middle">
        <span className="line-clamp-2 leading-5 text-[var(--oj-ink-muted)]" title={summary}>{summary}</span>
      </td>
      <td className="px-4 py-3 align-middle text-xs tabular-nums text-[var(--oj-ink-muted)]" title={event.traceId ?? ""}>
        <span className="line-clamp-2 break-all leading-5">{trace}</span>
      </td>
      <td className="px-4 py-3 align-middle text-[var(--oj-ink-muted)]">
        <span className="line-clamp-2 leading-5" title={formatDate(event.createdAt)}>{formatDate(event.createdAt)}</span>
      </td>
    </tr>
  );
}

function OperationJobDetailDialog({ job, onClose, t, locale }: { job: OperationJobResponse; onClose: () => void; t: TFunction; locale: "zh-CN" | "en-US" }) {
  const resource = formatResourceSummary(t, job.resourceType, job.resourceId, job.contestId, job.contestRunId);
  const readableError = readableStoredError(job.errorMessage, locale, "", "operation");
  return (
    <DetailDialog title={t("operations.jobDetails")} subtitle={`#${job.id}`} onClose={onClose} t={t}>
      <DetailGrid
        items={[
          [t("operations.job"), t(`operations.type.${job.jobType}`)],
          [t("common.status"), t(`operations.status.${job.status}`)],
          [t("operations.resource"), resource.primary],
          [t("operations.resourceContext"), resource.context],
          [t("operations.progress"), `${formatProgress(job)} · ${formatProgressMessage(t, job)}`],
          [t("operations.attempts"), `${job.attemptCount}/${job.maxAttempts}`],
          [t("common.created"), formatDate(job.createdAt)],
          [t("operations.startedAt"), formatDate(job.startedAt)],
          [t("operations.completedAt"), formatDate(job.completedAt)]
        ]}
      />
      <TechnicalBlock label={t("operations.result")} value={formatJobResult(t, job)} />
      {readableError ? <TechnicalBlock label={t("operations.error")} value={readableError} tone="danger" /> : null}
      {job.errorMessage && job.errorMessage !== readableError ? <TechnicalBlock label={t("operations.rawError")} value={job.errorMessage} /> : null}
      <TechnicalBlock label={t("operations.technicalDetails")} value={prettyJson(job.resultJson)} />
    </DetailDialog>
  );
}

function AuditEventDetailDialog({ event, onClose, t }: { event: OperationAuditEventResponse; onClose: () => void; t: TFunction }) {
  const resource = formatResourceSummary(t, event.resourceType, event.resourceId, event.contestId, event.contestRunId);
  return (
    <DetailDialog title={t("operations.auditDetails")} subtitle={`#${event.id}`} onClose={onClose} t={t}>
      <DetailGrid
        items={[
          [t("operations.action"), formatAuditAction(t, event)],
          [t("operations.rawAction"), event.action ?? "--"],
          [t("operations.actor"), `#${event.actorUserId}`],
          [t("operations.resource"), resource.primary],
          [t("operations.resourceContext"), resource.context],
          [t("common.status"), formatAuditStatus(t, event.status)],
          [t("operations.targetUser"), event.targetUserId ? `#${event.targetUserId}` : "--"],
          [t("common.created"), formatDate(event.createdAt)]
        ]}
      />
      <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
        <div className="mb-2 flex items-center justify-between gap-3">
          <span className="text-xs font-semibold text-[var(--oj-ink-muted)]">{t("operations.traceId")}</span>
          {event.traceId ? (
            <Button variant="outline" size="sm" onClick={() => void copyText(event.traceId ?? "")}>
              <Copy className="size-4" aria-hidden="true" />
              {t("operations.copyTraceId")}
            </Button>
          ) : null}
        </div>
        <div className="break-all font-mono text-xs text-[var(--oj-ink)]">{event.traceId ?? "--"}</div>
      </div>
      <TechnicalBlock label={t("operations.summary")} value={formatEvidenceSummary(t, event.summaryJson)} />
      <TechnicalBlock label={t("operations.technicalDetails")} value={prettyJson(event.summaryJson)} />
    </DetailDialog>
  );
}

function DetailDialog({
  title,
  subtitle,
  children,
  onClose,
  t
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  onClose: () => void;
  t: TFunction;
}) {
  React.useEffect(() => {
    const root = document.documentElement;
    const body = document.body;
    const previousRootOverflow = root.style.overflow;
    const previousBodyOverflow = body.style.overflow;
    const previousBodyPaddingRight = body.style.paddingRight;
    const scrollbarWidth = window.innerWidth - root.clientWidth;

    root.style.overflow = "hidden";
    body.style.overflow = "hidden";
    if (scrollbarWidth > 0) {
      body.style.paddingRight = `${scrollbarWidth}px`;
    }

    return () => {
      root.style.overflow = previousRootOverflow;
      body.style.overflow = previousBodyOverflow;
      body.style.paddingRight = previousBodyPaddingRight;
    };
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 px-4 py-6"
      role="dialog"
      aria-modal="true"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="max-h-[86vh] w-full max-w-3xl overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white shadow-2xl">
        <header className="flex items-start justify-between gap-4 border-b border-[var(--oj-border-soft)] px-5 py-4">
          <div>
            <h2 className="text-base font-semibold text-[var(--oj-ink)]">{title}</h2>
            {subtitle ? <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{subtitle}</p> : null}
          </div>
          <button type="button" className="rounded-lg px-2 py-1 text-xl leading-none text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)]" onClick={onClose} aria-label={t("common.close")}>
            ×
          </button>
        </header>
        <div className="max-h-[calc(86vh-76px)] space-y-4 overflow-y-auto overscroll-contain px-5 py-4 [scrollbar-gutter:stable]">
          {children}
        </div>
      </div>
    </div>
  );
}

function DetailGrid({ items }: { items: Array<[string, string]> }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {items.map(([label, value]) => (
        <div key={label} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <div className="text-xs font-semibold text-[var(--oj-ink-muted)]">{label}</div>
          <div className="mt-1 break-words text-sm text-[var(--oj-ink)]">{value || "--"}</div>
        </div>
      ))}
    </div>
  );
}

function TechnicalBlock({ label, value, tone = "neutral" }: { label: string; value: string; tone?: "neutral" | "danger" }) {
  return (
    <section className="rounded-xl border border-[var(--oj-border-soft)] bg-white">
      <div className="border-b border-[var(--oj-border-soft)] px-3 py-2 text-xs font-semibold text-[var(--oj-ink-muted)]">{label}</div>
      <pre className={`max-h-72 overflow-auto whitespace-pre-wrap break-words p-3 text-xs leading-6 ${tone === "danger" ? "text-[var(--oj-danger)]" : "text-[var(--oj-ink)]"}`}>{value || "--"}</pre>
    </section>
  );
}

function Pagination({ page, totalPages, onPageChange, t }: { page: number; totalPages: number; onPageChange: (page: number) => void; t: TFunction }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-[var(--oj-border-soft)] px-4 py-3 text-sm text-[var(--oj-ink-muted)]">
      <span>{page} / {Math.max(totalPages, 1)}</span>
      <div className="flex gap-2">
        <Button variant="outline" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>{t("common.previous")}</Button>
        <Button variant="outline" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>{t("common.next")}</Button>
      </div>
    </div>
  );
}

function jobStatusTone(status: OperationJobStatus) {
  if (status === "COMPLETED") return "green";
  if (status === "FAILED" || status === "CANCELLED") return "red";
  if (status === "RUNNING") return "blue";
  return "amber";
}

function formatDate(value?: string | null) {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "short" }).format(date);
}

function formatProgressMessage(t: TFunction, job: OperationJobResponse) {
  const message = job.progressMessage?.trim();
  if (message) {
    const normalized = message.toUpperCase();
    if (normalized === "COMPLETED") return t("operations.progressMessage.completed");
    if (normalized === "RUNNING") return t("operations.progressMessage.running");
    if (normalized === "QUEUED") return t("operations.progressMessage.queued");
    return message;
  }
  if (job.status === "COMPLETED") return t("operations.progressMessage.completed");
  if (job.status === "RUNNING") return t("operations.progressMessage.running");
  if (job.status === "QUEUED") return t("operations.progressMessage.queued");
  if (job.status === "FAILED") return t("operations.progressMessage.failed");
  return "--";
}

function formatResourceSummary(
  t: TFunction,
  resourceType?: string | null,
  resourceId?: string | null,
  contestId?: string | null,
  contestRunId?: string | null
) {
  const typeLabel = formatKnownLabel(t, "operations.resourceType", resourceType);
  const primary = resourceId ? `${typeLabel} #${shortId(resourceId)}` : typeLabel;
  const contest = contestId ? `${t("operations.resourceContest")} #${shortId(contestId)}` : t("operations.noContestContext");
  const run = contestRunId ? `${t("operations.resourceRun")} #${shortId(contestRunId)}` : t("operations.noRunContext");
  return {
    primary,
    context: `${contest} / ${run}`
  };
}

function formatKnownLabel(t: TFunction, baseKey: string, value?: string | null) {
  if (!value) return "--";
  return t(`${baseKey}.${value}`, undefined, value);
}

function formatJobResult(t: TFunction, job: OperationJobResponse) {
  const parsed = parseJsonObject(job.resultJson);
  if (!parsed) {
    if (job.artifact) return t("operations.resultSummary.artifact");
    if (job.status === "COMPLETED") return t("operations.resultSummary.completed");
    return t("operations.resultSummary.none");
  }
  if (truthy(parsed.artifact) || job.artifact) return t("operations.resultSummary.artifact");
  const reportId = firstString(parsed.reportId, parsed.teacherReportId, parsed.studentReportId);
  if (reportId) return t("operations.resultSummary.report", { id: shortId(reportId) });
  const plagiarismJobId = firstString(parsed.plagiarismJobId);
  if (plagiarismJobId) return t("operations.resultSummary.plagiarismJob", { id: shortId(plagiarismJobId) });
  if (Array.isArray(parsed.generatedReportIds)) {
    return t("operations.resultSummary.batchReports", { count: parsed.generatedReportIds.length });
  }
  return t("operations.resultSummary.completed");
}

function formatAuditAction(t: TFunction, eventOrAction?: OperationAuditEventResponse | string | null) {
  if (eventOrAction && typeof eventOrAction === "object") {
    const displayName = eventOrAction.actionDisplayName?.trim();
    if (displayName) return displayName;
    return formatKnownLabel(t, "operations.actionType", eventOrAction.action);
  }
  return formatKnownLabel(t, "operations.actionType", eventOrAction);
}

function formatAuditStatus(t: TFunction, status?: string | null) {
  return formatKnownLabel(t, "operations.auditStatus", status);
}

function auditStatusTone(status?: string | null) {
  if (status === "SUCCESS" || status === "COMPLETED") return "green";
  if (status === "FAILED" || status === "ERROR") return "red";
  if (status === "RUNNING") return "blue";
  return "neutral";
}

function formatEvidenceSummary(t: TFunction, summaryJson?: string | null) {
  const parsed = parseJsonObject(summaryJson);
  if (!parsed) return summaryJson?.trim() || "--";
  const entries = Object.entries(parsed)
    .filter(([, value]) => value != null && value !== "")
    .slice(0, 4)
    .map(([key, value]) => `${formatKnownLabel(t, "operations.evidenceLabel", key)}：${formatReadableValue(t, key, value)}`);
  return entries.length ? entries.join("；") : "--";
}

function formatReadableValue(t: TFunction, key: string, value: unknown): string {
  if (value == null) return "--";
  if (typeof value === "boolean") return value ? t("common.yes") : t("common.no");
  if (typeof value === "number") return Number.isInteger(value) ? String(value) : String(Math.round(value * 1000) / 1000);
  if (typeof value === "string") {
    if (looksLikeIsoDate(key, value)) return formatDate(value);
    if (key.toLowerCase().includes("id")) return `#${shortId(value)}`;
    return value;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return "--";
    return value.slice(0, 5).map((item) => formatReadableValue(t, key, item)).join(", ");
  }
  if (typeof value === "object") {
    const nestedEntries = Object.entries(value as Record<string, unknown>)
      .filter(([, nestedValue]) => nestedValue != null && nestedValue !== "")
      .slice(0, 3)
      .map(([nestedKey, nestedValue]) => `${formatKnownLabel(t, "operations.evidenceLabel", nestedKey)}：${formatReadableValue(t, nestedKey, nestedValue)}`);
    return nestedEntries.length ? nestedEntries.join("；") : "--";
  }
  return String(value);
}

function looksLikeIsoDate(key: string, value: string) {
  return (key.toLowerCase().endsWith("at") || key.toLowerCase().includes("time")) && !Number.isNaN(new Date(value).getTime());
}

function parseJsonObject(value?: string | null): Record<string, unknown> | null {
  if (!value?.trim()) return null;
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function prettyJson(value?: string | null) {
  if (!value?.trim()) return "--";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function firstString(...values: unknown[]) {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) return value;
  }
  return null;
}

function truthy(value: unknown) {
  return value === true || value === "true" || value === 1;
}

function shortId(value?: string | null) {
  if (!value) return "--";
  return value.length > 12 ? `${value.slice(0, 6)}…${value.slice(-4)}` : value;
}

function shortenTraceId(value: string) {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    // Clipboard may be unavailable in non-secure local contexts; the visible value remains selectable.
  }
}
