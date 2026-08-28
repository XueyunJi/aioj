import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Archive, BarChart3, Bot, Brain, CalendarClock, Check, CheckCircle2, ChevronLeft, ChevronRight, Download, Eye, FileCode, Megaphone, MessagesSquare, Pause, Play, Plus, RadioTower, RotateCw, Save, Search, Send, ShieldCheck, Trash2, UserCheck } from "lucide-react";
import {
  activeQueryRefetchInterval,
  ApiError,
  api,
  steadyQueryRefetchInterval,
  type AdminUserResponse,
  type ContestAiPolicyMode,
  type ContestAnnouncementResponse,
  type ContestInvitationBatchResponse,
  type ContestClarificationResponse,
  type ContestClarificationStatus,
  type ContestClarificationVisibility,
  type ContestMode,
  type ContestPayload,
  type ContestExportFormat,
  type ContestProblemPayload,
  type ContestPostmortemReportResponse,
  type ContestRegistrationAccess,
  type ContestRegistrationResponse,
  type ContestRegistrationStatus,
  type ContestResolverSessionDetailResponse,
  type ContestResolverSessionResponse,
  type ContestResponse,
  type ContestRunKind,
  type ContestRunResponse,
  type ContestRunStatus,
  type ContestScoreboardView,
  type ContestStudentPostmortemSummaryResponse,
  type ContestSubmissionResponse,
  type ContestStatus,
  type ContestPlagiarismGraphEdge,
  type ContestPlagiarismGraphResponse,
  type EntityId,
  type FairnessAlertResponse,
  type FairnessAlertSeverity,
  type FairnessAlertStatus,
  type FairnessAlertType,
  type LearningGroupResponse,
  type PlagiarismAiStatus,
  type PlagiarismJobStatus,
  type PlagiarismPairDetailResponse,
  type PlagiarismReviewStatus,
  type PlagiarismRiskLevel,
  type ProblemListSort,
  type ProblemResponse,
  type SubmissionStatus
} from "@aioj/api-client";
import { Badge, Button, Card, CardBody, ContestScoreboardTable, cn, shouldToggleRowSelection } from "@aioj/ui-react";
import {
  ConfirmDialog,
  EmptyState,
  ErrorPanel,
  Field,
  LoadingPanel,
  PageHeader,
  SidePanel,
  TableShell,
  inputClass,
  selectClass,
  textareaClass
} from "../components/Common";
import { DateTimeField } from "../components/DateTimeField";
import { MarkdownView } from "../components/MarkdownView";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { useOperationJobAutoDownload } from "../lib/useOperationJobAutoDownload";
import { formatBytes, formatDateTime, shortId } from "../lib/format";
import { readableJudgeMessage, readableStoredError } from "../lib/readableError";
import { ContestAiUsagePanel } from "./ContestAiUsagePanel";

const PAGE_SIZE = 20;
const MODES: ContestMode[] = ["ACM", "IOI"];
const STATUSES: Array<ContestStatus | ""> = ["", "DRAFT", "PUBLISHED", "ARCHIVED"];
const SUBMISSION_STATUSES: Array<SubmissionStatus | ""> = ["", "QUEUED", "RUNNING", "ACCEPTED", "WRONG_ANSWER", "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED", "RUNTIME_ERROR", "COMPILE_ERROR", "SYSTEM_ERROR"];
const LANGUAGES = ["", "cpp", "python", "java"];
const PLAGIARISM_RISK_LEVELS: Array<PlagiarismRiskLevel | ""> = ["", "MEDIUM", "HIGH", "CRITICAL", "LOW"];
const PLAGIARISM_REVIEW_STATUSES: Array<PlagiarismReviewStatus | ""> = ["", "OPEN", "REVIEWED", "DISMISSED", "CONFIRMED"];
const FAIRNESS_ALERT_TYPES: Array<FairnessAlertType | ""> = ["", "HIGH_RISK_UNREVIEWED", "REPEATED_HIGH_SIMILARITY", "SHARED_IP_CLUSTER", "SHARED_USER_AGENT_CLUSTER", "NEAR_TIME_HIGH_RISK_PAIR", "UNFINISHED_JUDGING"];
const FAIRNESS_ALERT_SEVERITIES: Array<FairnessAlertSeverity | ""> = ["", "CRITICAL", "HIGH", "MEDIUM", "LOW"];
const FAIRNESS_ALERT_STATUSES: Array<FairnessAlertStatus | ""> = ["", "OPEN", "REVIEWED", "DISMISSED", "CONFIRMED"];
const RUN_STATUSES: Array<ContestRunStatus | ""> = ["", "DRAFT", "EXPIRED", "SCHEDULED", "RUNNING", "ENDED", "ARCHIVED"];
const RUNS_PAGE_SIZE = 50;
const REGISTRATIONS_PAGE_SIZE = 50;
const CLARIFICATIONS_PAGE_SIZE = 50;
const POSTMORTEM_REPORTS_PAGE_SIZE = 20;
const STUDENT_SUMMARIES_PAGE_SIZE = 50;
const PLAGIARISM_JOBS_PAGE_SIZE = 20;
const RUN_KINDS: ContestRunKind[] = ["FORMAL", "SIMULATION", "PRACTICE", "REPLAY"];
const REGISTRATION_ACCESSES: ContestRegistrationAccess[] = ["PUBLIC", "GROUPS", "INVITE_ONLY"];
const REGISTRATION_STATUSES: Array<ContestRegistrationStatus | ""> = ["", "PENDING", "INVITED", "APPROVED", "REJECTED", "DECLINED", "CANCELLED"];
const AI_POLICY_MODES: ContestAiPolicyMode[] = ["DEFAULT", "STRICT", "DISABLED"];

function isEligibleAiOperationRun(run: ContestRunResponse) {
  return run.status === "ENDED" && !run.deletedAt;
}

function formatRunSelectorLabel(run: ContestRunResponse) {
  return `${run.title} · ${formatDateTime(run.startAt)} - ${formatDateTime(run.endAt)}`;
}

type ProblemRow = ContestProblemPayload & {
  title?: string;
};

export function ContestsView() {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [status, setStatus] = React.useState<ContestStatus | "">("");
  const [keyword, setKeyword] = React.useState("");
  const [acmFilter, setAcmFilter] = React.useState<"" | "true" | "false">("");
  const [page, setPage] = React.useState(1);
  const [editorTarget, setEditorTarget] = React.useState<ContestResponse | null | undefined>(undefined);
  const [runsTarget, setRunsTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [scoreboardTarget, setScoreboardTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [submissionsTarget, setSubmissionsTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [plagiarismTarget, setPlagiarismTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [postmortemTarget, setPostmortemTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [communicationTarget, setCommunicationTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [aiUsageTarget, setAiUsageTarget] = React.useState<ContestResponse | undefined>(undefined);
  const [archiveTarget, setArchiveTarget] = React.useState<ContestResponse | null>(null);
  const [publishTarget, setPublishTarget] = React.useState<ContestResponse | null>(null);
  const [restoreTarget, setRestoreTarget] = React.useState<ContestResponse | null>(null);
  const [deleteTarget, setDeleteTarget] = React.useState<ContestResponse | null>(null);

  React.useEffect(() => {
    setPage(1);
  }, [status, keyword, acmFilter]);

  const trimmedKeyword = keyword.trim();

  const contestsQuery = useQuery({
    queryKey: ["admin-contests", page, status, trimmedKeyword, acmFilter],
    queryFn: () => api.contests({
      page,
      pageSize: PAGE_SIZE,
      status,
      keyword: trimmedKeyword || undefined,
      acm: acmFilter === "" ? "" : acmFilter === "true"
    })
  });

  const groupsQuery = useQuery({
    queryKey: ["contest-scope-groups"],
    queryFn: () => api.classes({ status: "ACTIVE" })
  });

  const confirmMutation = useMutation({
    mutationFn: (id: EntityId) => api.confirmContest(id),
    onSuccess: async () => {
      await invalidateContestQueries(queryClient);
    }
  });

  const archiveMutation = useMutation({
    mutationFn: (id: EntityId) => api.archiveContest(id),
    onSuccess: async () => {
      await invalidateContestQueries(queryClient);
    }
  });

  const restoreMutation = useMutation({
    mutationFn: (id: EntityId) => api.restoreContest(id),
    onSuccess: async () => {
      await invalidateContestQueries(queryClient);
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (id: EntityId) => api.deleteContest(id),
    onSuccess: async () => {
      await invalidateContestQueries(queryClient);
    }
  });

  const records = contestsQuery.data?.records ?? [];
  const total = contestsQuery.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const groups = groupsQuery.data ?? [];
  async function confirmContest(contest: ContestResponse) {
    try {
      await confirmMutation.mutateAsync(contest.id);
      toast.success(t("contests.publishedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.publishFailed"));
    }
  }

  return (
    <div className="mx-auto flex max-w-[1540px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("contests.adminTitle")}
        description={t("contests.adminDescription")}
        actions={(
          <>
            <Button variant="outline" disabled={contestsQuery.isFetching} onClick={() => void contestsQuery.refetch()}>
              <RotateCw className="size-4" aria-hidden="true" />
              {t("common.refresh")}
            </Button>
            <Button onClick={() => setEditorTarget(null)}>
              <Plus className="size-4" aria-hidden="true" />
              {t("contests.create")}
            </Button>
          </>
        )}
      />

      <section className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <input
          className={`${inputClass} w-full sm:w-72`}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder={t("contests.searchByNamePlaceholder")}
        />
        <select className={`${selectClass} w-full sm:w-48`} value={status} onChange={(event) => setStatus(event.target.value as ContestStatus | "")}>
          {STATUSES.map((item) => (
            <option key={item || "all"} value={item}>
              {item ? t(`contests.status.${item}`) : t("contests.allStatuses")}
            </option>
          ))}
        </select>
        <select className={`${selectClass} w-full sm:w-44`} value={acmFilter} onChange={(event) => setAcmFilter(event.target.value as typeof acmFilter)}>
          <option value="">{t("contests.allModes")}</option>
          <option value="true">{t("contests.acmOnly")}</option>
          <option value="false">{t("contests.nonAcmOnly")}</option>
        </select>
        <Button
          className="w-fit"
          variant="outline"
          onClick={() => {
            setStatus("");
            setKeyword("");
            setAcmFilter("");
          }}
        >
          {t("problems.resetFilters")}
        </Button>
      </section>

      {contestsQuery.isLoading ? (
        <LoadingPanel label={t("contests.loading")} />
      ) : contestsQuery.isError ? (
        <ErrorPanel title={t("contests.loadFailed")} action={<Button variant="outline" onClick={() => void contestsQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : records.length ? (
        <>
          <TableShell>
            <table className="w-full min-w-[1180px] text-sm">
              <colgroup>
                <col className="w-[22%]" />
                <col className="w-[14%]" />
                <col className="w-[16%]" />
                <col className="w-[14%]" />
                <col className="w-[6%]" />
                <col className="w-[28%]" />
              </colgroup>
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="px-4 py-3 text-left">{t("common.title")}</th>
                  <th className="px-4 py-3 text-left">{t("contests.modeAndStatus")}</th>
                  <th className="px-4 py-3 text-left">{t("contests.rules")}</th>
                  <th className="px-4 py-3 text-left">{t("common.updatedAt")}</th>
                  <th className="px-4 py-3 text-center">{t("contests.problemCount")}</th>
                  <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {records.map((contest) => {
                  const confirmed = contest.status === "PUBLISHED";
                  const confirmedOnlyTitle = confirmed ? undefined : t("contests.confirmBeforeOperations");
                  return (
                  <tr key={contest.id} className="align-middle">
                    <td className="px-4 py-4">
                      <div className="min-w-0">
                        <strong className="line-clamp-2 text-[var(--oj-ink)]" title={contest.title}>{contest.title}</strong>
                        <span className="mt-1 block text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(contest.id)}</span>
                      </div>
                    </td>
                    <td className="px-4 py-4">
                      <div className="flex flex-wrap gap-2">
                        <Badge tone="blue">{t(`contests.mode.${contest.mode}`)}</Badge>
                        <StatusChip status={contest.status} />
                      </div>
                    </td>
                    <td className="px-4 py-4 text-xs leading-5 text-[var(--oj-ink-muted)]">
                      <div>{t("contests.penaltyMinutes")}: {contest.penaltyMinutes}</div>
                      <div>{t("contests.cePenalty")}: {contest.cePenalty ? t("common.yes") : t("common.no")}</div>
                    </td>
                    <td className="px-4 py-4 text-xs leading-5 text-[var(--oj-ink-muted)]">
                      {formatDateTime(contest.updatedAt)}
                    </td>
                    <td className="px-4 py-4 text-center tabular-nums">{contest.problemCount}</td>
                    <td className="px-4 py-4">
                      <div className="grid grid-cols-2 gap-2 xl:grid-cols-3">
                        <Button size="sm" variant="outline" onClick={() => setEditorTarget(contest)}>{t("common.edit")}</Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setRunsTarget(contest)}>
                          <CalendarClock className="size-4" aria-hidden="true" />
                          {t("contests.runs")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setScoreboardTarget(contest)}>
                          <BarChart3 className="size-4" aria-hidden="true" />
                          {t("contests.scoreboard")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setSubmissionsTarget(contest)}>
                          <FileCode className="size-4" aria-hidden="true" />
                          {t("contests.submissions")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setPlagiarismTarget(contest)}>
                          <Search className="size-4" aria-hidden="true" />
                          {t("contests.plagiarism")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setPostmortemTarget(contest)}>
                          <Brain className="size-4" aria-hidden="true" />
                          {t("contests.postmortem")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setCommunicationTarget(contest)}>
                          <MessagesSquare className="size-4" aria-hidden="true" />
                          {t("contests.communications")}
                        </Button>
                        <Button size="sm" variant="outline" disabled={!confirmed} title={confirmedOnlyTitle} onClick={() => setAiUsageTarget(contest)}>
                          <Bot className="size-4" aria-hidden="true" />
                          {t("contests.aiUsage.title")}
                        </Button>
                        {contest.status === "DRAFT" ? (
                          <Button size="sm" disabled={confirmMutation.isPending} onClick={() => setPublishTarget(contest)}>
                            <Send className="size-4" aria-hidden="true" />
                            {t("contests.confirmCreate")}
                          </Button>
                        ) : null}
                        {contest.status !== "ARCHIVED" ? (
                          <Button size="sm" variant="outline" onClick={() => setArchiveTarget(contest)}>
                            <Archive className="size-4" aria-hidden="true" />
                            {t("contests.archive")}
                          </Button>
                        ) : null}
                        {contest.status === "ARCHIVED" ? (
                          <Button size="sm" variant="outline" disabled={restoreMutation.isPending} onClick={() => setRestoreTarget(contest)}>
                            <RotateCw className="size-4" aria-hidden="true" />
                            {t("contests.restoreRun")}
                          </Button>
                        ) : null}
                        {contest.status === "ARCHIVED" ? (
                          <Button size="sm" variant="outline" disabled={deleteMutation.isPending} onClick={() => setDeleteTarget(contest)}>
                            <Trash2 className="size-4" aria-hidden="true" />
                            {t("contests.deleteContest")}
                          </Button>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </TableShell>
          <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-white px-4 py-3 text-sm text-[var(--oj-ink-muted)] sm:flex-row sm:items-center sm:justify-between">
            <span className="tabular-nums">{page} / {totalPages} · {total}</span>
            <div className="flex gap-2">
              <Button variant="outline" disabled={page <= 1} onClick={() => setPage((value) => Math.max(1, value - 1))}>{t("common.previous")}</Button>
              <Button variant="outline" disabled={page >= totalPages} onClick={() => setPage((value) => Math.min(totalPages, value + 1))}>{t("common.next")}</Button>
            </div>
          </div>
        </>
      ) : (
        <EmptyState
          title={t("contests.emptyTitle")}
          description={t("contests.emptyDescription")}
          actionLabel={t("contests.create")}
          onAction={() => setEditorTarget(null)}
        />
      )}

      <ContestEditorPanel
        contest={editorTarget}
        onOpenChange={(open) => !open && setEditorTarget(undefined)}
        onSaved={async (contest) => {
          setEditorTarget(contest);
          await invalidateContestQueries(queryClient);
        }}
      />

      <ContestRunsPanel
        contest={runsTarget}
        groups={groups}
        onOpenChange={(open) => !open && setRunsTarget(undefined)}
      />

      <ContestScoreboardPanel
        contest={scoreboardTarget}
        onOpenChange={(open) => !open && setScoreboardTarget(undefined)}
      />

      <ContestSubmissionsPanel
        contest={submissionsTarget}
        onOpenChange={(open) => !open && setSubmissionsTarget(undefined)}
      />

      <ContestPlagiarismPanel
        contest={plagiarismTarget}
        onOpenChange={(open) => !open && setPlagiarismTarget(undefined)}
      />

      <ContestPostmortemPanel
        contest={postmortemTarget}
        onOpenChange={(open) => !open && setPostmortemTarget(undefined)}
      />

      <ContestCommunicationPanel
        contest={communicationTarget}
        onOpenChange={(open) => !open && setCommunicationTarget(undefined)}
      />

      <ContestAiUsagePanel
        contest={aiUsageTarget}
        onOpenChange={(open) => !open && setAiUsageTarget(undefined)}
      />

      <ConfirmDialog
        open={Boolean(publishTarget)}
        onOpenChange={(open) => !open && setPublishTarget(null)}
        title={t("contests.publishConfirmTitle")}
        description={`${publishTarget ? publishTarget.title : ""}\n${t("contests.publishConfirmCopy")}`}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.confirmCreate")}
        onConfirm={async () => {
          if (!publishTarget) return;
          try {
            await confirmContest(publishTarget);
          } finally {
            setPublishTarget(null);
          }
        }}
      />

      <ConfirmDialog
        open={Boolean(archiveTarget)}
        onOpenChange={(open) => !open && setArchiveTarget(null)}
        title={t("contests.archiveConfirm")}
        description={archiveTarget ? archiveTarget.title : ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.archive")}
        onConfirm={async () => {
          if (!archiveTarget) return;
          try {
            await archiveMutation.mutateAsync(archiveTarget.id);
            toast.success(t("contests.archivedMessage"));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.archiveFailed"));
          } finally {
            setArchiveTarget(null);
          }
        }}
      />

      <ConfirmDialog
        open={Boolean(restoreTarget)}
        onOpenChange={(open) => !open && setRestoreTarget(null)}
        title={t("contests.restoreConfirm")}
        description={restoreTarget ? restoreTarget.title : ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.restoreRun")}
        onConfirm={async () => {
          if (!restoreTarget) return;
          try {
            await restoreMutation.mutateAsync(restoreTarget.id);
            toast.success(t("contests.restoredMessage"));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.restoreFailed"));
          } finally {
            setRestoreTarget(null);
          }
        }}
      />

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("contests.deleteContest")}
        description={deleteTarget ? `${deleteTarget.title}\n${t("contests.deleteContestConfirm")}` : t("contests.deleteContestConfirm")}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.deleteContest")}
        onConfirm={async () => {
          if (!deleteTarget) return;
          try {
            await deleteMutation.mutateAsync(deleteTarget.id);
            toast.success(t("contests.deletedMessage"));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.deleteContestFailed"));
          } finally {
            setDeleteTarget(null);
          }
        }}
      />
    </div>
  );
}

function ContestEditorPanel({
  contest,
  onOpenChange,
  onSaved
}: {
  contest: ContestResponse | null | undefined;
  onOpenChange: (open: boolean) => void;
  onSaved: (contest: ContestResponse) => Promise<void>;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const [title, setTitle] = React.useState("");
  const [description, setDescription] = React.useState("");
  const [mode, setMode] = React.useState<ContestMode>("ACM");
  const [penaltyMinutes, setPenaltyMinutes] = React.useState(20);
  const [cePenalty, setCePenalty] = React.useState(false);
  const [aiPolicyMode, setAiPolicyMode] = React.useState<ContestAiPolicyMode>("DEFAULT");
  const [aiPolicyNotes, setAiPolicyNotes] = React.useState("");
  const [rows, setRows] = React.useState<ProblemRow[]>([]);
  const [saving, setSaving] = React.useState(false);
  const open = contest !== undefined;
  const existing = contest ?? null;
  const draftEditable = !existing || existing.status === "DRAFT";
  const descriptionEditable = !existing || existing.status !== "ARCHIVED";
  const previousContestIdRef = React.useRef<EntityId | null>(null);

  const contestProblemsQuery = useQuery({
    queryKey: ["contest-problems", existing?.id],
    queryFn: () => api.contestProblems(existing!.id),
    enabled: Boolean(open && existing?.id)
  });

  React.useEffect(() => {
    if (!open) {
      previousContestIdRef.current = null;
      return;
    }
    const currentId = existing?.id ?? null;
    setTitle(existing?.title ?? "");
    setDescription(existing?.description ?? "");
    setMode(existing?.mode ?? "ACM");
    setPenaltyMinutes(existing?.penaltyMinutes ?? 20);
    setCePenalty(Boolean(existing?.cePenalty));
    setAiPolicyMode(existing?.aiPolicyMode ?? "DEFAULT");
    setAiPolicyNotes(existing?.aiPolicyNotes ?? "");
    if (!existing || previousContestIdRef.current !== currentId) {
      setRows([]);
    }
    previousContestIdRef.current = currentId;
  }, [existing?.id, open]);

  React.useEffect(() => {
    if (!contestProblemsQuery.data) return;
    setRows(contestProblemsQuery.data.map((item) => ({
      problemId: item.problemId,
      label: item.label,
      displayTitle: item.displayTitle ?? "",
        score: 0,
        sortOrder: item.sortOrder,
        scoringMode: "CASE_SUM_BEST_SUBMISSION",
        title: item.displayTitle ?? undefined
    })));
  }, [contestProblemsQuery.data]);

  const formSnapshot = React.useMemo(() => JSON.stringify({
    title,
    description,
    mode,
    penaltyMinutes,
    cePenalty,
    aiPolicyMode,
    aiPolicyNotes,
    rows: rows.map((row) => ({ problemId: row.problemId, label: row.label, displayTitle: row.displayTitle ?? "" }))
  }), [title, description, mode, penaltyMinutes, cePenalty, aiPolicyMode, aiPolicyNotes, rows]);

  const baselineSnapshot = React.useMemo(() => {
    if (!open) return null;
    if (existing && !contestProblemsQuery.data) return null;
    return JSON.stringify({
      title: existing?.title ?? "",
      description: existing?.description ?? "",
      mode: existing?.mode ?? "ACM",
      penaltyMinutes: existing?.penaltyMinutes ?? 20,
      cePenalty: Boolean(existing?.cePenalty),
      aiPolicyMode: existing?.aiPolicyMode ?? "DEFAULT",
      aiPolicyNotes: existing?.aiPolicyNotes ?? "",
      rows: (contestProblemsQuery.data ?? []).map((item) => ({ problemId: item.problemId, label: item.label, displayTitle: item.displayTitle ?? "" }))
    });
  }, [open, existing, contestProblemsQuery.data]);

  const dirty = baselineSnapshot !== null && formSnapshot !== baselineSnapshot;

  function addProblems(problems: ProblemResponse[]) {
    const existingIds = new Set(rows.map((item) => item.problemId));
    const fresh = problems.filter((problem) => !existingIds.has(problem.id));
    const skipped = problems.length - fresh.length;
    if (!fresh.length) {
      toast.error(t("contests.problemDuplicate"));
      return;
    }
    setRows((current) => [
      ...current,
      ...fresh.map((problem, index) => ({
        problemId: problem.id,
        label: nextLabel(current.length + index),
        displayTitle: problem.title,
        score: 0,
        sortOrder: current.length + index,
        scoringMode: "CASE_SUM_BEST_SUBMISSION" as const,
        title: problem.title
      }))
    ]);
    toast.success(t("contests.problemsAddedMessage", { added: fresh.length, skipped }));
  }

  async function save() {
    if (!dirty) return;
    if (!descriptionEditable) {
      toast.error(t("contests.problemEditorLocked"));
      return;
    }
    if (description.length > 2000) {
      toast.error(t("contests.descriptionTooLong"));
      return;
    }
    if (aiPolicyNotes.length > 2000) {
      toast.error(t("contests.aiPolicyNotesTooLong"));
      return;
    }
    if (draftEditable && !title.trim()) {
      toast.error(t("contests.titleRequired"));
      return;
    }
    setSaving(true);
    try {
      if (existing && existing.status === "PUBLISHED") {
        const saved = await api.updateContest(existing.id, {
          description: description.trim()
        });
        toast.success(t("contests.savedMessage"));
        await onSaved(saved);
        return;
      }
      const basePayload: ContestPayload = {
        title: title.trim(),
        description: description.trim() || undefined,
        mode,
        penaltyMinutes,
        cePenalty,
        aiPolicyMode,
        aiPolicyNotes: aiPolicyNotes.trim() || undefined
      };
      const saved = existing
        ? await api.updateContest(existing.id, {
          title: basePayload.title,
          description: basePayload.description,
          mode: basePayload.mode,
          penaltyMinutes: basePayload.penaltyMinutes,
          cePenalty: basePayload.cePenalty,
          aiPolicyMode: basePayload.aiPolicyMode,
          aiPolicyNotes: aiPolicyNotes.trim()
        })
        : await api.createContest(basePayload);
      if (draftEditable && rows.length) {
        await api.replaceContestProblems(saved.id, rows.map((row, index) => ({
          problemId: row.problemId,
          label: row.label.trim(),
          displayTitle: row.displayTitle?.trim() || undefined,
          score: 0,
          sortOrder: index,
          scoringMode: "CASE_SUM_BEST_SUBMISSION"
        })));
      }
      toast.success(t("contests.savedMessage"));
      await onSaved(saved);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <SidePanel
      wide
      open={open}
      onOpenChange={onOpenChange}
      title={existing ? t("contests.edit") : t("contests.create")}
      description={t("contests.editorDescription")}
      footer={(
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
          <Button disabled={saving || !dirty} onClick={() => void save()}>
            <Save className="size-4" aria-hidden="true" />
            {saving ? t("common.loading") : t("common.save")}
          </Button>
        </div>
      )}
    >
      <div className="space-y-5">
        <section className="grid gap-4 lg:grid-cols-2">
          <Field label={t("contests.title")}>
            <input className={inputClass} value={title} disabled={!draftEditable} onChange={(event) => setTitle(event.target.value)} placeholder={t("contests.titlePlaceholder")} />
          </Field>
          <Field label={t("contests.modeLabel")}>
            <select className={selectClass} value={mode} disabled={!draftEditable} onChange={(event) => setMode(event.target.value as ContestMode)}>
              {MODES.map((item) => <option key={item} value={item}>{t(`contests.mode.${item}`)}</option>)}
            </select>
          </Field>
          <Field label={t("contests.penaltyMinutes")}>
            <input
              className={inputClass}
              type="number"
              min={0}
              max={300}
              value={penaltyMinutes}
              disabled={!draftEditable}
              onChange={(event) => setPenaltyMinutes(Number(event.target.value))}
            />
          </Field>
          <Field label={t("contests.cePenalty")}>
            <label className="flex h-10 items-center gap-2 rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)]">
              <input
                type="checkbox"
                checked={cePenalty}
                disabled={!draftEditable}
                onChange={(event) => setCePenalty(event.target.checked)}
              />
              {t("contests.cePenaltyCopy")}
            </label>
          </Field>
          <Field label={t("common.status")}>
            <div className="flex h-10 items-center gap-2">
              <StatusChip status={existing?.status ?? "DRAFT"} />
              {existing?.publishedAt ? <span className="text-xs text-[var(--oj-ink-muted)]">{t("contests.publishedAt")}: {formatDateTime(existing.publishedAt)}</span> : null}
            </div>
          </Field>
          <div className="lg:col-span-2">
            <Field label={t("contests.description")}>
              <textarea
                className={`${textareaClass} min-h-28`}
                value={description}
                disabled={!descriptionEditable}
                onChange={(event) => setDescription(event.target.value)}
                placeholder={t("contests.descriptionPlaceholder")}
              />
            </Field>
            {existing?.status === "PUBLISHED" ? (
              <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{t("contests.descriptionOnlyAfterConfirm")}</p>
            ) : null}
          </div>
        </section>

        <Card className="rounded-xl shadow-none">
          <CardBody className="space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.aiPolicySectionTitle")}</h2>
                <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("contests.aiPolicySectionCopy")}</p>
              </div>
              <Badge tone={draftEditable ? "blue" : "neutral"}>{draftEditable ? t("contests.editable") : t("contests.readonly")}</Badge>
            </div>
            <div className="grid gap-4 lg:grid-cols-2">
              <Field label={t("contests.aiPolicyModeLabel")}>
                <select className={selectClass} value={aiPolicyMode} disabled={!draftEditable} onChange={(event) => setAiPolicyMode(event.target.value as ContestAiPolicyMode)}>
                  {AI_POLICY_MODES.map((item) => <option key={item} value={item}>{t(`contests.aiPolicyMode.${item}`)}</option>)}
                </select>
              </Field>
              <div className="lg:col-span-2">
                <Field label={t("contests.aiPolicyNotesLabel")}>
                  <textarea
                    className={`${textareaClass} min-h-24`}
                    value={aiPolicyNotes}
                    disabled={!draftEditable}
                    maxLength={2000}
                    onChange={(event) => setAiPolicyNotes(event.target.value)}
                    placeholder={t("contests.aiPolicyNotesPlaceholder")}
                  />
                </Field>
                <p className="mt-1 text-right text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("contests.aiPolicyNotesCount", { count: aiPolicyNotes.length })}</p>
              </div>
            </div>
          </CardBody>
        </Card>

        <Card className="rounded-xl shadow-none">
          <CardBody className="space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.problemEditor")}</h2>
                <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{draftEditable ? t("contests.problemEditorCopy") : t("contests.problemEditorLocked")}</p>
              </div>
              <Badge tone={draftEditable ? "blue" : "neutral"}>{draftEditable ? t("contests.editable") : t("contests.readonly")}</Badge>
            </div>

            {draftEditable ? (
              <ContestProblemPicker onAdd={addProblems} />
            ) : null}

            {contestProblemsQuery.isLoading ? (
              <LoadingPanel label={t("common.loading")} />
            ) : rows.length ? (
              <TableShell>
                <table className="w-full min-w-[780px] text-sm">
                  <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                    <tr>
                      <th className="px-4 py-3 text-left">{t("contests.label")}</th>
                      <th className="px-4 py-3 text-left">{t("contests.problem")}</th>
                      <th className="px-4 py-3 text-left">{t("contests.displayTitle")}</th>
                      <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[var(--oj-border-soft)]">
                    {rows.map((row, index) => (
                      <tr key={`${row.problemId}-${index}`}>
                        <td className="px-4 py-3">
                          <input
                            className={`${inputClass} h-9 w-20 text-center font-semibold`}
                            value={row.label}
                            disabled={!draftEditable}
                            onChange={(event) => updateRow(setRows, index, { label: event.target.value })}
                          />
                        </td>
                        <td className="px-4 py-3">
                          <div className="font-medium text-[var(--oj-ink)]">#{shortId(row.problemId)}</div>
                          {row.title ? <div className="mt-1 text-xs text-[var(--oj-ink-muted)]">{row.title}</div> : null}
                        </td>
                        <td className="px-4 py-3">
                          <input
                            className={`${inputClass} h-9`}
                            value={row.displayTitle ?? ""}
                            disabled={!draftEditable}
                            onChange={(event) => updateRow(setRows, index, { displayTitle: event.target.value })}
                          />
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex justify-center gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={!draftEditable || index === 0}
                              onClick={() => moveRow(setRows, index, -1)}
                            >
                              {t("contests.moveUp")}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={!draftEditable || index === rows.length - 1}
                              onClick={() => moveRow(setRows, index, 1)}
                            >
                              {t("contests.moveDown")}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              className="text-red-700 hover:bg-red-50"
                              disabled={!draftEditable}
                              onClick={() => setRows((current) => current.filter((_, rowIndex) => rowIndex !== index))}
                            >
                              <Trash2 className="size-4" aria-hidden="true" />
                              {t("common.remove")}
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableShell>
            ) : (
              <EmptyState title={t("contests.noProblemsTitle")} description={t("contests.noProblemsDescription")} />
            )}
          </CardBody>
        </Card>

        <Card className="rounded-xl shadow-none">
          <CardBody>
            <EmptyState title={t("contests.blueprintRunsTitle")} description={t("contests.blueprintRunsDescription")} />
          </CardBody>
        </Card>
      </div>
      </SidePanel>
    </>
  );
}

function ContestRunsPanel({
  contest,
  groups,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  groups: LearningGroupResponse[];
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = React.useState<ContestRunStatus | "">("");
  const [registrationStatusFilter, setRegistrationStatusFilter] = React.useState<ContestRegistrationStatus | "">("");
  const [keyword, setKeyword] = React.useState("");
  const [runsPage, setRunsPage] = React.useState(1);
  const [registrationsPage, setRegistrationsPage] = React.useState(1);
  const [selectedRunId, setSelectedRunId] = React.useState<EntityId>("");
  const [title, setTitle] = React.useState("");
  const [runKind, setRunKind] = React.useState<ContestRunKind>("SIMULATION");
  const [startAt, setStartAt] = React.useState("");
  const [endAt, setEndAt] = React.useState("");
  const [freezeAt, setFreezeAt] = React.useState("");
  const [registrationAccess, setRegistrationAccess] = React.useState<ContestRegistrationAccess>("PUBLIC");
  const [approvalRequired, setApprovalRequired] = React.useState(false);
  const [allowedGroupIds, setAllowedGroupIds] = React.useState<EntityId[]>([]);
  const [registrationStartAt, setRegistrationStartAt] = React.useState("");
  const [registrationEndAt, setRegistrationEndAt] = React.useState("");
  const [maxParticipants, setMaxParticipants] = React.useState("");
  const [inviteDialogRun, setInviteDialogRun] = React.useState<ContestRunResponse | null>(null);
  const [deleteRunTarget, setDeleteRunTarget] = React.useState<ContestRunResponse | null>(null);
  const open = Boolean(contest);
  const contestConfirmed = contest?.status === "PUBLISHED";
  const panelReady = open && contestConfirmed;

  React.useEffect(() => {
    if (!contest) return;
    setStatusFilter("");
    setRegistrationStatusFilter("");
    setKeyword("");
    setSelectedRunId("");
    setTitle(`${contest.title} ${t("contests.defaultRunSuffix")}`);
    setRunKind("SIMULATION");
    const now = new Date();
    now.setMinutes(0, 0, 0);
    const defaultStart = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    const defaultEnd = new Date(defaultStart.getTime() + 5 * 60 * 60 * 1000);
    setStartAt(toDateTimeLocal(defaultStart.toISOString()));
    setEndAt(toDateTimeLocal(defaultEnd.toISOString()));
    setFreezeAt("");
    setRegistrationAccess("PUBLIC");
    setApprovalRequired(false);
    setAllowedGroupIds([]);
    setRegistrationStartAt("");
    setRegistrationEndAt("");
    setMaxParticipants("");
    setInviteDialogRun(null);
  }, [contest?.id, t]);

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, statusFilter, keyword, runsPage],
    queryFn: () => api.contestRuns(contest!.id, {
      page: runsPage,
      pageSize: RUNS_PAGE_SIZE,
      status: statusFilter,
      keyword: keyword.trim()
    }),
    enabled: panelReady
  });

  const runs = runsQuery.data?.records ?? [];
  const runsTotal = runsQuery.data?.total ?? 0;

  React.useEffect(() => {
    if (!selectedRunId && runs.length) setSelectedRunId(runs[0].id);
    if (selectedRunId && runs.length && !runs.some((run) => run.id === selectedRunId)) setSelectedRunId(runs[0].id);
  }, [runs, selectedRunId]);

  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? null;

  const registrationsQuery = useQuery({
    queryKey: ["admin-contest-run-registrations", contest?.id, selectedRunId, registrationStatusFilter, registrationsPage],
    queryFn: () => api.contestRunRegistrations(contest!.id, selectedRunId, {
      page: registrationsPage,
      pageSize: REGISTRATIONS_PAGE_SIZE,
      status: registrationStatusFilter
    }),
    enabled: panelReady && Boolean(selectedRunId)
  });

  const registrationsTotal = registrationsQuery.data?.total ?? 0;

  const createRunMutation = useMutation({
    mutationFn: async () => {
      if (!contest) throw new Error("Contest is required");
      return api.createContestRun(contest.id, {
        title: title.trim(),
        runKind,
        startAt: fromDateTimeLocal(startAt),
        endAt: fromDateTimeLocal(endAt),
        freezeAt: freezeAt ? fromDateTimeLocal(freezeAt) : null,
        registrationAccess,
        approvalRequired,
        allowedGroupIds: registrationAccess !== "GROUPS" ? [] : allowedGroupIds,
        registrationStartAt: !registrationStartAt ? null : fromDateTimeLocal(registrationStartAt),
        registrationEndAt: !registrationEndAt ? null : fromDateTimeLocal(registrationEndAt),
        maxParticipants: !maxParticipants ? null : Number(maxParticipants)
      });
    },
    onSuccess: async (run) => {
      setSelectedRunId(run.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] });
    }
  });

  const publishRunMutation = useMutation({
    mutationFn: async (runId: EntityId) => {
      if (!contest) throw new Error("Contest is required");
      return api.publishContestRun(contest.id, runId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] });
    }
  });

  const archiveRunMutation = useMutation({
    mutationFn: async (runId: EntityId) => {
      if (!contest) throw new Error("Contest is required");
      return api.archiveContestRun(contest.id, runId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] });
    }
  });

  const restoreRunMutation = useMutation({
    mutationFn: async (runId: EntityId) => {
      if (!contest) throw new Error("Contest is required");
      return api.restoreContestRun(contest.id, runId);
    },
    onSuccess: async (run) => {
      setSelectedRunId(run.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] });
    }
  });

  const deleteRunMutation = useMutation({
    mutationFn: async (runId: EntityId) => {
      if (!contest) throw new Error("Contest is required");
      return api.deleteContestRun(contest.id, runId);
    },
    onSuccess: async () => {
      setSelectedRunId("");
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] });
    }
  });

  const approveMutation = useMutation({
    mutationFn: async (registrationId: EntityId) => {
      if (!contest || !selectedRunId) throw new Error("Registration is required");
      return api.approveContestRunRegistration(contest.id, selectedRunId, registrationId);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-contest-run-registrations", contest?.id, selectedRunId] }),
        queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] })
      ]);
    }
  });

  const rejectMutation = useMutation({
    mutationFn: async (registrationId: EntityId) => {
      if (!contest || !selectedRunId) throw new Error("Registration is required");
      return api.rejectContestRunRegistration(contest.id, selectedRunId, registrationId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-run-registrations", contest?.id, selectedRunId] });
    }
  });

  async function createRun() {
    if (!contestConfirmed) {
      toast.error(t("contests.confirmBeforeOperations"));
      return;
    }
    if (!title.trim() || !startAt || !endAt) {
      toast.error(t("contests.runRequiredFields"));
      return;
    }
    if (Boolean(registrationStartAt) !== Boolean(registrationEndAt)) {
      toast.error(t("contests.registrationWindowPairRequired"));
      return;
    }
    if (registrationAccess === "GROUPS" && allowedGroupIds.length === 0) {
      toast.error(t("contests.allowedGroupsRequired"));
      return;
    }
    try {
      await createRunMutation.mutateAsync();
      toast.success(t("contests.runCreatedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.runSaveFailed"));
    }
  }

  async function publishRun(run: ContestRunResponse) {
    try {
      await publishRunMutation.mutateAsync(run.id);
      toast.success(t("contests.runPublishedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.runPublishFailed"));
    }
  }

  async function archiveRun(run: ContestRunResponse) {
    try {
      await archiveRunMutation.mutateAsync(run.id);
      toast.success(t("contests.runArchivedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.runArchiveFailed"));
    }
  }

  async function restoreRun(run: ContestRunResponse) {
    try {
      await restoreRunMutation.mutateAsync(run.id);
      toast.success(t("contests.runRestoredMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.runRestoreFailed"));
    }
  }

  async function approve(registration: ContestRegistrationResponse) {
    try {
      await approveMutation.mutateAsync(registration.id);
      toast.success(t("contests.registrationApprovedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.registrationReviewFailed"));
    }
  }

  async function reject(registration: ContestRegistrationResponse) {
    try {
      await rejectMutation.mutateAsync(registration.id);
      toast.success(t("contests.registrationRejectedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.registrationReviewFailed"));
    }
  }

  function toggleAllowedGroup(groupId: EntityId) {
    setAllowedGroupIds((current) => current.includes(groupId)
      ? current.filter((id) => id !== groupId)
      : [...current, groupId]);
  }

  async function refreshRunRegistrations() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["admin-contest-run-registrations", contest?.id, selectedRunId] }),
      queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] })
    ]);
  }

  return (
    <>
      <SidePanel
      wide
      open={open}
      onOpenChange={onOpenChange}
      title={contest?.title ?? t("contests.runs")}
      description={t("contests.runsCopy")}
      footer={(
        <div className="flex justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
        </div>
      )}
    >
      <div className="space-y-5">
        {!panelReady ? (
          <EmptyState title={t("contests.confirmBeforeOperations")} description={t("contests.confirmBeforeRun")} />
        ) : (
          <>
        <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
          <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.createRun")}</h3>
              <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("contests.createRunCopy")}</p>
            </div>
            <Button disabled={createRunMutation.isPending} onClick={() => void createRun()}>
              <Plus className="size-4" aria-hidden="true" />
              {t("contests.createRun")}
            </Button>
          </div>
          <div className="grid gap-4 xl:grid-cols-2">
            <div className="grid content-start gap-3">
              <Field label={t("common.title")}>
                <input className={inputClass} value={title} onChange={(event) => setTitle(event.target.value)} />
              </Field>
              <Field label={t("contests.runKindLabel")}>
                <select className={selectClass} value={runKind} onChange={(event) => setRunKind(event.target.value as ContestRunKind)}>
                  {RUN_KINDS.map((kind) => <option key={kind} value={kind}>{t(`contests.runKind.${kind}`)}</option>)}
                </select>
              </Field>
              <DateTimeField
                label={t("contests.freezeAt")}
                value={freezeAt}
                onChange={setFreezeAt}
                dateLabel={t("common.date")}
                timeLabel={t("common.time")}
                nowLabel={t("common.now")}
                clearLabel={t("common.clear")}
                defaultTime="13:00"
              />
              <p className="-mt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("contests.freezeAtOptionalHint")}</p>
              <Field label={t("contests.registrationAccessLabel")}>
                <select className={selectClass} value={registrationAccess} onChange={(event) => setRegistrationAccess(event.target.value as ContestRegistrationAccess)}>
                  {REGISTRATION_ACCESSES.map((access) => <option key={access} value={access}>{t(`contests.registrationAccess.${access}`)}</option>)}
                </select>
              </Field>
              <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(140px,0.7fr)]">
                <Field label={t("contests.approvalRequired")}>
                  <label className="flex min-h-10 items-center gap-2 rounded-xl border border-[var(--oj-border)] bg-white px-3 py-2 text-sm text-[var(--oj-ink)]">
                    <input type="checkbox" checked={approvalRequired} onChange={(event) => setApprovalRequired(event.target.checked)} />
                    <span className="text-pretty leading-5">{t("contests.approvalRequiredCopy")}</span>
                  </label>
                </Field>
                <Field label={t("contests.maxParticipants")}>
                  <input className={inputClass} type="number" min={1} value={maxParticipants} onChange={(event) => setMaxParticipants(event.target.value)} placeholder={t("contests.noCapacityLimit")} />
                </Field>
              </div>
            </div>
            <div className="grid content-start gap-3">
              <DateTimeField
                label={t("contests.startAt")}
                value={startAt}
                onChange={setStartAt}
                dateLabel={t("common.date")}
                timeLabel={t("common.time")}
                nowLabel={t("common.now")}
                clearLabel={t("common.clear")}
              />
              <DateTimeField
                label={t("contests.endAt")}
                value={endAt}
                onChange={setEndAt}
                dateLabel={t("common.date")}
                timeLabel={t("common.time")}
                nowLabel={t("common.now")}
                clearLabel={t("common.clear")}
                defaultTime="14:00"
              />
              <DateTimeField
                label={t("contests.registrationStartAt")}
                value={registrationStartAt}
                onChange={setRegistrationStartAt}
                dateLabel={t("common.date")}
                timeLabel={t("common.time")}
                nowLabel={t("common.now")}
                clearLabel={t("common.clear")}
              />
              <DateTimeField
                label={t("contests.registrationEndAt")}
                value={registrationEndAt}
                onChange={setRegistrationEndAt}
                dateLabel={t("common.date")}
                timeLabel={t("common.time")}
                nowLabel={t("common.now")}
                clearLabel={t("common.clear")}
                defaultTime="23:59"
              />
              <p className="-mt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("contests.registrationWindowOptionalHint")}</p>
            </div>
          </div>
          {registrationAccess === "GROUPS" ? (
            <div className="mt-4">
              <Field label={t("contests.allowedGroups")}>
                <div className="grid gap-2 rounded-xl border border-[var(--oj-border)] bg-white p-3 sm:grid-cols-2 xl:grid-cols-3">
                  {groups.map((group) => (
                    <label key={group.id} className="flex min-w-0 items-center gap-2 rounded-lg border border-[var(--oj-border-soft)] px-3 py-2 text-sm">
                      <input type="checkbox" checked={allowedGroupIds.includes(group.id)} onChange={() => toggleAllowedGroup(group.id)} />
                      <span className="truncate" title={group.name}>{group.name}</span>
                    </label>
                  ))}
                </div>
              </Field>
            </div>
          ) : null}
        </section>

        <section className="grid gap-5 xl:grid-cols-[minmax(360px,0.9fr)_minmax(0,1.4fr)]">
          <div className="space-y-3">
            <div className="grid gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 sm:grid-cols-2">
              <select className={selectClass} value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value as ContestRunStatus | ""); setRunsPage(1); }}>
                {RUN_STATUSES.map((status) => <option key={status || "all"} value={status}>{status ? t(`contests.runStatus.${status}`) : t("contests.allRunStatuses")}</option>)}
              </select>
              <input className={inputClass} value={keyword} onChange={(event) => { setKeyword(event.target.value); setRunsPage(1); }} placeholder={t("contests.runKeywordPlaceholder")} />
            </div>
            {runsQuery.isLoading ? (
              <LoadingPanel label={t("common.loading")} />
            ) : runsQuery.isError ? (
              <ErrorPanel title={t("contests.runsLoadFailed")} />
            ) : runs.length ? (
              <div className="grid gap-3">
                {runs.map((run) => (
                  <button
                    key={run.id}
                    type="button"
                    className={`rounded-xl border p-4 text-left transition-colors ${selectedRunId === run.id ? "border-blue-500 bg-blue-50" : "border-[var(--oj-border)] bg-white hover:bg-[var(--oj-surface-muted)]"}`}
                    onClick={() => setSelectedRunId(run.id)}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate text-sm font-semibold text-[var(--oj-ink)]" title={run.title}>{run.title}</div>
                        <div className="mt-2 flex flex-wrap gap-2">
                          <RunStatusChip status={run.status} />
                          <Badge tone="neutral">{t(`contests.runKind.${run.runKind}`)}</Badge>
                        </div>
                      </div>
                    </div>
                    <div className="mt-3 text-xs leading-5 tabular-nums text-[var(--oj-ink-muted)]">
                      <div>{formatDateTime(run.startAt)} - {formatDateTime(run.endAt)}</div>
                      <div>{t(`contests.registrationAccess.${run.registrationAccess}`)} · {run.approvalRequired ? t("contests.approvalRequiredShort") : t("contests.noApprovalRequiredShort")}</div>
                    </div>
                  </button>
                ))}
              </div>
            ) : (
              <EmptyState title={t("contests.noRunsTitle")} description={t("contests.noRunsDescription")} />
            )}
            {runsTotal > 0 ? (
              <PaginationRow
                page={runsPage}
                totalPages={Math.max(1, Math.ceil(runsTotal / RUNS_PAGE_SIZE))}
                total={runsTotal}
                onPageChange={setRunsPage}
              />
            ) : null}
          </div>

          <div className="space-y-4 rounded-xl border border-[var(--oj-border)] bg-white p-4">
            {selectedRun ? (
              <>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="truncate text-base font-semibold text-[var(--oj-ink)]" title={selectedRun.title}>{selectedRun.title}</h3>
                    <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(selectedRun.id)}</p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {selectedRun.status === "DRAFT" ? (
                      <Button size="sm" disabled={publishRunMutation.isPending} onClick={() => void publishRun(selectedRun)}>
                        <Send className="size-4" aria-hidden="true" />
                        {t("contests.publishRun")}
                      </Button>
                    ) : null}
                    {selectedRun.status !== "ARCHIVED" ? (
                      <Button size="sm" variant="outline" disabled={archiveRunMutation.isPending} onClick={() => void archiveRun(selectedRun)}>
                        <Archive className="size-4" aria-hidden="true" />
                        {t("contests.archiveRun")}
                      </Button>
                    ) : null}
                    {selectedRun.status === "ARCHIVED" ? (
                      <Button size="sm" variant="outline" disabled={restoreRunMutation.isPending} onClick={() => void restoreRun(selectedRun)}>
                        <RotateCw className="size-4" aria-hidden="true" />
                        {t("contests.restoreRun")}
                      </Button>
                    ) : null}
                    {selectedRun.status === "ARCHIVED" ? (
                      <Button size="sm" variant="outline" disabled={deleteRunMutation.isPending} onClick={() => setDeleteRunTarget(selectedRun)}>
                        <Trash2 className="size-4" aria-hidden="true" />
                        {t("contests.deleteRun")}
                      </Button>
                    ) : null}
                  </div>
                </div>
                <div className="grid gap-3 md:grid-cols-3">
                  <InfoPill label={t("contests.startAt")} value={formatDateTime(selectedRun.startAt)} />
                  <InfoPill label={t("contests.endAt")} value={formatDateTime(selectedRun.endAt)} />
                  <InfoPill label={t("contests.registrationAccessLabel")} value={t(`contests.registrationAccess.${selectedRun.registrationAccess}`)} />
                  <InfoPill label={t("contests.maxParticipants")} value={selectedRun.maxParticipants ? String(selectedRun.maxParticipants) : t("contests.noCapacityLimit")} />
                  <InfoPill label={t("contests.aiPolicyModeLabel")} value={t(`contests.aiPolicyMode.${selectedRun.aiPolicyModeSnapshot ?? contest?.aiPolicyMode ?? "DEFAULT"}`)} />
                </div>
                {(selectedRun.aiPolicyNotesSnapshot ?? contest?.aiPolicyNotes) ? (
                  <div className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-4">
                    <div className="text-xs font-medium text-[var(--oj-ink-muted)]">{t("contests.aiPolicyNotesLabel")}</div>
                    <div className="mt-1 whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink)]">{selectedRun.aiPolicyNotesSnapshot ?? contest?.aiPolicyNotes}</div>
                  </div>
                ) : null}
                {selectedRun.status === "EXPIRED" ? (
                  <section className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm leading-6 text-red-900">
                    {t("contests.expiredRunArchiveOnly")}
                  </section>
                ) : selectedRun.registrationAccess === "INVITE_ONLY" ? (
                  <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                    <div className="flex flex-wrap items-end justify-between gap-3">
                      <div>
                      <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.inviteParticipant")}</h4>
                      <p className="mt-1 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("contests.inviteParticipantCopy")}</p>
                      </div>
                      <Button onClick={() => setInviteDialogRun(selectedRun)}>
                        <UserCheck className="size-4" aria-hidden="true" />
                        {t("contests.inviteParticipant")}
                      </Button>
                    </div>
                    {selectedRun.status === "DRAFT" ? (
                      <p className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">
                        {t("contests.inviteDraftDeferredCopy")}
                      </p>
                    ) : null}
                  </section>
                ) : null}
                <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--oj-border-soft)] pt-4">
                  <div>
                    <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.registrations")}</h4>
                    <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("contests.registrationsCopy")}</p>
                  </div>
                  <select className={`${selectClass} w-full sm:w-56`} value={registrationStatusFilter} onChange={(event) => { setRegistrationStatusFilter(event.target.value as ContestRegistrationStatus | ""); setRegistrationsPage(1); }}>
                    {REGISTRATION_STATUSES.map((status) => <option key={status || "all"} value={status}>{status ? t(`contests.registrationStatus.${status}`) : t("contests.allRegistrationStatuses")}</option>)}
                  </select>
                </div>
                {registrationsQuery.isLoading ? (
                  <LoadingPanel label={t("common.loading")} />
                ) : registrationsQuery.isError ? (
                  <ErrorPanel title={t("contests.registrationsLoadFailed")} />
                ) : registrationsQuery.data?.records.length ? (
                  <div className="grid gap-3">
                    {registrationsQuery.data.records.map((registration) => (
                      <article key={registration.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <div className="truncate text-sm font-semibold text-[var(--oj-ink)]">{registration.displayName || registration.account || `#${registration.userId}`}</div>
                            <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">{registration.account || "--"} · #{shortId(registration.userId)}</div>
                          </div>
                          <RegistrationStatusChip status={registration.status} />
                        </div>
                        <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
                          <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(registration.requestedAt)}</span>
                          {selectedRun.status !== "EXPIRED" && registration.status === "PENDING" ? (
                            <div className="flex gap-2">
                              <Button size="sm" disabled={approveMutation.isPending} onClick={() => void approve(registration)}>
                                <Check className="size-4" aria-hidden="true" />
                                {t("contests.approveRegistration")}
                              </Button>
                              <Button size="sm" variant="outline" disabled={rejectMutation.isPending} onClick={() => void reject(registration)}>
                                <Trash2 className="size-4" aria-hidden="true" />
                                {t("contests.rejectRegistration")}
                              </Button>
                            </div>
                          ) : null}
                        </div>
                      </article>
                    ))}
                  </div>
                ) : (
                  <EmptyState title={t("contests.noRegistrationsTitle")} description={t("contests.noRegistrationsDescription")} />
                )}
                {registrationsTotal > 0 ? (
                  <PaginationRow
                    page={registrationsPage}
                    totalPages={Math.max(1, Math.ceil(registrationsTotal / REGISTRATIONS_PAGE_SIZE))}
                    total={registrationsTotal}
                    onPageChange={setRegistrationsPage}
                  />
                ) : null}
              </>
            ) : (
              <EmptyState title={t("contests.selectRunTitle")} description={t("contests.selectRunDescription")} />
            )}
          </div>
        </section>
          </>
        )}
      </div>
      </SidePanel>
      <ContestInviteUsersDialog
        contest={contest}
        run={inviteDialogRun}
        onOpenChange={(dialogOpen) => !dialogOpen && setInviteDialogRun(null)}
        onCompleted={refreshRunRegistrations}
      />
      <ConfirmDialog
        open={Boolean(deleteRunTarget)}
        onOpenChange={(dialogOpen) => !dialogOpen && setDeleteRunTarget(null)}
        title={t("contests.deleteRun")}
        description={deleteRunTarget ? `${deleteRunTarget.title}\n${t("contests.deleteRunConfirm")}` : t("contests.deleteRunConfirm")}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.deleteRun")}
        onConfirm={async () => {
          if (!deleteRunTarget) return;
          try {
            await deleteRunMutation.mutateAsync(deleteRunTarget.id);
            toast.success(t("contests.runDeletedMessage"));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.runDeleteFailed"));
          } finally {
            setDeleteRunTarget(null);
          }
        }}
      />
    </>
  );
}

const INVITATION_USER_PAGE_SIZE = 20;
const MAX_INVITATION_BATCH_SIZE = 100;

function ContestInviteUsersDialog({
  contest,
  run,
  onOpenChange,
  onCompleted
}: {
  contest: ContestResponse | undefined;
  run: ContestRunResponse | null;
  onOpenChange: (open: boolean) => void;
  onCompleted: () => Promise<void>;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const [keyword, setKeyword] = React.useState("");
  const [page, setPage] = React.useState(1);
  const [selectedUsers, setSelectedUsers] = React.useState<Map<EntityId, AdminUserResponse>>(new Map());
  const [result, setResult] = React.useState<ContestInvitationBatchResponse | null>(null);
  const open = Boolean(contest && run);
  const trimmedKeyword = keyword.trim();

  React.useEffect(() => {
    setKeyword("");
    setPage(1);
    setSelectedUsers(new Map());
    setResult(null);
  }, [run?.id]);

  const usersQuery = useQuery({
    queryKey: ["contest-invitation-user-directory", run?.id, page, trimmedKeyword],
    queryFn: () => api.users({
      page,
      pageSize: INVITATION_USER_PAGE_SIZE,
      keyword: trimmedKeyword || undefined,
      enabled: true,
      lifecycle: "ACTIVE"
    }),
    enabled: open
  });
  const users = usersQuery.data?.records ?? [];
  const total = usersQuery.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / INVITATION_USER_PAGE_SIZE));
  const allPageSelected = users.length > 0 && users.every((user) => selectedUsers.has(user.userId));

  const inviteMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !run) throw new Error("Contest run is required");
      return api.inviteContestRunRegistrationsBatch(contest.id, run.id, {
        userIds: [...selectedUsers.keys()]
      });
    },
    onSuccess: async (response) => {
      setResult(response);
      setSelectedUsers((current) => new Map(
        [...current].filter(([userId]) => response.results.some((item) => item.userId === userId && item.status === "FAILED"))
      ));
      await onCompleted();
      if (response.succeeded > 0) {
        toast.success(t("contests.inviteBatchCompleted", { count: response.succeeded }));
      }
    }
  });

  function toggleUser(user: AdminUserResponse) {
    if (!selectedUsers.has(user.userId) && selectedUsers.size >= MAX_INVITATION_BATCH_SIZE) {
      toast.error(t("contests.inviteBatchLimit", { count: MAX_INVITATION_BATCH_SIZE }));
      return;
    }
    setResult(null);
    setSelectedUsers((current) => {
      const next = new Map(current);
      if (next.has(user.userId)) {
        next.delete(user.userId);
      } else {
        next.set(user.userId, user);
      }
      return next;
    });
  }

  function toggleCurrentPage() {
    setResult(null);
    if (allPageSelected) {
      setSelectedUsers((current) => {
        const next = new Map(current);
        users.forEach((user) => next.delete(user.userId));
        return next;
      });
      return;
    }
    const candidates = users.filter((user) => !selectedUsers.has(user.userId));
    const capacity = Math.max(0, MAX_INVITATION_BATCH_SIZE - selectedUsers.size);
    if (capacity < candidates.length) {
      toast.error(t("contests.inviteBatchLimit", { count: MAX_INVITATION_BATCH_SIZE }));
    }
    setSelectedUsers((current) => {
      const next = new Map(current);
      candidates.slice(0, capacity).forEach((user) => next.set(user.userId, user));
      return next;
    });
  }

  async function submitInvitations() {
    if (!selectedUsers.size) return;
    try {
      await inviteMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.inviteParticipantFailed"));
    }
  }

  return (
    <SidePanel
      presentation="workspace"
      workspaceSize="lg"
      workspaceHeight="fixed"
      open={open}
      onOpenChange={onOpenChange}
      title={t("contests.inviteUsersDialogTitle")}
      description={run?.status === "DRAFT" ? t("contests.inviteDraftDeferredCopy") : t("contests.inviteUsersDialogCopy")}
      footer={(
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm text-[var(--oj-ink-muted)]">{t("contests.inviteSelectedCount", { count: selectedUsers.size })}</span>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
            <Button disabled={!selectedUsers.size || inviteMutation.isPending} onClick={() => void submitInvitations()}>
              <UserCheck className="size-4" aria-hidden="true" />
              {inviteMutation.isPending ? t("contests.inviteBatchSending") : t("contests.inviteParticipant")}
            </Button>
          </div>
        </div>
      )}
    >
      <div className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row">
          <input
            className={inputClass}
            value={keyword}
            onChange={(event) => {
              setKeyword(event.target.value);
              setPage(1);
              setResult(null);
            }}
            placeholder={t("contests.inviteUserSearchPlaceholder")}
          />
          <Button variant="outline" onClick={() => void usersQuery.refetch()} disabled={usersQuery.isFetching}>
            <Search className="size-4" aria-hidden="true" />
            {t("common.refresh")}
          </Button>
        </div>

        {usersQuery.isLoading ? (
          <LoadingPanel label={t("common.loading")} />
        ) : usersQuery.isError ? (
          <ErrorPanel title={t("adminUsers.loadFailed")} action={<Button variant="outline" onClick={() => void usersQuery.refetch()}>{t("common.refresh")}</Button>} />
        ) : users.length === 0 ? (
          <EmptyState title={t("contests.inviteUsersEmptyTitle")} description={t("contests.inviteUsersEmptyCopy")} />
        ) : (
          <TableShell>
            <table className="w-full min-w-[760px] text-sm">
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="w-12 px-4 py-3 text-left">
                    <input
                      type="checkbox"
                      aria-label={t("contests.inviteSelectPage")}
                      checked={allPageSelected}
                      onChange={toggleCurrentPage}
                    />
                  </th>
                  <th className="px-4 py-3 text-left">{t("common.account")}</th>
                  <th className="px-4 py-3 text-left">{t("common.displayName")}</th>
                  <th className="px-4 py-3 text-left">{t("common.email")}</th>
                  <th className="px-4 py-3 text-left">{t("common.roles")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)] bg-white">
                {users.map((user) => {
                  const selected = selectedUsers.has(user.userId);
                  return (
                    <tr
                      key={user.userId}
                      className={`cursor-pointer transition-colors ${selected ? "bg-blue-50" : "hover:bg-[var(--oj-surface-muted)]"}`}
                      onClick={(event) => {
                        if (shouldToggleRowSelection(event)) toggleUser(user);
                      }}
                    >
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          aria-label={t("contests.inviteSelectUser", { account: user.account })}
                          checked={selected}
                          onChange={() => toggleUser(user)}
                        />
                      </td>
                      <td className="px-4 py-3 font-medium text-[var(--oj-ink)]">{user.account}</td>
                      <td className="px-4 py-3 text-[var(--oj-ink)]">{user.displayName}</td>
                      <td className="px-4 py-3 text-[var(--oj-ink-muted)]">{user.email || "--"}</td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap gap-1.5">
                          {user.roles.map((role) => <Badge key={role} tone={role === "ADMIN" ? "red" : role === "TEACHER" ? "blue" : "green"}>{t(`role.${role}`)}</Badge>)}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableShell>
        )}

        {total > 0 ? (
          <PaginationRow page={page} totalPages={totalPages} total={total} onPageChange={setPage} />
        ) : null}

        {result ? <ContestInvitationBatchResultPanel result={result} /> : null}
      </div>
    </SidePanel>
  );
}

function ContestInvitationBatchResultPanel({ result }: { result: ContestInvitationBatchResponse }) {
  const { t } = useI18n();
  return (
    <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.inviteBatchResults")}</h3>
        <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">
          {t("contests.inviteBatchSummary", { requested: result.requested, succeeded: result.succeeded, failed: result.failed })}
        </span>
      </div>
      <div className="mt-3 grid gap-2">
        {result.results.map((item) => (
          <div key={item.userId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-white px-3 py-2 text-sm">
            <span className="min-w-0 truncate text-[var(--oj-ink)]" title={item.displayName || item.account || item.userId}>
              {item.displayName || item.account || `#${shortId(item.userId)}`}
              {item.account && item.displayName ? <span className="ml-2 text-xs text-[var(--oj-ink-muted)]">{item.account}</span> : null}
            </span>
            <InvitationBatchStatusChip status={item.status} />
          </div>
        ))}
      </div>
    </section>
  );
}

function InvitationBatchStatusChip({ status }: { status: ContestInvitationBatchResponse["results"][number]["status"] }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "QUEUED_FOR_NOTIFICATION" ? "blue"
    : status === "SAVED_FOR_PUBLISH" ? "amber"
      : status === "FAILED" ? "red"
        : "neutral";
  return <Badge className="whitespace-nowrap" tone={tone}>{t(`contests.inviteBatchStatus.${status}`)}</Badge>;
}

function ContestScoreboardPanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [view, setView] = React.useState<ContestScoreboardView>("PRIVATE");
  const [runId, setRunId] = React.useState<EntityId | "">("");
  const [atMillis, setAtMillis] = React.useState("");
  const [timelineIndex, setTimelineIndex] = React.useState<number | null>(null);
  const [snapshotId, setSnapshotId] = React.useState<EntityId>("");
  const [pendingFreezeAction, setPendingFreezeAction] = React.useState<"unfreeze" | "refreeze" | null>(null);
  const open = Boolean(contest);

  React.useEffect(() => {
    if (!contest) return;
    setView("PRIVATE");
    setRunId("");
    setAtMillis("");
    setTimelineIndex(null);
    setSnapshotId("");
  }, [contest]);

  React.useEffect(() => {
    setSnapshotId("");
    setTimelineIndex(null);
  }, [runId]);

  React.useEffect(() => {
    setTimelineIndex(null);
  }, [view]);

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "scoreboard-selector"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 50 }),
    enabled: open
  });
  const runs = runsQuery.data?.records ?? [];
  const requiresRun = Boolean(contest && (!contest.startAt || !contest.endAt || runs.length > 0));
  const scoreboardScopeReady = open && !runsQuery.isLoading && (!requiresRun || Boolean(runId));
  const selectedRun = runs.find((run) => run.id === runId) ?? null;

  React.useEffect(() => {
    if (!runId || runs.some((run) => run.id === runId)) {
      return;
    }
    setRunId("");
  }, [runId, runs]);

  const timelineQuery = useQuery({
    queryKey: ["admin-contest-scoreboard-timeline", contest?.id, runId, view],
    queryFn: () => api.contestScoreboardTimeline(contest!.id, runId, { view }),
    enabled: open && Boolean(runId) && !snapshotId,
    refetchInterval: (query) => {
      if (query.state.data?.status === "READY" || query.state.data?.status === "FAILED") return false;
      return steadyQueryRefetchInterval(query, true, 3000, 5000);
    }
  });
  const timelineTicks = timelineQuery.data?.ticks ?? [];
  const selectedTimelineTick = timelineIndex == null ? null : timelineTicks[timelineIndex] ?? null;
  const replaySnapshotId = snapshotId || selectedTimelineTick?.snapshotId || "";

  const scoreboardQuery = useQuery({
    queryKey: ["admin-contest-scoreboard", contest?.id, runId, view, atMillis, replaySnapshotId],
    queryFn: () => {
      if (!contest) throw new Error("Contest is required");
      return api.contestScoreboard(contest.id, replaySnapshotId
        ? { runId, snapshotId: replaySnapshotId }
        : { runId, view, atMillis: parseOptionalMillis(atMillis) });
    },
    enabled: scoreboardScopeReady,
    refetchInterval: (query) => {
      if (replaySnapshotId || atMillis.trim()) return false;
      if (query.state.data?.frozen) return false;
      const ended = selectedRun ? Date.now() >= new Date(selectedRun.endAt).getTime() : false;
      return steadyQueryRefetchInterval(query, !ended, 5000, 15000);
    },
    staleTime: replaySnapshotId ? 30 * 60_000 : 0,
    gcTime: replaySnapshotId ? 60 * 60_000 : 5 * 60_000
  });

  const snapshotsQuery = useQuery({
    queryKey: ["admin-contest-scoreboard-snapshots", contest?.id, runId],
    queryFn: () => api.contestScoreboardSnapshots(contest!.id, { runId }),
    enabled: scoreboardScopeReady
  });

  const createSnapshotMutation = useMutation({
    mutationFn: async () => {
      if (!contest) throw new Error("Contest is required");
      const parsedAtMillis = parseOptionalMillis(atMillis);
      return api.createContestScoreboardSnapshot(contest.id, {
        snapshotKind: "MANUAL",
        view,
        atMillis: parsedAtMillis === "" ? null : parsedAtMillis
      }, { runId });
    },
    onSuccess: async (scoreboard) => {
      if (scoreboard.snapshotId) setSnapshotId(scoreboard.snapshotId);
      toast.success(t("contests.snapshotCreatedMessage"));
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-scoreboard-snapshots", contest?.id] });
    }
  });

  const finalizeMutation = useMutation({
    mutationFn: async () => {
      if (!contest) throw new Error("Contest is required");
      return api.finalizeContest(contest.id, { runId });
    },
    onSuccess: async (scoreboard) => {
      if (scoreboard.snapshotId) setSnapshotId(scoreboard.snapshotId);
      toast.success(t("contests.finalizeSucceededMessage"));
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-scoreboard-snapshots", contest?.id] });
    }
  });

  const handleExportAutoDownloadCompleted = React.useCallback(() => {
    toast.success(t("operations.jobAutoDownloadCompleted"));
    void queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
  }, [queryClient, t, toast]);

  const handleExportAutoDownloadFailed = React.useCallback(() => {
    toast.error(t("operations.jobAutoDownloadFailed"));
  }, [t, toast]);

  const startExportAutoDownload = useOperationJobAutoDownload({
    contestId: contest?.id,
    onCompleted: handleExportAutoDownloadCompleted,
    onFailed: handleExportAutoDownloadFailed
  });

  const exportScoreboardMutation = useMutation({
    mutationFn: async (format: ContestExportFormat) => {
      if (!contest) throw new Error("Contest is required");
      const params = replaySnapshotId
        ? { format, runId, snapshotId: replaySnapshotId }
        : { format, runId, view, atMillis: parseOptionalMillis(atMillis) };
      return api.createContestScoreboardExportJob(contest.id, params);
    },
    onSuccess: async (job) => {
      toast.success(t("operations.jobQueuedAutoDownload"));
      startExportAutoDownload(job.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });

  const publicFreezeMutation = useMutation({
    mutationFn: async (action: "unfreeze" | "refreeze") => {
      if (!contest || !runId) throw new Error("Contest run is required");
      return action === "unfreeze"
        ? api.unfreezePublicScoreboard(contest.id, runId)
        : api.refreezePublicScoreboard(contest.id, runId);
    },
    onSuccess: async (run, action) => {
      toast.success(t(action === "unfreeze" ? "contests.unfreezePublicScoreboardMessage" : "contests.refreezePublicScoreboardMessage"));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-contest-runs", contest?.id] }),
        queryClient.invalidateQueries({ queryKey: ["admin-contest-scoreboard", contest?.id, run.id] }),
        queryClient.invalidateQueries({ queryKey: ["admin-contest-scoreboard-timeline", contest?.id, run.id, "PUBLIC"] })
      ]);
    }
  });

  async function createSnapshot() {
    if (requiresRun && !runId) {
      toast.error(t("contests.scoreboardRunRequired"));
      return;
    }
    try {
      await createSnapshotMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.snapshotCreateFailed"));
    }
  }

  async function finalizeContest() {
    if (requiresRun && !runId) {
      toast.error(t("contests.scoreboardRunRequired"));
      return;
    }
    try {
      await finalizeMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.finalizeFailed"));
    }
  }

  async function exportScoreboard(format: ContestExportFormat) {
    if (requiresRun && !runId) {
      toast.error(t("contests.scoreboardRunRequired"));
      return;
    }
    try {
      await exportScoreboardMutation.mutateAsync(format);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.exportFailed"));
    }
  }

  function requestPublicFreezeToggle() {
    if (!selectedRun) return;
    if (!selectedRun.freezeAt) {
      toast.error(t("contests.publicNoFreezeNotice"));
      return;
    }
    setPendingFreezeAction(selectedRun.publicScoreboardUnfrozenAt ? "refreeze" : "unfreeze");
  }

  async function confirmPublicFreezeToggle(action: "unfreeze" | "refreeze") {
    try {
      await publicFreezeMutation.mutateAsync(action);
    } catch (caught) {
      toast.error(caught instanceof ApiError
        ? caught.userMessage
        : action === "unfreeze"
          ? t("contests.unfreezePublicScoreboardFailed")
          : t("contests.refreezePublicScoreboardFailed"));
    } finally {
      setPendingFreezeAction(null);
    }
  }

  const scoreboard = scoreboardQuery.data;
  const selectedRunHasFreeze = Boolean(selectedRun?.freezeAt);

  return (
    <SidePanel
      wide
      open={open}
      onOpenChange={onOpenChange}
      title={contest?.title ?? t("contests.scoreboard")}
      description={t("contests.scoreboardCopy")}
      footer={(
        <div className="flex justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
        </div>
      )}
    >
      <div className="space-y-5">
        <section className="space-y-3 rounded-xl border border-[var(--oj-border)] bg-white p-4">
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-[minmax(180px,1fr)_160px_minmax(180px,1fr)_180px]">
            <select className={selectClass} value={runId} onChange={(event) => setRunId(event.target.value)}>
              <option value="" disabled={requiresRun}>{requiresRun ? t("contests.selectRunForScoreboard") : t("contests.allRuns")}</option>
              {runs.map((run) => (
                <option key={run.id} value={run.id}>{formatRunSelectorLabel(run)}</option>
              ))}
            </select>
            <select className={selectClass} value={view} disabled={Boolean(snapshotId)} onChange={(event) => setView(event.target.value as ContestScoreboardView)}>
              <option value="PRIVATE">{t("contests.scoreboardViewPrivate")}</option>
              <option value="PUBLIC">{t("contests.scoreboardViewPublic")}</option>
            </select>
            <input
              className={inputClass}
              type="number"
              min={0}
              value={atMillis}
              disabled={Boolean(snapshotId)}
              onChange={(event) => {
                setTimelineIndex(null);
                setAtMillis(event.target.value);
              }}
              placeholder={t("contests.scoreboardAtMillis")}
            />
            <select
              className={selectClass}
              value={snapshotId}
              onChange={(event) => {
                setTimelineIndex(null);
                setSnapshotId(event.target.value);
              }}
            >
              <option value="">{t("contests.liveScoreboard")}</option>
              {snapshotsQuery.data?.map((snapshot) => (
                <option key={snapshot.id} value={snapshot.id}>
                  {t(`contests.snapshotKind.${snapshot.snapshotKind}`)} · {formatContestClock(snapshot.contestTimeMillis)}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <Button className="shrink-0 whitespace-nowrap" variant="outline" disabled={createSnapshotMutation.isPending || Boolean(snapshotId) || (requiresRun && !runId)} onClick={() => void createSnapshot()}>
              <Save className="size-4" aria-hidden="true" />
              {t("contests.createSnapshot")}
            </Button>
            <Button className="shrink-0 whitespace-nowrap" disabled={finalizeMutation.isPending || (requiresRun && !runId)} onClick={() => void finalizeContest()}>
              <CheckCircle2 className="size-4" aria-hidden="true" />
              {t("contests.finalize")}
            </Button>
          </div>
        </section>
        {runId && !snapshotId && timelineQuery.data && timelineQuery.data.status !== "READY" ? (
          <ErrorPanel
            title={t(timelineQuery.data.status === "FAILED" ? "contests.scoreboardTimelineFailed" : "contests.scoreboardTimelineGenerating")}
            description={timelineQuery.data.message || t("contests.scoreboardTimelineGeneratingCopy")}
          />
        ) : null}
        {runId && !snapshotId && timelineTicks.length ? (
          <AdminScoreboardTimeline
            ticks={timelineTicks}
            currentIndex={timelineIndex}
            onChange={(index) => {
              setTimelineIndex(index);
              const tick = index == null ? null : timelineTicks[index];
              setAtMillis(tick ? String(tick.bucketMillis) : "");
            }}
          />
        ) : null}
        {selectedRun && !snapshotId && new Date(selectedRun.endAt).getTime() <= Date.now() ? (
          <section className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:flex-row md:items-center md:justify-between">
            <div>
              <h3 className="text-sm font-semibold text-[var(--oj-ink)]">
                {selectedRunHasFreeze
                  ? selectedRun.publicScoreboardUnfrozenAt
                    ? t("contests.publicUnfrozen")
                    : t("contests.publicFrozen")
                  : t("contests.publicNoFreeze")}
              </h3>
              <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
                {selectedRunHasFreeze
                  ? selectedRun.publicScoreboardUnfrozenAt
                    ? t("contests.unfrozenNotice")
                    : t("contests.frozenNotice")
                  : t("contests.publicNoFreezeNotice")}
              </p>
            </div>
            <Button
              variant="outline"
              disabled={publicFreezeMutation.isPending || !selectedRunHasFreeze}
              onClick={() => requestPublicFreezeToggle()}
            >
              <RotateCw className="size-4" aria-hidden="true" />
              {selectedRunHasFreeze && selectedRun.publicScoreboardUnfrozenAt ? t("contests.refreezePublicScoreboard") : t("contests.unfreezePublicScoreboard")}
            </Button>
          </section>
        ) : null}
        <section className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:flex-row md:items-center md:justify-between">
          <div>
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.exportScoreboard")}</h3>
            <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("contests.exportScoreboardCopy")}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={exportScoreboardMutation.isPending || (requiresRun && !runId)} onClick={() => void exportScoreboard("CSV")}>
              <Download className="size-4" aria-hidden="true" />
              {t("contests.exportCsv")}
            </Button>
            <Button variant="outline" disabled={exportScoreboardMutation.isPending || (requiresRun && !runId)} onClick={() => void exportScoreboard("XLSX")}>
              <Download className="size-4" aria-hidden="true" />
              {t("contests.exportXlsx")}
            </Button>
          </div>
        </section>

        {requiresRun && !runId ? (
          <EmptyState title={t("contests.scoreboardRunRequired")} description={t("contests.scoreboardRunRequiredCopy")} />
        ) : scoreboardQuery.isLoading ? (
          <LoadingPanel label={t("common.loading")} />
        ) : scoreboardQuery.isError || !scoreboard ? (
          <ErrorPanel title={t("contests.scoreboardLoadFailed")} action={<Button variant="outline" onClick={() => void scoreboardQuery.refetch()}>{t("common.refresh")}</Button>} />
        ) : (
          <>
            <section className="grid gap-3 md:grid-cols-3">
              <InfoPill label={t("contests.atContestTime")} value={formatContestClock(scoreboard.atContestMillis)} />
              <InfoPill label={t("contests.generatedAt")} value={formatDateTime(scoreboard.generatedAt)} />
              <InfoPill
                label={t("contests.scoringRules")}
                value={scoreboard.mode === "IOI"
                  ? t("contests.ioiScoringNote")
                  : `${scoreboard.penaltyMinutes} ${t("contests.penaltyMinutesShort")} · ${scoreboard.cePenalty ? t("contests.cePenaltyOn") : t("contests.cePenaltyOff")}`}
              />
            </section>
            {view === "PUBLIC" && selectedRun?.freezeAt && selectedRun.publicScoreboardUnfrozenAt ? (
              <ErrorPanel title={t("contests.unfrozenNotice")} tone="success" />
            ) : selectedRun?.freezeAt && scoreboard.frozen ? (
              <ErrorPanel title={t("contests.frozenNotice")} />
            ) : null}
            <ContestScoreboardTable
              mode={scoreboard.mode}
              problems={scoreboard.problems}
              rows={scoreboard.rows}
              labels={scoreboardLabels(t)}
            />
            <AdminResolverReplaySection contest={contest} run={selectedRun} runId={runId} />
          </>
        )}
      </div>
      <ConfirmDialog
        open={pendingFreezeAction !== null}
        onOpenChange={(dialogOpen) => !dialogOpen && setPendingFreezeAction(null)}
        title={pendingFreezeAction === "refreeze" ? t("contests.refreezeConfirmTitle") : t("contests.unfreezeConfirmTitle")}
        description={pendingFreezeAction === "refreeze" ? t("contests.refreezeConfirmCopy") : t("contests.unfreezeConfirmCopy")}
        cancelLabel={t("common.cancel")}
        confirmLabel={pendingFreezeAction === "refreeze" ? t("contests.refreezePublicScoreboard") : t("contests.unfreezePublicScoreboard")}
        onConfirm={() => {
          if (!pendingFreezeAction) return;
          return confirmPublicFreezeToggle(pendingFreezeAction);
        }}
      />
    </SidePanel>
  );
}

function AdminResolverReplaySection({
  contest,
  run,
  runId
}: {
  contest: ContestResponse | undefined;
  run?: ContestRunResponse | null;
  runId: EntityId | "";
}) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [selectedSessionId, setSelectedSessionId] = React.useState<EntityId>("");
  const [deleteSessionTarget, setDeleteSessionTarget] = React.useState<ContestResolverSessionResponse | null>(null);

  React.useEffect(() => {
    setSelectedSessionId("");
  }, [contest?.id, runId]);

  const sessionsQuery = useQuery({
    queryKey: ["admin-contest-resolver-sessions", contest?.id, runId],
    queryFn: () => api.contestResolverSessions(contest!.id, runId),
    enabled: Boolean(contest && runId)
  });

  React.useEffect(() => {
    if (!selectedSessionId && sessionsQuery.data?.length) {
      setSelectedSessionId(sessionsQuery.data[0].id);
    }
  }, [selectedSessionId, sessionsQuery.data]);

  const detailQuery = useQuery({
    queryKey: ["admin-contest-resolver-session", contest?.id, runId, selectedSessionId],
    queryFn: () => api.contestResolverSession(contest!.id, runId, selectedSessionId),
    enabled: Boolean(contest && runId && selectedSessionId)
  });
  const selectedSession = sessionsQuery.data?.find((session) => session.id === selectedSessionId) ?? null;
  const resolverMissingFreeze = Boolean(runId && run && !run.freezeAt);

  const createMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId) throw new Error("Contest run is required");
      return api.createContestResolverSession(contest.id, runId);
    },
    onSuccess: async (detail) => {
      setSelectedSessionId(detail.session.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-sessions", contest?.id, runId] });
    }
  });

  const publishMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId || !selectedSessionId) throw new Error("Resolver session is required");
      return api.publishContestResolverSession(contest.id, runId, selectedSessionId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-sessions", contest?.id, runId] });
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-session", contest?.id, runId, selectedSessionId] });
    }
  });

  const archiveMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId || !selectedSessionId) throw new Error("Resolver session is required");
      return api.archiveContestResolverSession(contest.id, runId, selectedSessionId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-sessions", contest?.id, runId] });
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-session", contest?.id, runId, selectedSessionId] });
    }
  });

  const restoreMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId || !selectedSessionId) throw new Error("Resolver session is required");
      return api.restoreContestResolverSession(contest.id, runId, selectedSessionId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-sessions", contest?.id, runId] });
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-session", contest?.id, runId, selectedSessionId] });
    }
  });

  const deleteMutation = useMutation({
    mutationFn: async (sessionId: EntityId) => {
      if (!contest || !runId) throw new Error("Resolver session is required");
      return api.deleteContestResolverSession(contest.id, runId, sessionId);
    },
    onSuccess: async (_deleted, sessionId) => {
      if (selectedSessionId === sessionId) {
        setSelectedSessionId("");
      }
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-resolver-sessions", contest?.id, runId] });
    }
  });

  async function createSession() {
    if (resolverMissingFreeze) {
      toast.error(t("contests.resolverNoFreeze"));
      return;
    }
    try {
      await createMutation.mutateAsync();
      toast.success(t("contests.resolverCreatedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.resolverCreateFailed"));
    }
  }

  async function publishSession() {
    try {
      await publishMutation.mutateAsync();
      toast.success(t("contests.resolverPublishedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.resolverPublishFailed"));
    }
  }

  async function archiveSession() {
    try {
      await archiveMutation.mutateAsync();
      toast.success(t("contests.resolverArchivedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.resolverArchiveFailed"));
    }
  }

  async function restoreSession() {
    try {
      await restoreMutation.mutateAsync();
      toast.success(t("contests.resolverRestoredMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.resolverRestoreFailed"));
    }
  }

  if (!contest || contest.mode !== "ACM") {
    return null;
  }

  return (
    <>
      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold text-[var(--oj-ink)]">
            <RadioTower className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
            {t("contests.resolverReplay")}
          </div>
          <p className="mt-1 text-xs text-pretty text-[var(--oj-ink-muted)]">{t("contests.resolverReplayCopy")}</p>
        </div>
        <Button disabled={!runId || resolverMissingFreeze || createMutation.isPending} onClick={() => void createSession()}>
          <RadioTower className="size-4" aria-hidden="true" />
          {t("contests.resolverGenerate")}
        </Button>
      </div>

      {resolverMissingFreeze ? (
        <div className="mt-3">
          <ErrorPanel title={t("contests.resolverNoFreeze")} description={t("contests.resolverNoFreezeCopy")} />
        </div>
      ) : null}

      {!runId ? (
        <EmptyState title={t("contests.resolverRunRequired")} description={t("contests.resolverRunRequiredCopy")} />
      ) : sessionsQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : sessionsQuery.data?.length ? (
        <div className="mt-4 space-y-4">
          <div className="grid gap-3 md:grid-cols-[minmax(220px,1fr)_auto_auto_auto]">
            <select className={selectClass} value={selectedSessionId} onChange={(event) => setSelectedSessionId(event.target.value)}>
              {sessionsQuery.data.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.title} · {t(`contests.resolverStatus.${session.status}`)}
                </option>
              ))}
            </select>
            {selectedSession && selectedSession.status !== "PUBLISHED" && selectedSession.status !== "ARCHIVED" ? (
              <Button variant="outline" disabled={!selectedSessionId || publishMutation.isPending} onClick={() => void publishSession()}>
              <CheckCircle2 className="size-4" aria-hidden="true" />
              {t("contests.resolverPublish")}
              </Button>
            ) : null}
            {selectedSession && selectedSession.status !== "ARCHIVED" ? (
              <Button variant="outline" disabled={!selectedSessionId || archiveMutation.isPending} onClick={() => void archiveSession()}>
                <Archive className="size-4" aria-hidden="true" />
                {t("contests.resolverArchive")}
              </Button>
            ) : null}
            {selectedSession?.status === "ARCHIVED" ? (
              <Button variant="outline" disabled={!selectedSessionId || restoreMutation.isPending} onClick={() => void restoreSession()}>
                <RotateCw className="size-4" aria-hidden="true" />
                {t("contests.resolverRestore")}
              </Button>
            ) : null}
            {selectedSession?.status === "ARCHIVED" ? (
              <Button variant="outline" disabled={!selectedSessionId || deleteMutation.isPending} onClick={() => setDeleteSessionTarget(selectedSession)}>
                <Trash2 className="size-4" aria-hidden="true" />
                {t("contests.resolverDelete")}
              </Button>
            ) : null}
          </div>
          {detailQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : detailQuery.isError || !detailQuery.data ? (
            <ErrorPanel title={t("contests.resolverLoadFailed")} action={<Button variant="outline" onClick={() => void detailQuery.refetch()}>{t("common.refresh")}</Button>} />
          ) : (
            <ResolverReplayWorkspace detail={detailQuery.data} />
          )}
        </div>
      ) : (
        <EmptyState title={t("contests.resolverEmpty")} description={t("contests.resolverEmptyCopy")} />
      )}
      </section>
      <ConfirmDialog
        open={Boolean(deleteSessionTarget)}
        onOpenChange={(dialogOpen) => !dialogOpen && setDeleteSessionTarget(null)}
        title={t("contests.resolverDelete")}
        description={deleteSessionTarget ? `${deleteSessionTarget.title}\n${t("contests.resolverDeleteConfirm")}` : t("contests.resolverDeleteConfirm")}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.resolverDelete")}
        onConfirm={async () => {
          if (!deleteSessionTarget) return;
          try {
            await deleteMutation.mutateAsync(deleteSessionTarget.id);
            toast.success(t("contests.resolverDeletedMessage"));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.resolverDeleteFailed"));
          } finally {
            setDeleteSessionTarget(null);
          }
        }}
      />
    </>
  );
}

function ResolverReplayWorkspace({ detail }: { detail: ContestResolverSessionDetailResponse }) {
  const { t } = useI18n();
  const [index, setIndex] = React.useState(0);
  const [playing, setPlaying] = React.useState(false);
  const [speed, setSpeed] = React.useState("1200");

  React.useEffect(() => {
    setIndex(0);
    setPlaying(false);
  }, [detail.session.id]);

  React.useEffect(() => {
    if (!playing) return undefined;
    const delay = Number(speed) || 1200;
    const timer = window.setInterval(() => {
      setIndex((current) => {
        if (current >= detail.steps.length - 1) {
          setPlaying(false);
          return current;
        }
        return current + 1;
      });
    }, delay);
    return () => window.clearInterval(timer);
  }, [detail.steps.length, playing, speed]);

  const steps = detail.steps;
  const currentStep = steps[index];
  if (!currentStep) {
    return <EmptyState title={t("contests.resolverNoSteps")} description={t("contests.resolverNoStepsCopy")} />;
  }
  const payload = parseResolverPayload(currentStep.payloadJson);
  return (
    <div className="space-y-4 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface)] p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="text-sm font-semibold tabular-nums text-[var(--oj-ink)]">
            {t("contests.resolverStepProgress", { current: index + 1, total: steps.length })}
          </div>
          <p className="mt-1 text-xs text-pretty text-[var(--oj-ink-muted)]">{resolverStepLabel(t, currentStep.stepType, payload)}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" disabled={index === 0} onClick={() => setIndex((value) => Math.max(0, value - 1))}>
            <ChevronLeft className="size-4" aria-hidden="true" />
            {t("contests.resolverPrevious")}
          </Button>
          <Button size="sm" onClick={() => setPlaying((value) => !value)}>
            {playing ? <Pause className="size-4" aria-hidden="true" /> : <Play className="size-4" aria-hidden="true" />}
            {playing ? t("contests.resolverPause") : t("contests.resolverPlay")}
          </Button>
          <Button variant="outline" size="sm" disabled={index >= steps.length - 1} onClick={() => setIndex((value) => Math.min(steps.length - 1, value + 1))}>
            {t("contests.resolverNext")}
            <ChevronRight className="size-4" aria-hidden="true" />
          </Button>
          <select className={`${selectClass} w-auto min-w-28`} value={speed} onChange={(event) => setSpeed(event.target.value)}>
            <option value="1800">0.5x</option>
            <option value="1200">1x</option>
            <option value="700">1.5x</option>
          </select>
        </div>
      </div>
      <input
        className="w-full accent-[var(--oj-primary)]"
        type="range"
        min={0}
        max={Math.max(0, steps.length - 1)}
        value={index}
        onChange={(event) => setIndex(Number(event.target.value))}
      />
      <ContestScoreboardTable
        mode={currentStep.scoreboard.mode}
        problems={currentStep.scoreboard.problems}
        rows={currentStep.scoreboard.rows}
        labels={scoreboardLabels(t)}
      />
    </div>
  );
}

function parseResolverPayload(payloadJson: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(payloadJson);
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

function resolverStepLabel(t: ReturnType<typeof useI18n>["t"], stepType: string, payload: Record<string, unknown>) {
  if (stepType === "INITIAL") return t("contests.resolverInitialStep");
  if (stepType === "FINAL") return t("contests.resolverFinalStep");
  const participant = String(payload.displayNameSnapshot ?? payload.accountSnapshot ?? "--");
  const problem = String(payload.problemLabel ?? "--");
  const status = String(payload.status ?? "--");
  return t("contests.resolverRevealStep", { participant, problem, status });
}

function ContestCommunicationPanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const open = Boolean(contest);
  const [runId, setRunId] = React.useState<EntityId | "">("");
  const [tab, setTab] = React.useState<"announcements" | "clarifications">("announcements");
  const [editingAnnouncement, setEditingAnnouncement] = React.useState<ContestAnnouncementResponse | null>(null);
  const [announcementTitle, setAnnouncementTitle] = React.useState("");
  const [announcementContent, setAnnouncementContent] = React.useState("");
  const [announcementPinned, setAnnouncementPinned] = React.useState(false);
  const [clarificationStatus, setClarificationStatus] = React.useState<ContestClarificationStatus | "">("");
  const [clarificationVisibility, setClarificationVisibility] = React.useState<ContestClarificationVisibility | "">("");
  const [clarificationsPage, setClarificationsPage] = React.useState(1);
  const [selectedClarification, setSelectedClarification] = React.useState<ContestClarificationResponse | null>(null);
  const [replyText, setReplyText] = React.useState("");
  const [replyVisibility, setReplyVisibility] = React.useState<ContestClarificationVisibility>("PRIVATE");

  React.useEffect(() => {
    if (!open) {
      setRunId("");
      setTab("announcements");
      setEditingAnnouncement(null);
      setAnnouncementTitle("");
      setAnnouncementContent("");
      setAnnouncementPinned(false);
      setSelectedClarification(null);
      setReplyText("");
      setReplyVisibility("PRIVATE");
    }
  }, [open]);

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "communications"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 200 }),
    enabled: open
  });
  const runs = runsQuery.data?.records ?? [];
  React.useEffect(() => {
    if (!open || runId || runs.length === 0) return;
    setRunId(runs[0].id);
  }, [open, runId, runs]);
  React.useEffect(() => {
    if (!runId) return;
    if (runs.length > 0 && !runs.some((run) => run.id === runId)) {
      setRunId("");
    }
  }, [runId, runs]);

  const selectedRun = runs.find((run) => run.id === runId) ?? null;
  const announcementsQuery = useQuery({
    queryKey: ["contest-announcements", contest?.id, runId, true],
    queryFn: () => api.contestAnnouncements(contest!.id, runId, { includeArchived: true }),
    enabled: open && Boolean(runId)
  });
  const clarificationsQuery = useQuery({
    queryKey: ["contest-clarifications", contest?.id, runId, clarificationStatus, clarificationVisibility, clarificationsPage],
    queryFn: () => api.contestClarifications(contest!.id, runId, {
      page: clarificationsPage,
      pageSize: CLARIFICATIONS_PAGE_SIZE,
      status: clarificationStatus,
      visibility: clarificationVisibility,
      staffView: true
    }),
    enabled: open && Boolean(runId)
  });
  const announcements = announcementsQuery.data ?? [];
  const clarifications = clarificationsQuery.data?.records ?? [];
  const clarificationsTotal = clarificationsQuery.data?.total ?? 0;

  const resetAnnouncementForm = () => {
    setEditingAnnouncement(null);
    setAnnouncementTitle("");
    setAnnouncementContent("");
    setAnnouncementPinned(false);
  };

  const saveAnnouncementMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId) throw new Error("missing run");
      const payload = { title: announcementTitle, content: announcementContent, pinned: announcementPinned };
      return editingAnnouncement
        ? api.updateContestAnnouncement(contest.id, runId, editingAnnouncement.id, payload)
        : api.createContestAnnouncement(contest.id, runId, payload);
    },
    onSuccess: async () => {
      resetAnnouncementForm();
      await queryClient.invalidateQueries({ queryKey: ["contest-announcements", contest?.id, runId] });
    }
  });

  const archiveAnnouncementMutation = useMutation({
    mutationFn: ({ announcementId, restore }: { announcementId: EntityId; restore: boolean }) => {
      if (!contest || !runId) throw new Error("missing run");
      return restore
        ? api.restoreContestAnnouncement(contest.id, runId, announcementId)
        : api.archiveContestAnnouncement(contest.id, runId, announcementId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["contest-announcements", contest?.id, runId] });
    }
  });

  const replyMutation = useMutation({
    mutationFn: () => {
      if (!contest || !runId || !selectedClarification) throw new Error("missing clarification");
      return api.replyContestClarification(contest.id, runId, selectedClarification.id, {
        answer: replyText,
        visibility: replyVisibility
      });
    },
    onSuccess: async (updated) => {
      setSelectedClarification(updated);
      setReplyText("");
      await queryClient.invalidateQueries({ queryKey: ["contest-clarifications", contest?.id, runId] });
    }
  });

  const closeMutation = useMutation({
    mutationFn: () => {
      if (!contest || !runId || !selectedClarification) throw new Error("missing clarification");
      return api.closeContestClarification(contest.id, runId, selectedClarification.id);
    },
    onSuccess: async (updated) => {
      setSelectedClarification(updated);
      await queryClient.invalidateQueries({ queryKey: ["contest-clarifications", contest?.id, runId] });
    }
  });

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={contest?.title ?? ""}
      description={t("contests.communicationsCopy")}
      presentation="workspace"
      workspaceSize="lg"
    >
      <div className="space-y-5">
        <Card>
          <CardBody className="space-y-4">
            <div className="grid gap-3 lg:grid-cols-[minmax(260px,380px)_1fr] lg:items-end">
              <Field label={t("contests.run")}>
                <select className={selectClass} value={runId} onChange={(event) => setRunId(event.target.value)}>
                  {runs.length === 0 ? <option value="">{t("contests.noRunsTitle")}</option> : null}
                  {runs.map((run) => (
                    <option key={run.id} value={run.id}>{formatRunSelectorLabel(run)}</option>
                  ))}
                </select>
              </Field>
              <div className="flex flex-wrap gap-2">
                {selectedRun ? (
                  <>
                    <Badge tone="neutral">{t(`contests.runStatus.${selectedRun.status}`)}</Badge>
                    <span className="text-sm text-[var(--oj-ink-muted)]">{formatDateTime(selectedRun.startAt)} - {formatDateTime(selectedRun.endAt)}</span>
                  </>
                ) : null}
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant={tab === "announcements" ? "primary" : "outline"} onClick={() => setTab("announcements")}>
                <Megaphone className="size-4" aria-hidden="true" />
                {t("contests.announcements")}
              </Button>
              <Button variant={tab === "clarifications" ? "primary" : "outline"} onClick={() => setTab("clarifications")}>
                <MessagesSquare className="size-4" aria-hidden="true" />
                {t("contests.clarifications")}
              </Button>
            </div>
          </CardBody>
        </Card>

        {tab === "announcements" ? (
          <div className="grid gap-4 xl:grid-cols-[minmax(320px,420px)_1fr]">
            <Card>
              <CardBody className="space-y-4">
                <div>
                  <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{editingAnnouncement ? t("contests.updateAnnouncement") : t("contests.createAnnouncement")}</h3>
                  <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.announcementsCopy")}</p>
                </div>
                <Field label={t("contests.announcementTitle")}>
                  <input className={inputClass} value={announcementTitle} onChange={(event) => setAnnouncementTitle(event.target.value)} />
                </Field>
                <Field label={t("contests.announcementContent")}>
                  <textarea className={textareaClass} rows={8} value={announcementContent} onChange={(event) => setAnnouncementContent(event.target.value)} />
                </Field>
                <label className="flex items-center gap-2 text-sm text-[var(--oj-ink)]">
                  <input type="checkbox" checked={announcementPinned} onChange={(event) => setAnnouncementPinned(event.target.checked)} />
                  {t("contests.announcementPinned")}
                </label>
                <div className="flex flex-wrap gap-2">
                  <Button
                    disabled={!runId || saveAnnouncementMutation.isPending}
                    onClick={async () => {
                      try {
                        await saveAnnouncementMutation.mutateAsync();
                        toast.success(t("contests.announcementSavedMessage"));
                      } catch (caught) {
                        toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.announcementSaveFailed"));
                      }
                    }}
                  >
                    <Save className="size-4" aria-hidden="true" />
                    {editingAnnouncement ? t("contests.updateAnnouncement") : t("contests.createAnnouncement")}
                  </Button>
                  {editingAnnouncement ? <Button variant="outline" onClick={resetAnnouncementForm}>{t("common.cancel")}</Button> : null}
                </div>
              </CardBody>
            </Card>
            <Card>
              <CardBody className="space-y-3">
                {announcementsQuery.isLoading ? <LoadingPanel label={t("common.loading")} /> : announcements.length === 0 ? (
                  <EmptyState title={t("contests.noAnnouncementsTitle")} description={t("contests.noAnnouncementsDescription")} />
                ) : announcements.map((announcement) => (
                  <article key={announcement.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-4">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 className="truncate text-sm font-semibold text-[var(--oj-ink)]">{announcement.title}</h3>
                          {announcement.pinned ? <Badge tone="blue">{t("contests.announcementPinned")}</Badge> : null}
                          <Badge tone={announcement.status === "ARCHIVED" ? "neutral" : "green"}>
                            {announcement.status === "ARCHIVED" ? t("contests.archive") : t("contests.publish")}
                          </Badge>
                        </div>
                        <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{formatDateTime(announcement.publishedAt)}</p>
                      </div>
                      <div className="flex shrink-0 flex-wrap gap-2">
                        <Button size="sm" variant="outline" onClick={() => {
                          setEditingAnnouncement(announcement);
                          setAnnouncementTitle(announcement.title);
                          setAnnouncementContent(announcement.content);
                          setAnnouncementPinned(Boolean(announcement.pinned));
                        }}>{t("common.edit")}</Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={archiveAnnouncementMutation.isPending}
                          onClick={async () => {
                            const restoring = announcement.status === "ARCHIVED";
                            try {
                              await archiveAnnouncementMutation.mutateAsync({
                                announcementId: announcement.id,
                                restore: restoring
                              });
                              toast.success(t(restoring ? "contests.announcementRestoredMessage" : "contests.announcementArchivedMessage"));
                            } catch (caught) {
                              toast.error(caught instanceof ApiError ? caught.userMessage : restoring ? t("contests.announcementRestoreFailed") : t("contests.announcementArchiveFailed"));
                            }
                          }}
                        >
                          {announcement.status === "ARCHIVED" ? t("contests.restoreAnnouncement") : t("contests.archiveAnnouncement")}
                        </Button>
                      </div>
                    </div>
                    <div className="mt-3 rounded-lg bg-[var(--oj-surface-muted)] px-3 py-2">
                      <MarkdownView content={announcement.content} />
                    </div>
                  </article>
                ))}
              </CardBody>
            </Card>
          </div>
        ) : (
          <div className="space-y-4">
            <Card>
              <CardBody className="flex flex-col gap-3 lg:flex-row lg:items-center">
                <select className={selectClass} value={clarificationStatus} onChange={(event) => { setClarificationStatus(event.target.value as ContestClarificationStatus | ""); setClarificationsPage(1); }}>
                  <option value="">{t("contests.allClarificationStatuses")}</option>
                  {(["OPEN", "ANSWERED", "CLOSED"] as ContestClarificationStatus[]).map((status) => (
                    <option key={status} value={status}>{t(`contests.clarificationStatusLabels.${status}`)}</option>
                  ))}
                </select>
                <select className={selectClass} value={clarificationVisibility} onChange={(event) => { setClarificationVisibility(event.target.value as ContestClarificationVisibility | ""); setClarificationsPage(1); }}>
                  <option value="">{t("contests.allClarificationVisibility")}</option>
                  {(["PRIVATE", "PUBLIC"] as ContestClarificationVisibility[]).map((visibility) => (
                    <option key={visibility} value={visibility}>{t(`contests.clarificationVisibilityLabels.${visibility}`)}</option>
                  ))}
                </select>
                <Button variant="outline" onClick={() => void clarificationsQuery.refetch()}>
                  <RotateCw className="size-4" aria-hidden="true" />
                  {t("common.refresh")}
                </Button>
              </CardBody>
            </Card>
            <div className="grid gap-4 xl:grid-cols-[minmax(360px,520px)_1fr]">
              <Card>
                <CardBody className="space-y-3">
                  {clarificationsQuery.isLoading ? <LoadingPanel label={t("common.loading")} /> : clarifications.length === 0 ? (
                    <EmptyState title={t("contests.noClarificationsTitle")} description={t("contests.noClarificationsDescription")} />
                  ) : clarifications.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={`block w-full rounded-xl border p-4 text-left transition ${selectedClarification?.id === item.id ? "border-[var(--oj-primary)] bg-blue-50" : "border-[var(--oj-border-soft)] bg-white hover:bg-[var(--oj-surface-muted)]"}`}
                      onClick={() => {
                        setSelectedClarification(item);
                        setReplyText(item.answer ?? "");
                        setReplyVisibility(item.answerVisibility ?? "PRIVATE");
                      }}
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={item.status === "OPEN" ? "amber" : item.status === "ANSWERED" ? "green" : "neutral"}>
                          {t(`contests.clarificationStatusLabels.${item.status}`)}
                        </Badge>
                        {item.answerVisibility ? (
                          <Badge tone={item.answerVisibility === "PUBLIC" ? "blue" : "neutral"}>
                            {t(`contests.clarificationVisibilityLabels.${item.answerVisibility}`)}
                          </Badge>
                        ) : null}
                        <span className="ml-auto text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(item.id)}</span>
                      </div>
                      <p className="mt-2 line-clamp-3 text-sm leading-6 text-[var(--oj-ink)]">{item.question}</p>
                      <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{formatDateTime(item.createdAt)}</p>
                    </button>
                  ))}
                  {clarificationsTotal > 0 ? (
                    <PaginationRow
                      page={clarificationsPage}
                      totalPages={Math.max(1, Math.ceil(clarificationsTotal / CLARIFICATIONS_PAGE_SIZE))}
                      total={clarificationsTotal}
                      onPageChange={setClarificationsPage}
                    />
                  ) : null}
                </CardBody>
              </Card>
              <Card>
                <CardBody className="space-y-4">
                  {selectedClarification ? (
                    <>
                      <div>
                        <p className="text-xs text-[var(--oj-ink-muted)]">#{shortId(selectedClarification.id)}</p>
                        <h3 className="mt-1 text-base font-semibold text-[var(--oj-ink)]">{t("contests.clarificationQuestion")}</h3>
                        <p className="mt-2 whitespace-pre-wrap rounded-xl bg-[var(--oj-surface-muted)] p-3 text-sm leading-6 text-[var(--oj-ink)]">{selectedClarification.question}</p>
                      </div>
                      <Field label={t("contests.clarificationVisibility")}>
                        <select className={selectClass} value={replyVisibility} onChange={(event) => setReplyVisibility(event.target.value as ContestClarificationVisibility)}>
                          <option value="PRIVATE">{t("contests.clarificationPrivate")}</option>
                          <option value="PUBLIC">{t("contests.clarificationPublic")}</option>
                        </select>
                      </Field>
                      <Field label={t("contests.clarificationAnswer")}>
                        <textarea className={textareaClass} rows={8} value={replyText} placeholder={t("contests.clarificationAnswerPlaceholder")} onChange={(event) => setReplyText(event.target.value)} />
                      </Field>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          disabled={replyMutation.isPending}
                          onClick={async () => {
                            try {
                              await replyMutation.mutateAsync();
                              toast.success(t("contests.clarificationRepliedMessage"));
                            } catch (caught) {
                              toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.clarificationReplyFailed"));
                            }
                          }}
                        >
                          <Send className="size-4" aria-hidden="true" />
                          {t("contests.replyClarification")}
                        </Button>
                        <Button
                          variant="outline"
                          disabled={closeMutation.isPending}
                          onClick={async () => {
                            try {
                              await closeMutation.mutateAsync();
                              toast.success(t("contests.clarificationClosedMessage"));
                            } catch (caught) {
                              toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.clarificationCloseFailed"));
                            }
                          }}
                        >
                          {t("contests.closeClarification")}
                        </Button>
                      </div>
                      {selectedClarification.answer ? (
                        <div className="rounded-xl bg-[var(--oj-surface-muted)] p-3">
                          <MarkdownView content={selectedClarification.answer} />
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <EmptyState title={t("contests.noClarificationsTitle")} description={t("contests.clarificationsCopy")} />
                  )}
                </CardBody>
              </Card>
            </div>
          </div>
        )}
      </div>
    </SidePanel>
  );
}

function ContestPostmortemPanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const open = Boolean(contest);
  const [runId, setRunId] = React.useState<EntityId | "">("");
  const [selectedReportId, setSelectedReportId] = React.useState<EntityId | "">("");
  const [reportsPage, setReportsPage] = React.useState(1);
  const [summariesPage, setSummariesPage] = React.useState(1);

  React.useEffect(() => {
    if (!open) {
      setRunId("");
      setSelectedReportId("");
    }
  }, [open]);

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "postmortem-selector", "AI_OPERATIONS"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 200, purpose: "AI_OPERATIONS" }),
    enabled: open
  });
  const runs = runsQuery.data?.records ?? [];
  const eligibleRuns = React.useMemo(() => runs.filter(isEligibleAiOperationRun), [runs]);

  React.useEffect(() => {
    if (!open || !runId) return;
    if (!eligibleRuns.some((run) => run.id === runId)) {
      setRunId("");
      setSelectedReportId("");
    }
  }, [eligibleRuns, open, runId]);

  const selectedRun = eligibleRuns.find((run) => run.id === runId) ?? null;
  const reportsQuery = useQuery({
    queryKey: ["admin-contest-postmortem-reports", contest?.id, runId, reportsPage],
    queryFn: () => api.contestPostmortemReports(contest!.id, runId, { page: reportsPage, pageSize: POSTMORTEM_REPORTS_PAGE_SIZE }),
    enabled: open && Boolean(runId)
  });
  const reports = reportsQuery.data?.records ?? [];
  const reportsTotal = reportsQuery.data?.total ?? 0;
  const studentSummariesQuery = useQuery({
    queryKey: ["admin-contest-student-postmortem-summaries", contest?.id, runId, summariesPage],
    queryFn: () => api.contestStudentPostmortemSummaries(contest!.id, runId, { page: summariesPage, pageSize: STUDENT_SUMMARIES_PAGE_SIZE }),
    enabled: open && Boolean(runId)
  });
  const studentSummaries = studentSummariesQuery.data?.records ?? [];
  const studentSummariesTotal = studentSummariesQuery.data?.total ?? 0;

  React.useEffect(() => {
    if (!selectedReportId && reports.length) setSelectedReportId(reports[0].id);
    if (selectedReportId && reports.length && !reports.some((report) => report.id === selectedReportId)) setSelectedReportId(reports[0].id);
  }, [reports, selectedReportId]);

  const selectedReport = reports.find((report) => report.id === selectedReportId) ?? null;
  const createMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId) throw new Error("Contest run is required");
      return api.createContestPostmortemOperationJob(contest.id, runId);
    },
    onSuccess: async () => {
      toast.success(t("operations.jobQueued"));
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });
  const retryMutation = useMutation({
    mutationFn: async (reportId: EntityId) => {
      if (!contest || !runId) throw new Error("Postmortem report is required");
      return api.retryContestPostmortemAi(contest.id, runId, reportId);
    },
    onSuccess: async (report) => {
      setSelectedReportId(report.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-postmortem-reports", contest?.id, runId] });
    }
  });
  const createStudentReportMutation = useMutation({
    mutationFn: async (participantId: EntityId) => {
      if (!contest || !runId) throw new Error("Contest run is required");
      return api.createContestStudentPostmortemOperationJobForParticipant(contest.id, runId, participantId);
    },
    onSuccess: async () => {
      toast.success(t("operations.jobQueued"));
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });
  const batchStudentReportsMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runId) throw new Error("Contest run is required");
      return api.createBatchContestStudentPostmortemOperationJob(contest.id, runId, {});
    },
    onSuccess: async () => {
      toast.success(t("operations.jobQueued"));
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });

  async function createReport() {
    try {
      await createMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.postmortemCreateFailed"));
    }
  }

  async function retryAi(report: ContestPostmortemReportResponse) {
    try {
      await retryMutation.mutateAsync(report.id);
      toast.success(t("contests.postmortemRetriedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.postmortemRetryFailed"));
    }
  }

  async function createBatchStudentReports() {
    try {
      await batchStudentReportsMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.studentPostmortemBatchCreateFailed"));
    }
  }

  const stats = selectedReport ? parsePostmortemStats(selectedReport.statisticsJson) : null;

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={contest?.title ?? t("contests.postmortem")}
      description={t("contests.postmortemCopy")}
      presentation="workspace"
      workspaceSize="xl"
      workspaceHeight="fixed"
      footer={<Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>}
    >
      <div className="space-y-4">
        <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
            <div className="grid flex-1 gap-3 md:grid-cols-[minmax(220px,360px)_1fr]">
              <Field label={t("contests.run")}>
                <select className={selectClass} value={runId} onChange={(event) => setRunId(event.target.value)}>
                  <option value="">{t("contests.selectEndedRun")}</option>
                  {eligibleRuns.map((run) => (
                    <option key={run.id} value={run.id}>{formatRunSelectorLabel(run)}</option>
                  ))}
                </select>
              </Field>
              <div className="grid gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs text-[var(--oj-ink-muted)] md:grid-cols-3">
                <InfoPill label={t("contests.runStatusLabel")} value={selectedRun ? t(`contests.runStatus.${selectedRun.status}`) : "--"} />
                <InfoPill label={t("contests.startAt")} value={selectedRun ? formatDateTime(selectedRun.startAt) : "--"} />
                <InfoPill label={t("contests.endAt")} value={selectedRun ? formatDateTime(selectedRun.endAt) : "--"} />
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" disabled={reportsQuery.isFetching} onClick={() => void reportsQuery.refetch()}>
                <RotateCw className="size-4" aria-hidden="true" />
                {t("common.refresh")}
              </Button>
              <Button disabled={!runId || createMutation.isPending} onClick={() => void createReport()}>
                <Brain className="size-4" aria-hidden="true" />
                {createMutation.isPending ? t("contests.postmortemGenerating") : t("contests.postmortemGenerate")}
              </Button>
            </div>
          </div>
          <p className="mt-3 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("contests.postmortemBoundary")}</p>
        </section>

        <div className="grid min-h-[520px] gap-4 xl:grid-cols-[320px_minmax(0,1fr)]">
          <section className="rounded-xl border border-[var(--oj-border)] bg-white p-3">
            <h3 className="px-1 text-sm font-semibold text-[var(--oj-ink)]">{t("contests.postmortemReports")}</h3>
            <div className="mt-3 space-y-2">
              {!eligibleRuns.length ? (
                <EmptyState title={t("contests.noEligibleAiRunsTitle")} description={t("contests.noEligibleAiRunsDescription")} />
              ) : !runId ? (
                <EmptyState title={t("contests.postmortemRunRequired")} description={t("contests.postmortemRunRequiredCopy")} />
              ) : reportsQuery.isLoading ? (
                <LoadingPanel label={t("common.loading")} />
              ) : reportsQuery.isError ? (
                <ErrorPanel title={t("contests.postmortemLoadFailed")} />
              ) : reports.length ? (
                reports.map((report) => (
                  <button
                    key={report.id}
                    type="button"
                    className={`w-full rounded-xl border p-3 text-left transition-colors ${selectedReportId === report.id ? "border-blue-500 bg-blue-50" : "border-[var(--oj-border)] bg-white hover:bg-[var(--oj-surface-muted)]"}`}
                    onClick={() => setSelectedReportId(report.id)}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-semibold tabular-nums text-[var(--oj-ink)]">#{shortId(report.id)}</span>
                      <PostmortemAiStatusChip status={report.aiStatus} />
                    </div>
                    <div className="mt-2 text-xs leading-5 tabular-nums text-[var(--oj-ink-muted)]">
                      <div>{formatDateTime(report.createdAt)}</div>
                      <div>{report.aiProvider || "--"} {report.aiModel || ""}</div>
                    </div>
                  </button>
                ))
              ) : (
                <EmptyState title={t("contests.postmortemEmpty")} description={t("contests.postmortemEmptyCopy")} />
              )}
              {reportsTotal > 0 ? (
                <PaginationRow
                  page={reportsPage}
                  totalPages={Math.max(1, Math.ceil(reportsTotal / POSTMORTEM_REPORTS_PAGE_SIZE))}
                  total={reportsTotal}
                  onPageChange={setReportsPage}
                />
              ) : null}
            </div>
          </section>

          <section className="min-w-0 rounded-xl border border-[var(--oj-border)] bg-white p-4">
            {!selectedReport ? (
              <EmptyState title={t("contests.postmortemSelectReport")} description={t("contests.postmortemSelectReportCopy")} />
            ) : (
              <div className="space-y-5">
                <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] pb-4 lg:flex-row lg:items-start lg:justify-between">
                  <div>
                    <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.postmortemReportDetail")}</h3>
                    <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{selectedReport.id} · {formatDateTime(selectedReport.createdAt)}</p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {selectedReport.aiStatus === "FAILED" ? (
                      <Button variant="outline" disabled={retryMutation.isPending} onClick={() => void retryAi(selectedReport)}>
                        <RotateCw className="size-4" aria-hidden="true" />
                        {t("contests.postmortemRetryAi")}
                      </Button>
                    ) : null}
                    {selectedReport.aiMarkdown ? (
                      <Button variant="outline" onClick={() => downloadMarkdown(selectedReport, stats, {
                        kind: t("contests.postmortemExportKind"),
                        reportId: t("contests.postmortemExportReportId"),
                        generatedAt: t("contests.postmortemExportGeneratedAt")
                      })}>
                        <Download className="size-4" aria-hidden="true" />
                        {t("contests.postmortemDownloadMarkdown")}
                      </Button>
                    ) : null}
                  </div>
                </div>

                {stats ? <PostmortemStatsSummary stats={stats} /> : <ErrorPanel title={t("contests.postmortemStatsParseFailed")} />}

                {selectedReport.aiStatus === "FAILED" ? (
                  <ErrorPanel title={t("contests.postmortemAiFailed")} description={readableStoredError(selectedReport.errorMessage, locale, t("contests.postmortemAiFailedCopy"), "ai")} />
                ) : null}

                <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                  <div className="mb-3 flex items-center justify-between gap-2">
                    <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.postmortemAiMarkdown")}</h3>
                    <PostmortemAiStatusChip status={selectedReport.aiStatus} />
                  </div>
                  {selectedReport.aiMarkdown ? (
                    <div className="rounded-xl bg-white p-4">
                      <MarkdownView content={selectedReport.aiMarkdown} />
                    </div>
                  ) : (
                    <p className="text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.postmortemNoMarkdown")}</p>
                  )}
                </section>
              </div>
            )}
          </section>
        </div>

        {runId ? (
          <StudentPostmortemSummarySection
            summaries={studentSummaries}
            total={studentSummariesTotal}
            page={summariesPage}
            pageSize={STUDENT_SUMMARIES_PAGE_SIZE}
            onPageChange={setSummariesPage}
            loading={studentSummariesQuery.isLoading}
            error={studentSummariesQuery.isError}
            generating={createStudentReportMutation.isPending}
            batchGenerating={batchStudentReportsMutation.isPending}
            onBatchGenerate={() => void createBatchStudentReports()}
            onGenerate={(summary) => {
              void createStudentReportMutation.mutateAsync(summary.contestParticipantId).catch((caught) => {
                toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.studentPostmortemCreateFailed"));
              });
            }}
          />
        ) : null}
      </div>
    </SidePanel>
  );
}

type PostmortemStats = {
  contestTitle?: string;
  runTitle?: string;
  mode?: string;
  participantCount?: number;
  activeParticipants?: number;
  submissionCount?: number;
  languageDistribution?: Record<string, number>;
  statusDistribution?: Record<string, number>;
  problems?: Array<{
    label?: string;
    title?: string;
    tags?: string[];
    submissionCount?: number;
    attemptedParticipants?: number;
    acceptedParticipants?: number;
    acceptedRate?: number;
    bestScore?: number;
    maxScore?: number;
  }>;
  plagiarism?: {
    jobCount?: number;
    completedJobCount?: number;
    pairCount?: number;
    highRiskPairCount?: number;
  };
  weaknessCandidates?: string[];
};

function PostmortemStatsSummary({ stats }: { stats: PostmortemStats }) {
  const { t } = useI18n();
  const problems = stats.problems ?? [];
  const plagiarism = stats.plagiarism ?? {};
  return (
    <section className="space-y-4">
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <InfoCard label={t("contests.postmortemParticipants")} value={`${stats.activeParticipants ?? 0} / ${stats.participantCount ?? 0}`} />
        <InfoCard label={t("contests.postmortemSubmissions")} value={String(stats.submissionCount ?? 0)} />
        <InfoCard label={t("contests.postmortemHighRiskPairs")} value={String(plagiarism.highRiskPairCount ?? 0)} />
        <InfoCard label={t("contests.postmortemProblems")} value={String(problems.length)} />
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <DistributionBlock title={t("contests.postmortemLanguageDistribution")} distribution={stats.languageDistribution} />
        <DistributionBlock title={t("contests.postmortemStatusDistribution")} distribution={stats.statusDistribution} />
      </div>
      {stats.weaknessCandidates?.length ? (
        <section className="rounded-xl border border-amber-200 bg-amber-50 p-4">
          <h4 className="text-sm font-semibold text-amber-950">{t("contests.postmortemWeaknessCandidates")}</h4>
          <ul className="mt-2 space-y-1 text-sm leading-6 text-amber-900">
            {stats.weaknessCandidates.map((item) => <li key={item}>· {item}</li>)}
          </ul>
        </section>
      ) : null}
      <TableShell>
        <table className="w-full min-w-[860px] text-sm">
          <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
            <tr>
              <th className="px-4 py-3 text-left">{t("contests.problem")}</th>
              <th className="px-4 py-3 text-center">{t("contests.postmortemAttempts")}</th>
              <th className="px-4 py-3 text-center">{t("contests.postmortemAccepted")}</th>
              <th className="px-4 py-3 text-center">{t("contests.postmortemAcceptedRate")}</th>
              <th className="px-4 py-3 text-left">{t("contests.postmortemTags")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--oj-border-soft)]">
            {problems.map((problem) => (
              <tr key={`${problem.label}-${problem.title}`}>
                <td className="px-4 py-3">
                  <div className="font-medium text-[var(--oj-ink)]">{problem.label} · {problem.title}</div>
                  <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatScoreSummary(problem.bestScore, problem.maxScore)}</div>
                </td>
                <td className="px-4 py-3 text-center tabular-nums">{problem.submissionCount ?? 0}</td>
                <td className="px-4 py-3 text-center tabular-nums">{problem.acceptedParticipants ?? 0} / {problem.attemptedParticipants ?? 0}</td>
                <td className="px-4 py-3 text-center tabular-nums">{formatPercent(problem.acceptedRate ?? 0)}</td>
                <td className="px-4 py-3 text-xs text-[var(--oj-ink-muted)]">{problem.tags?.join(", ") || "--"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableShell>
    </section>
  );
}

function StudentPostmortemSummarySection({
  summaries,
  total,
  page,
  pageSize,
  onPageChange,
  loading,
  error,
  generating,
  batchGenerating,
  onBatchGenerate,
  onGenerate
}: {
  summaries: ContestStudentPostmortemSummaryResponse[];
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  loading: boolean;
  error: boolean;
  generating: boolean;
  batchGenerating: boolean;
  onBatchGenerate: () => void;
  onGenerate: (summary: ContestStudentPostmortemSummaryResponse) => void;
}) {
  const { t } = useI18n();
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentPostmortemSummaries")}</h3>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("contests.studentPostmortemSummariesCopy")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="neutral">{summaries.length}</Badge>
          <Button size="sm" variant="outline" disabled={batchGenerating || loading || error || !summaries.length} onClick={onBatchGenerate}>
            <Brain className="size-4" aria-hidden="true" />
            {batchGenerating ? t("contests.studentPostmortemBatchGenerating") : t("contests.studentPostmortemBatchGenerate")}
          </Button>
        </div>
      </div>
      {loading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : error ? (
        <ErrorPanel title={t("contests.studentPostmortemSummaryLoadFailed")} />
      ) : summaries.length ? (
        <TableShell>
          <table className="w-full min-w-[980px] text-sm">
            <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
              <tr>
                <th className="px-4 py-3 text-left">{t("contests.participant")}</th>
                <th className="px-4 py-3 text-center">{t("contests.postmortemSubmissions")}</th>
                <th className="px-4 py-3 text-center">{t("contests.postmortemAccepted")}</th>
                <th className="px-4 py-3 text-center">{t("contests.score")}</th>
                <th className="px-4 py-3 text-center">{t("contests.postmortemWeaknessCandidates")}</th>
                <th className="px-4 py-3 text-center">{t("contests.postmortemAiMarkdown")}</th>
                <th className="px-4 py-3 text-center">{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--oj-border-soft)]">
              {summaries.map((summary) => (
                <tr key={summary.contestParticipantId}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-[var(--oj-ink)]">{summary.displayNameSnapshot || summary.accountSnapshot}</div>
                    <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">{summary.accountSnapshot} · #{summary.userId}</div>
                  </td>
                  <td className="px-4 py-3 text-center tabular-nums">{summary.submissionCount}</td>
                  <td className="px-4 py-3 text-center tabular-nums">{summary.acceptedCount}</td>
                  <td className="px-4 py-3 text-center tabular-nums">{formatScoreSummary(summary.totalScore, summary.maxScore)}</td>
                  <td className="px-4 py-3 text-center tabular-nums">
                    {summary.weaknessCandidateCount} / {summary.pendingWeaknessCandidateCount}
                  </td>
                  <td className="px-4 py-3 text-center">
                    {summary.aiStatus ? <PostmortemAiStatusChip status={summary.aiStatus} /> : <Badge tone="neutral">{t("contests.studentPostmortemNotGenerated")}</Badge>}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <Button size="sm" variant="outline" disabled={generating} onClick={() => onGenerate(summary)}>
                      <Brain className="size-4" aria-hidden="true" />
                      {summary.reportId ? t("contests.studentPostmortemRegenerate") : t("contests.studentPostmortemGenerate")}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableShell>
      ) : (
        <EmptyState title={t("contests.studentPostmortemSummaryEmpty")} description={t("contests.studentPostmortemSummaryEmptyCopy")} />
      )}
      {total > 0 ? (
        <div className="mt-4">
          <PaginationRow
            page={page}
            totalPages={Math.max(1, Math.ceil(total / pageSize))}
            total={total}
            onPageChange={onPageChange}
          />
        </div>
      ) : null}
    </section>
  );
}

function InfoCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="text-xs font-medium text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-2 text-xl font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function DistributionBlock({ title, distribution }: { title: string; distribution?: Record<string, number> }) {
  const entries = Object.entries(distribution ?? {});
  return (
    <section className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-4">
      <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{title}</h4>
      {entries.length ? (
        <div className="mt-3 flex flex-wrap gap-2">
          {entries.map(([key, value]) => <Badge key={key} tone="neutral">{key}: {value}</Badge>)}
        </div>
      ) : (
        <p className="mt-2 text-sm text-[var(--oj-ink-muted)]">--</p>
      )}
    </section>
  );
}

function parsePostmortemStats(value: string): PostmortemStats | null {
  try {
    return JSON.parse(value) as PostmortemStats;
  } catch {
    return null;
  }
}

function downloadMarkdown(report: ContestPostmortemReportResponse, stats: PostmortemStats | null,
                          labels: { kind: string; reportId: string; generatedAt: string }) {
  const title = stats?.contestTitle || "contest";
  const runTitle = stats?.runTitle || "run";
  const content = `# ${title} - ${runTitle} ${labels.kind}\n\n${labels.reportId}${report.id}\n${labels.generatedAt}${formatDateTime(report.createdAt)}\n\n${report.aiMarkdown || ""}`;
  const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `contest-${report.contestId}-run-${report.contestRunId}-postmortem-${shortId(report.id)}.md`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

function PostmortemAiStatusChip({ status }: { status: string }) {
  const { t } = useI18n();
  const tone = status === "COMPLETED"
    ? "green"
    : status === "FAILED"
      ? "red"
      : status === "RUNNING"
        ? "blue"
        : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.postmortemAiStatus.${status}`)}</Badge>;
}

type ContestSubmissionsTab = "submissions" | "audit";

function ContestSubmissionsPanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const toast = useToast();
  const { t } = useI18n();
  const queryClient = useQueryClient();
  const [tab, setTab] = React.useState<ContestSubmissionsTab>("submissions");
  const [runFilter, setRunFilter] = React.useState<EntityId | "">("");
  const [problemFilter, setProblemFilter] = React.useState<EntityId | "">("");
  const [participantFilter, setParticipantFilter] = React.useState<EntityId | "">("");
  const [statusFilter, setStatusFilter] = React.useState<SubmissionStatus | "">("");
  const [languageFilter, setLanguageFilter] = React.useState("");
  const [submissionPage, setSubmissionPage] = React.useState(1);
  const [auditPage, setAuditPage] = React.useState(1);
  const [selectedSubmissionId, setSelectedSubmissionId] = React.useState<EntityId | null>(null);
  const [codeSubmission, setCodeSubmission] = React.useState<ContestSubmissionResponse | null>(null);
  const [accessReason, setAccessReason] = React.useState("");
  const open = Boolean(contest);

  React.useEffect(() => {
    if (!contest) return;
    setTab("submissions");
    setRunFilter("");
    setProblemFilter("");
    setParticipantFilter("");
    setStatusFilter("");
    setLanguageFilter("");
    setSubmissionPage(1);
    setAuditPage(1);
    setSelectedSubmissionId(null);
    setCodeSubmission(null);
    setAccessReason("");
  }, [contest]);

  React.useEffect(() => {
    setSubmissionPage(1);
  }, [runFilter, problemFilter, participantFilter, statusFilter, languageFilter]);

  React.useEffect(() => {
    setParticipantFilter("");
    setSelectedSubmissionId(null);
    setCodeSubmission(null);
  }, [runFilter]);

  const problemsQuery = useQuery({
    queryKey: ["admin-contest-problems", contest?.id],
    queryFn: () => api.contestProblems(contest!.id),
    enabled: open
  });

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "submissions-selector"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 50 }),
    enabled: open
  });

  const participantsQuery = useQuery({
    queryKey: ["admin-contest-participants", contest?.id, runFilter],
    queryFn: async () => {
      const participants = await api.contestParticipants(contest!.id);
      return runFilter ? participants.filter((participant) => participant.contestRunId === runFilter) : participants;
    },
    enabled: open
  });

  const submissionsQuery = useQuery({
    queryKey: ["admin-contest-submissions", contest?.id, runFilter, submissionPage, problemFilter, participantFilter, statusFilter, languageFilter],
    queryFn: () => api.contestSubmissions(contest!.id, {
      page: submissionPage,
      pageSize: PAGE_SIZE,
      runId: runFilter,
      contestProblemId: problemFilter,
      participantId: participantFilter,
      status: statusFilter,
      language: languageFilter
    }),
    enabled: open && tab === "submissions"
  });

  const detailQuery = useQuery({
    queryKey: ["admin-contest-submission", contest?.id, selectedSubmissionId],
    queryFn: () => api.contestSubmission(contest!.id, selectedSubmissionId!),
    enabled: open && tab === "submissions" && Boolean(selectedSubmissionId)
  });

  const auditQuery = useQuery({
    queryKey: ["admin-contest-code-access-logs", contest?.id, runFilter, auditPage],
    queryFn: () => api.contestSubmissionCodeAccessLogs(contest!.id, { page: auditPage, pageSize: PAGE_SIZE, runId: runFilter }),
    enabled: open && tab === "audit"
  });

  const accessCodeMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !selectedSubmissionId) throw new Error("Submission is required");
      return api.accessContestSubmissionCode(contest.id, selectedSubmissionId, { reason: accessReason.trim() || null });
    },
    onSuccess: async (result) => {
      setCodeSubmission(result.submission);
      setAccessReason("");
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-code-access-logs", contest?.id] });
    }
  });

  const handleSubmissionsExportAutoDownloadCompleted = React.useCallback(() => {
    toast.success(t("operations.jobAutoDownloadCompleted"));
    void queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
  }, [queryClient, t, toast]);

  const handleSubmissionsExportAutoDownloadFailed = React.useCallback(() => {
    toast.error(t("operations.jobAutoDownloadFailed"));
  }, [t, toast]);

  const startSubmissionsExportAutoDownload = useOperationJobAutoDownload({
    contestId: contest?.id,
    onCompleted: handleSubmissionsExportAutoDownloadCompleted,
    onFailed: handleSubmissionsExportAutoDownloadFailed
  });

  const exportSubmissionsMutation = useMutation({
    mutationFn: async (format: ContestExportFormat) => {
      if (!contest) throw new Error("Contest is required");
      return api.createContestSubmissionsExportJob(contest.id, {
        format,
        contestProblemId: problemFilter,
        participantId: participantFilter,
        runId: runFilter,
        status: statusFilter,
        language: languageFilter
      });
    },
    onSuccess: async (job) => {
      toast.success(t("operations.jobQueuedAutoDownload"));
      startSubmissionsExportAutoDownload(job.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });

  async function accessCode() {
    try {
      await accessCodeMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.sourceAccessFailed"));
    }
  }

  async function exportSubmissions(format: ContestExportFormat) {
    try {
      await exportSubmissionsMutation.mutateAsync(format);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.exportFailed"));
    }
  }

  const selectedSubmission = codeSubmission?.id === selectedSubmissionId ? codeSubmission : detailQuery.data;
  const submissionTotal = submissionsQuery.data?.total ?? 0;
  const submissionTotalPages = Math.max(1, Math.ceil(submissionTotal / PAGE_SIZE));
  const auditTotal = auditQuery.data?.total ?? 0;
  const auditTotalPages = Math.max(1, Math.ceil(auditTotal / PAGE_SIZE));

  return (
    <>
      <SidePanel
        wide
        open={open}
        onOpenChange={onOpenChange}
        title={contest?.title ?? t("contests.submissions")}
        description={t("contests.contestSubmissionsCopy")}
        footer={(
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
          </div>
        )}
      >
      <div className="space-y-5">
        <div className="flex flex-wrap gap-2 border-b border-[var(--oj-border-soft)] pb-3">
          <Button variant={tab === "submissions" ? "primary" : "outline"} onClick={() => setTab("submissions")}>
            <FileCode className="size-4" aria-hidden="true" />
            {t("contests.submissionsTab")}
          </Button>
          <Button variant={tab === "audit" ? "primary" : "outline"} onClick={() => setTab("audit")}>
            <ShieldCheck className="size-4" aria-hidden="true" />
            {t("contests.sourceAuditTab")}
          </Button>
        </div>

        {tab === "submissions" ? (
          <div className="space-y-4">
            <div className="space-y-4">
              <section className="grid gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:grid-cols-5">
                <select className={selectClass} value={runFilter} onChange={(event) => setRunFilter(event.target.value)}>
                  <option value="">{t("contests.allRuns")}</option>
                  {runsQuery.data?.records.map((run) => <option key={run.id} value={run.id}>{run.title}</option>)}
                </select>
                <select className={selectClass} value={problemFilter} onChange={(event) => setProblemFilter(event.target.value)}>
                  <option value="">{t("contests.allProblems")}</option>
                  {problemsQuery.data?.map((problem) => (
                    <option key={problem.id} value={problem.id}>{problem.label} · {problem.displayTitle || `#${problem.problemId}`}</option>
                  ))}
                </select>
                <select className={selectClass} value={participantFilter} onChange={(event) => setParticipantFilter(event.target.value)}>
                  <option value="">{t("contests.allParticipants")}</option>
                  {participantsQuery.data?.map((participant) => (
                    <option key={participant.id} value={participant.id}>{participant.accountSnapshot} · {participant.displayNameSnapshot}</option>
                  ))}
                </select>
                <select className={selectClass} value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as SubmissionStatus | "")}>
                  {SUBMISSION_STATUSES.map((status) => (
                    <option key={status || "all"} value={status}>{status ? t(`submissionStatus.${status}`) : t("submissions.allStatuses")}</option>
                  ))}
                </select>
                <select className={selectClass} value={languageFilter} onChange={(event) => setLanguageFilter(event.target.value)}>
                  {LANGUAGES.map((language) => (
                    <option key={language || "all"} value={language}>{language || t("submissions.allLanguages")}</option>
                  ))}
                </select>
              </section>
              <section className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:flex-row md:items-center md:justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.exportSubmissions")}</h3>
                  <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">{t("contests.exportSubmissionsCopy")}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button variant="outline" disabled={exportSubmissionsMutation.isPending} onClick={() => void exportSubmissions("CSV")}>
                    <Download className="size-4" aria-hidden="true" />
                    {t("contests.exportCsv")}
                  </Button>
                  <Button variant="outline" disabled={exportSubmissionsMutation.isPending} onClick={() => void exportSubmissions("XLSX")}>
                    <Download className="size-4" aria-hidden="true" />
                    {t("contests.exportXlsx")}
                  </Button>
                </div>
              </section>
              {submissionsQuery.isLoading ? (
                <LoadingPanel label={t("common.loading")} />
              ) : submissionsQuery.isError ? (
                <ErrorPanel title={t("contests.submissionsLoadFailed")} action={<Button variant="outline" onClick={() => void submissionsQuery.refetch()}>{t("common.refresh")}</Button>} />
              ) : submissionsQuery.data?.records.length ? (
                <>
                  <TableShell>
                    <table className="w-full min-w-[920px] text-sm">
                      <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                        <tr>
                          <th className="px-4 py-3 text-left">{t("contests.participant")}</th>
                          <th className="px-4 py-3 text-left">{t("contests.problem")}</th>
                          <th className="px-4 py-3 text-left">{t("submissions.viewLanguageLabel")}</th>
                          <th className="px-4 py-3 text-left">{t("common.status")}</th>
                          <th className="px-4 py-3 text-center">{t("contests.score")}</th>
                          <th className="px-4 py-3 text-left">{t("contests.contestClock")}</th>
                          <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-[var(--oj-border-soft)]">
                        {submissionsQuery.data.records.map((submission) => (
                          <tr key={submission.id} className="align-middle">
                            <td className="px-4 py-3">
                              <div className="font-medium text-[var(--oj-ink)]">{submission.displayNameSnapshot}</div>
                              <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">{submission.accountSnapshot} · #{shortId(submission.userId)}</div>
                            </td>
                            <td className="px-4 py-3">
                              <div className="font-medium text-[var(--oj-ink)]">{submission.problemLabel || "--"} · {submission.problemTitle || `#${submission.problemId}`}</div>
                              <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(submission.id)}</div>
                            </td>
                            <td className="px-4 py-3 text-[var(--oj-ink-muted)]">{submission.language}</td>
                            <td className="px-4 py-3"><SubmissionStatusChip status={submission.status} /></td>
                            <td className="px-4 py-3 text-center tabular-nums text-[var(--oj-ink-muted)]">{formatScoreSummary(submission.score, submission.maxScore)}</td>
                            <td className="px-4 py-3 text-xs leading-5 tabular-nums text-[var(--oj-ink-muted)]">
                              <div>{formatContestClock(submission.submittedAtContestMillis ?? 0)}</div>
                              <div>{formatDateTime(submission.createdAt)}</div>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <Button size="sm" variant="outline" onClick={() => {
                                setSelectedSubmissionId(submission.id);
                                setCodeSubmission(null);
                                setAccessReason("");
                              }}>
                                <Eye className="size-4" aria-hidden="true" />
                                {t("common.view")}
                              </Button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </TableShell>
                  <PaginationRow page={submissionPage} totalPages={submissionTotalPages} total={submissionTotal} onPageChange={setSubmissionPage} />
                </>
              ) : (
                <EmptyState title={t("contests.noContestSubmissionsTitle")} description={t("contests.noContestSubmissionsDescription")} />
              )}
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
              <select className={`${selectClass} w-full sm:w-72`} value={runFilter} onChange={(event) => setRunFilter(event.target.value)}>
                <option value="">{t("contests.allRuns")}</option>
                {runsQuery.data?.records.map((run) => <option key={run.id} value={run.id}>{run.title}</option>)}
              </select>
            </section>
            {auditQuery.isLoading ? (
              <LoadingPanel label={t("common.loading")} />
            ) : auditQuery.isError ? (
              <ErrorPanel title={t("contests.auditLoadFailed")} action={<Button variant="outline" onClick={() => void auditQuery.refetch()}>{t("common.refresh")}</Button>} />
            ) : auditQuery.data?.records.length ? (
              <>
                <TableShell>
                  <table className="w-full min-w-[920px] text-sm">
                    <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                      <tr>
                        <th className="px-4 py-3 text-left">{t("contests.auditViewer")}</th>
                        <th className="px-4 py-3 text-left">{t("contests.auditTarget")}</th>
                        <th className="px-4 py-3 text-left">{t("contests.problem")}</th>
                        <th className="px-4 py-3 text-left">{t("contests.auditReason")}</th>
                        <th className="px-4 py-3 text-left">{t("contests.auditTrace")}</th>
                        <th className="px-4 py-3 text-left">{t("contests.auditCreatedAt")}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--oj-border-soft)]">
                      {auditQuery.data.records.map((log) => (
                        <tr key={log.id} className="align-middle">
                          <td className="px-4 py-3">
                            <div className="font-medium text-[var(--oj-ink)]">{log.viewerDisplayName || log.viewerAccount || `#${log.viewerUserId}`}</div>
                            <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(log.viewerUserId)}</div>
                          </td>
                          <td className="px-4 py-3">
                            <div className="font-medium text-[var(--oj-ink)]">{log.targetDisplayNameSnapshot || log.targetAccountSnapshot || `#${log.targetUserId}`}</div>
                            <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(log.submissionId)}</div>
                          </td>
                          <td className="px-4 py-3 text-[var(--oj-ink-muted)]">{log.problemLabel || "--"} · {log.problemTitle || "--"}</td>
                          <td className="px-4 py-3 text-[var(--oj-ink-muted)]">{log.reason || t("contests.noAuditReason")}</td>
                          <td
                            className="px-4 py-3 max-w-40 truncate text-xs tabular-nums text-[var(--oj-ink-muted)]"
                            title={log.traceId ?? ""}
                          >
                            {log.traceId ? formatShortTraceId(log.traceId) : "--"}
                          </td>
                          <td className="px-4 py-3 text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(log.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </TableShell>
                <PaginationRow page={auditPage} totalPages={auditTotalPages} total={auditTotal} onPageChange={setAuditPage} />
              </>
            ) : (
              <EmptyState title={t("contests.noAuditLogsTitle")} description={t("contests.noAuditLogsDescription")} />
            )}
          </div>
        )}
        </div>
      </SidePanel>
      <SidePanel
        open={open && tab === "submissions" && Boolean(selectedSubmissionId)}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) {
            setSelectedSubmissionId(null);
            setCodeSubmission(null);
            setAccessReason("");
          }
        }}
        title={t("contests.submissionDetail")}
        description={selectedSubmissionId ? `#${selectedSubmissionId}` : undefined}
        presentation="workspace"
        workspaceSize="lg"
        workspaceHeight="fit"
        footer={(
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => {
              setSelectedSubmissionId(null);
              setCodeSubmission(null);
              setAccessReason("");
            }}>{t("common.close")}</Button>
          </div>
        )}
      >
        <ContestSubmissionDetail
          submission={selectedSubmission ?? null}
          loading={detailQuery.isLoading}
          accessReason={accessReason}
          setAccessReason={setAccessReason}
          accessing={accessCodeMutation.isPending}
          onAccess={() => void accessCode()}
        />
      </SidePanel>
    </>
  );
}

function ContestPlagiarismPanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [runFilter, setRunFilter] = React.useState<EntityId | "">("");
  const [selectedJobId, setSelectedJobId] = React.useState<EntityId>("");
  const [jobsPage, setJobsPage] = React.useState(1);
  const [pairPage, setPairPage] = React.useState(1);
  const [problemFilter, setProblemFilter] = React.useState<EntityId | "">("");
  const [languageFilter, setLanguageFilter] = React.useState("");
  const [riskFilter, setRiskFilter] = React.useState<PlagiarismRiskLevel | "">("");
  const [reviewFilter, setReviewFilter] = React.useState<PlagiarismReviewStatus | "">("");
  const [selectedPairId, setSelectedPairId] = React.useState<EntityId | null>(null);
  const [reviewStatus, setReviewStatus] = React.useState<PlagiarismReviewStatus>("OPEN");
  const [teacherNote, setTeacherNote] = React.useState("");
  const [activeTab, setActiveTab] = React.useState<"pairs" | "graph" | "alerts">("pairs");
  const [alertPage, setAlertPage] = React.useState(1);
  const [alertTypeFilter, setAlertTypeFilter] = React.useState<FairnessAlertType | "">("");
  const [alertSeverityFilter, setAlertSeverityFilter] = React.useState<FairnessAlertSeverity | "">("");
  const [alertStatusFilter, setAlertStatusFilter] = React.useState<FairnessAlertStatus | "">("");
  const [alertNotes, setAlertNotes] = React.useState<Record<string, string>>({});
  const open = Boolean(contest);

  React.useEffect(() => {
    if (!contest) return;
    setRunFilter("");
    setSelectedJobId("");
    setPairPage(1);
    setProblemFilter("");
    setLanguageFilter("");
    setRiskFilter("");
    setReviewFilter("");
    setSelectedPairId(null);
    setReviewStatus("OPEN");
    setTeacherNote("");
    setActiveTab("pairs");
    setAlertPage(1);
    setAlertTypeFilter("");
    setAlertSeverityFilter("");
    setAlertStatusFilter("");
    setAlertNotes({});
  }, [contest]);

  React.useEffect(() => {
    setPairPage(1);
  }, [problemFilter, languageFilter, riskFilter, reviewFilter]);

  React.useEffect(() => {
    setAlertPage(1);
  }, [alertTypeFilter, alertSeverityFilter, alertStatusFilter]);

  React.useEffect(() => {
    setSelectedJobId("");
    setSelectedPairId(null);
    setAlertNotes({});
  }, [runFilter]);

  const problemsQuery = useQuery({
    queryKey: ["admin-contest-problems", contest?.id],
    queryFn: () => api.contestProblems(contest!.id),
    enabled: open
  });

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "plagiarism-selector", "AI_OPERATIONS"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 200, purpose: "AI_OPERATIONS" }),
    enabled: open
  });
  const runs = runsQuery.data?.records ?? [];
  const eligibleRuns = React.useMemo(() => runs.filter(isEligibleAiOperationRun), [runs]);

  React.useEffect(() => {
    if (!open || !runFilter) return;
    if (!eligibleRuns.some((run) => run.id === runFilter)) {
      setRunFilter("");
      setSelectedJobId("");
      setSelectedPairId(null);
    }
  }, [eligibleRuns, open, runFilter]);

  const jobsQuery = useQuery({
    queryKey: ["admin-contest-plagiarism-jobs", contest?.id, runFilter, jobsPage],
    queryFn: () => api.contestPlagiarismJobs(contest!.id, { page: jobsPage, pageSize: PLAGIARISM_JOBS_PAGE_SIZE, runId: runFilter }),
    enabled: open && Boolean(runFilter),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) =>
      open && Boolean(runFilter) && Boolean(data?.records.some((job) => job.status === "QUEUED" || job.status === "RUNNING")),
      { fastMs: 2500, slowMs: 5000, hiddenMs: 10000 }
    )
  });

  const jobs = jobsQuery.data?.records ?? [];
  const jobsTotal = jobsQuery.data?.total ?? 0;

  React.useEffect(() => {
    if (!selectedJobId && jobs.length) {
      setSelectedJobId(jobs[0].id);
    }
  }, [jobs, selectedJobId]);

  const pairsQuery = useQuery({
    queryKey: ["admin-contest-plagiarism-pairs", contest?.id, selectedJobId, pairPage, problemFilter, languageFilter, riskFilter, reviewFilter],
    queryFn: () => api.contestPlagiarismPairs(contest!.id, selectedJobId, {
      page: pairPage,
      pageSize: PAGE_SIZE,
      contestProblemId: problemFilter,
      language: languageFilter,
      riskLevel: riskFilter,
      reviewStatus: reviewFilter
    }),
    enabled: open && Boolean(selectedJobId)
  });

  const detailQuery = useQuery({
    queryKey: ["admin-contest-plagiarism-pair", contest?.id, selectedJobId, selectedPairId],
    queryFn: () => api.contestPlagiarismPair(contest!.id, selectedJobId, selectedPairId!),
    enabled: open && Boolean(selectedJobId) && Boolean(selectedPairId)
  });

  const graphQuery = useQuery({
    queryKey: ["admin-contest-plagiarism-graph", contest?.id, runFilter, selectedJobId, problemFilter, languageFilter, riskFilter, reviewFilter],
    queryFn: () => api.contestPlagiarismGraph(contest!.id, runFilter as EntityId, {
      jobId: selectedJobId,
      contestProblemId: problemFilter,
      language: languageFilter,
      riskLevel: riskFilter,
      reviewStatus: reviewFilter
    }),
    enabled: open && activeTab === "graph" && Boolean(runFilter) && Boolean(selectedJobId)
  });

  const alertsQuery = useQuery({
    queryKey: ["admin-contest-fairness-alerts", contest?.id, runFilter, alertPage, alertTypeFilter, alertSeverityFilter, alertStatusFilter],
    queryFn: () => api.contestFairnessAlerts(contest!.id, runFilter as EntityId, {
      page: alertPage,
      pageSize: PAGE_SIZE,
      type: alertTypeFilter,
      severity: alertSeverityFilter,
      status: alertStatusFilter
    }),
    enabled: open && activeTab === "alerts" && Boolean(runFilter)
  });

  React.useEffect(() => {
    const pair = detailQuery.data?.pair;
    if (!pair) return;
    setReviewStatus(pair.reviewStatus);
    setTeacherNote(pair.teacherNote ?? "");
  }, [detailQuery.data?.pair]);

  const createJobMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runFilter) throw new Error("Contest run is required");
      return api.createContestPlagiarismOperationJob(contest.id, {
        minimumSimilarity: 0.55,
        includeAiAnalysis: true
      }, { runId: runFilter });
    },
    onSuccess: async () => {
      toast.success(t("operations.jobQueued"));
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });

  const updatePairMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !selectedPairId) throw new Error("Pair is required");
      return api.updateContestPlagiarismPair(contest.id, selectedJobId, selectedPairId, {
        reviewStatus,
        teacherNote: teacherNote.trim() || null
      });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-contest-plagiarism-pairs", contest?.id, selectedJobId] }),
        queryClient.invalidateQueries({ queryKey: ["admin-contest-plagiarism-pair", contest?.id, selectedJobId, selectedPairId] })
      ]);
    }
  });

  const retryAiMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !selectedPairId) throw new Error("Pair is required");
      return api.retryContestPlagiarismAiAnalysis(contest.id, selectedJobId, selectedPairId);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-contest-plagiarism-pairs", contest?.id, selectedJobId] }),
        queryClient.invalidateQueries({ queryKey: ["admin-contest-plagiarism-pair", contest?.id, selectedJobId, selectedPairId] })
      ]);
    }
  });

  const handlePlagiarismExportAutoDownloadCompleted = React.useCallback(() => {
    toast.success(t("operations.jobAutoDownloadCompleted"));
    void queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
  }, [queryClient, t, toast]);

  const handlePlagiarismExportAutoDownloadFailed = React.useCallback(() => {
    toast.error(t("operations.jobAutoDownloadFailed"));
  }, [t, toast]);

  const startPlagiarismExportAutoDownload = useOperationJobAutoDownload({
    contestId: contest?.id,
    onCompleted: handlePlagiarismExportAutoDownloadCompleted,
    onFailed: handlePlagiarismExportAutoDownloadFailed
  });

  const exportMutation = useMutation({
    mutationFn: async (format: ContestExportFormat) => {
      if (!contest || !selectedJobId) throw new Error("Job is required");
      return api.createContestPlagiarismExportJob(contest.id, selectedJobId, { format });
    },
    onSuccess: async (job) => {
      toast.success(t("operations.jobQueuedAutoDownload"));
      startPlagiarismExportAutoDownload(job.id);
      await queryClient.invalidateQueries({ queryKey: ["admin-operation-jobs"] });
    }
  });

  const rebuildAlertsMutation = useMutation({
    mutationFn: async () => {
      if (!contest || !runFilter) throw new Error("Contest run is required");
      return api.rebuildContestFairnessAlerts(contest.id, runFilter as EntityId);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-fairness-alerts", contest?.id, runFilter] });
    }
  });

  const updateAlertMutation = useMutation({
    mutationFn: async ({ alertId, status, teacherNote }: { alertId: EntityId; status?: FairnessAlertStatus; teacherNote?: string | null }) => {
      if (!contest || !runFilter) throw new Error("Contest run is required");
      return api.updateContestFairnessAlert(contest.id, runFilter as EntityId, alertId, { status, teacherNote });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-contest-fairness-alerts", contest?.id, runFilter] });
    }
  });

  async function createJob() {
    try {
      await createJobMutation.mutateAsync();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.plagiarismCreateFailed"));
    }
  }

  async function updatePair() {
    try {
      await updatePairMutation.mutateAsync();
      toast.success(t("contests.plagiarismReviewedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.plagiarismReviewFailed"));
    }
  }

  async function retryAi() {
    try {
      await retryAiMutation.mutateAsync();
      toast.success(t("contests.plagiarismAiRetriedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.plagiarismAiRetryFailed"));
    }
  }

  async function exportReport(format: ContestExportFormat) {
    try {
      await exportMutation.mutateAsync(format);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.exportFailed"));
    }
  }

  async function rebuildAlerts() {
    try {
      await rebuildAlertsMutation.mutateAsync();
      toast.success(t("contests.fairnessRebuiltMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.fairnessRebuildFailed"));
    }
  }

  async function updateAlert(alertId: EntityId, status?: FairnessAlertStatus, note?: string | null) {
    try {
      await updateAlertMutation.mutateAsync({ alertId, status, teacherNote: note });
      toast.success(t("contests.fairnessUpdatedMessage"));
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("contests.fairnessUpdateFailed"));
    }
  }

  const selectedJob = jobs.find((job) => job.id === selectedJobId) ?? null;
  const pairTotal = pairsQuery.data?.total ?? 0;
  const pairTotalPages = Math.max(1, Math.ceil(pairTotal / PAGE_SIZE));
  const alertTotal = alertsQuery.data?.total ?? 0;
  const alertTotalPages = Math.max(1, Math.ceil(alertTotal / PAGE_SIZE));
  const detail = detailQuery.data;

  return (
    <>
      <SidePanel
        wide
        open={open}
        onOpenChange={onOpenChange}
        title={contest?.title ?? t("contests.plagiarism")}
        description={t("contests.plagiarismCopy")}
        footer={(
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
          </div>
        )}
      >
      <div className="space-y-5">
        <section className="grid gap-4 rounded-xl border border-[var(--oj-border)] bg-white p-4 xl:grid-cols-[minmax(0,1fr)_minmax(220px,320px)_auto]">
          <div>
            <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.plagiarismJobs")}</h3>
            <p className="mt-1 text-sm text-pretty text-[var(--oj-ink-muted)]">{t("contests.plagiarismNotice")}</p>
          </div>
          <select className={selectClass} value={runFilter} onChange={(event) => { setRunFilter(event.target.value); setJobsPage(1); }}>
            <option value="">{t("contests.selectEndedRun")}</option>
            {eligibleRuns.map((run) => (
              <option key={run.id} value={run.id}>{formatRunSelectorLabel(run)}</option>
            ))}
          </select>
          <div className="flex flex-wrap gap-2 xl:justify-end">
            <Button variant="outline" disabled={!runFilter || jobsQuery.isFetching} onClick={() => void jobsQuery.refetch()}>
              <RotateCw className="size-4" aria-hidden="true" />
              {t("common.refresh")}
            </Button>
            <Button disabled={!runFilter || createJobMutation.isPending} onClick={() => void createJob()}>
              <Brain className="size-4" aria-hidden="true" />
              {t("contests.startPlagiarism")}
            </Button>
          </div>
        </section>

        {!eligibleRuns.length ? (
          <EmptyState title={t("contests.noEligibleAiRunsTitle")} description={t("contests.noEligibleAiRunsDescription")} />
        ) : !runFilter ? (
          <EmptyState title={t("contests.aiOpsRunRequired")} description={t("contests.aiOpsRunRequiredCopy")} />
        ) : jobsQuery.isLoading ? (
          <LoadingPanel label={t("common.loading")} />
        ) : jobsQuery.isError ? (
          <ErrorPanel title={t("contests.plagiarismLoadFailed")} action={<Button variant="outline" onClick={() => void jobsQuery.refetch()}>{t("common.refresh")}</Button>} />
        ) : jobs.length ? (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {jobs.map((job) => (
              <button
                key={job.id}
                type="button"
                className={`rounded-xl border p-4 text-left transition-colors ${selectedJobId === job.id ? "border-blue-500 bg-blue-50" : "border-[var(--oj-border)] bg-white hover:bg-[var(--oj-surface-muted)]"}`}
                onClick={() => {
                  setSelectedJobId(job.id);
                  setSelectedPairId(null);
                  setPairPage(1);
                }}
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="truncate text-sm font-semibold tabular-nums text-[var(--oj-ink)]">#{shortId(job.id)}</span>
                  <PlagiarismJobStatusChip status={job.status} />
                </div>
                <div className="mt-3 grid grid-cols-3 gap-2 text-xs tabular-nums text-[var(--oj-ink-muted)]">
                  <span>{t("contests.plagiarismSubmissions")}: {job.totalSubmissions}</span>
                  <span>{t("contests.plagiarismPairs")}: {job.totalPairs}</span>
                  <span>{t("contests.plagiarismHighRisk")}: {job.highRiskPairs}</span>
                </div>
                <div className="mt-2 text-xs text-[var(--oj-ink-muted)]">{formatDateTime(job.createdAt)}</div>
              </button>
            ))}
          </div>
        ) : (
          <EmptyState title={t("contests.noPlagiarismJobsTitle")} description={t("contests.noPlagiarismJobsDescription")} actionLabel={t("contests.startPlagiarism")} onAction={() => void createJob()} />
        )}
        {jobsTotal > 0 ? (
          <PaginationRow
            page={jobsPage}
            totalPages={Math.max(1, Math.ceil(jobsTotal / PLAGIARISM_JOBS_PAGE_SIZE))}
            total={jobsTotal}
            onPageChange={setJobsPage}
          />
        ) : null}

        {selectedJob ? (
          <>
            {selectedJob.errorMessage ? <ErrorPanel title={readableStoredError(selectedJob.errorMessage, locale, selectedJob.errorMessage, "operation")} /> : null}
            <div className="flex flex-wrap gap-2">
              {(["pairs", "graph", "alerts"] as const).map((tab) => (
                <Button
                  key={tab}
                  variant={activeTab === tab ? "primary" : "outline"}
                  onClick={() => setActiveTab(tab)}
                >
                  {tab === "pairs" ? t("contests.plagiarismPairs") : tab === "graph" ? t("contests.plagiarismGraph") : t("contests.fairnessAlerts")}
                </Button>
              ))}
            </div>

            {activeTab === "alerts" ? (
              <section className="grid gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:grid-cols-2 xl:grid-cols-[minmax(180px,1fr)_180px_180px_auto]">
                <select className={selectClass} value={alertTypeFilter} onChange={(event) => setAlertTypeFilter(event.target.value as FairnessAlertType | "")}>
                  {FAIRNESS_ALERT_TYPES.map((type) => (
                    <option key={type || "all"} value={type}>{type ? t(`contests.fairnessAlertType.${type}`) : t("contests.allAlertTypes")}</option>
                  ))}
                </select>
                <select className={selectClass} value={alertSeverityFilter} onChange={(event) => setAlertSeverityFilter(event.target.value as FairnessAlertSeverity | "")}>
                  {FAIRNESS_ALERT_SEVERITIES.map((severity) => (
                    <option key={severity || "all"} value={severity}>{severity ? t(`contests.fairnessAlertSeverity.${severity}`) : t("contests.allSeverities")}</option>
                  ))}
                </select>
                <select className={selectClass} value={alertStatusFilter} onChange={(event) => setAlertStatusFilter(event.target.value as FairnessAlertStatus | "")}>
                  {FAIRNESS_ALERT_STATUSES.map((status) => (
                    <option key={status || "all"} value={status}>{status ? t(`contests.fairnessAlertStatus.${status}`) : t("contests.allAlertStatuses")}</option>
                  ))}
                </select>
                <div className="flex flex-wrap gap-2 xl:justify-end">
                  <Button variant="outline" disabled={alertsQuery.isFetching} onClick={() => void alertsQuery.refetch()}>
                    <RotateCw className="size-4" aria-hidden="true" />
                    {t("common.refresh")}
                  </Button>
                  <Button disabled={rebuildAlertsMutation.isPending} onClick={() => void rebuildAlerts()}>
                    <ShieldCheck className="size-4" aria-hidden="true" />
                    {t("contests.fairnessRebuild")}
                  </Button>
                </div>
              </section>
            ) : (
              <section className="grid gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 md:grid-cols-2 xl:grid-cols-[minmax(180px,1.4fr)_180px_180px_200px_auto]">
                <select className={selectClass} value={problemFilter} onChange={(event) => setProblemFilter(event.target.value)}>
                  <option value="">{t("contests.allProblems")}</option>
                  {problemsQuery.data?.map((problem) => (
                    <option key={problem.id} value={problem.id}>{problem.label} · {problem.displayTitle || `#${problem.problemId}`}</option>
                  ))}
                </select>
                <select className={selectClass} value={languageFilter} onChange={(event) => setLanguageFilter(event.target.value)}>
                  {LANGUAGES.map((language) => (
                    <option key={language || "all"} value={language}>{language || t("submissions.allLanguages")}</option>
                  ))}
                </select>
                <select className={selectClass} value={riskFilter} onChange={(event) => setRiskFilter(event.target.value as PlagiarismRiskLevel | "")}>
                  {PLAGIARISM_RISK_LEVELS.map((risk) => (
                    <option key={risk || "all"} value={risk}>{risk ? t(`contests.plagiarismRisk.${risk}`) : t("contests.allRiskLevels")}</option>
                  ))}
                </select>
                <select className={selectClass} value={reviewFilter} onChange={(event) => setReviewFilter(event.target.value as PlagiarismReviewStatus | "")}>
                  {PLAGIARISM_REVIEW_STATUSES.map((status) => (
                    <option key={status || "all"} value={status}>{status ? t(`contests.plagiarismReview.${status}`) : t("contests.allReviewStatuses")}</option>
                  ))}
                </select>
                <div className="flex flex-wrap gap-2 md:col-span-2 xl:col-span-1 xl:justify-end">
                  <Button variant="outline" disabled={exportMutation.isPending} onClick={() => void exportReport("CSV")}>
                    <Download className="size-4" aria-hidden="true" />
                    CSV
                  </Button>
                  <Button variant="outline" disabled={exportMutation.isPending} onClick={() => void exportReport("XLSX")}>
                    <Download className="size-4" aria-hidden="true" />
                    XLSX
                  </Button>
                </div>
              </section>
            )}

            <div className="space-y-4">
              {activeTab === "pairs" ? (
              <div className="space-y-4">
                {pairsQuery.isLoading ? (
                  <LoadingPanel label={t("common.loading")} />
                ) : pairsQuery.isError ? (
                  <ErrorPanel title={t("contests.plagiarismPairsLoadFailed")} action={<Button variant="outline" onClick={() => void pairsQuery.refetch()}>{t("common.refresh")}</Button>} />
                ) : pairsQuery.data?.records.length ? (
                  <>
                    <TableShell>
                      <table className="w-full min-w-[980px] text-sm">
                        <colgroup>
                          <col className="w-[20%]" />
                          <col className="w-[24%]" />
                          <col className="w-[12%]" />
                          <col className="w-[12%]" />
                          <col className="w-[12%]" />
                          <col className="w-[10%]" />
                          <col className="w-[10%]" />
                        </colgroup>
                        <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                          <tr>
                            <th className="px-4 py-3 text-left">{t("contests.problem")}</th>
                            <th className="px-4 py-3 text-left">{t("contests.plagiarismPair")}</th>
                            <th className="px-4 py-3 text-left">{t("submissions.viewLanguageLabel")}</th>
                            <th className="px-4 py-3 text-left">{t("contests.similarity")}</th>
                            <th className="px-4 py-3 text-left">{t("contests.riskLevel")}</th>
                            <th className="px-4 py-3 text-left">{t("contests.aiAnalysis")}</th>
                            <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-[var(--oj-border-soft)]">
                          {pairsQuery.data.records.map((pair) => (
                            <tr key={pair.id} className="align-middle">
                              <td className="px-4 py-3">
                                <div className="truncate font-medium text-[var(--oj-ink)]" title={pair.problemTitle}>{pair.problemLabel} · {pair.problemTitle || `#${shortId(pair.problemId)}`}</div>
                                <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(pair.id)}</div>
                              </td>
                              <td className="px-4 py-3">
                                <div className="truncate text-[var(--oj-ink)]" title={`${pair.leftDisplayNameSnapshot} / ${pair.rightDisplayNameSnapshot}`}>
                                  {pair.leftDisplayNameSnapshot} ↔ {pair.rightDisplayNameSnapshot}
                                </div>
                                <div className="mt-1 truncate text-xs text-[var(--oj-ink-muted)]" title={`${pair.leftAccountSnapshot} / ${pair.rightAccountSnapshot}`}>
                                  {pair.leftAccountSnapshot} / {pair.rightAccountSnapshot}
                                </div>
                              </td>
                              <td className="px-4 py-3 text-[var(--oj-ink-muted)]">{pair.language}</td>
                              <td className="px-4 py-3 tabular-nums text-[var(--oj-ink)]">{formatPercent(pair.similarity)}</td>
                              <td className="px-4 py-3"><PlagiarismRiskChip risk={pair.riskLevel} /></td>
                              <td className="px-4 py-3"><PlagiarismAiStatusChip status={pair.aiStatus} /></td>
                              <td className="px-4 py-3 text-center">
                                <Button size="sm" variant="outline" onClick={() => setSelectedPairId(pair.id)}>
                                  <Eye className="size-4" aria-hidden="true" />
                                  {t("common.view")}
                                </Button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </TableShell>
                    <PaginationRow page={pairPage} totalPages={pairTotalPages} total={pairTotal} onPageChange={setPairPage} />
                  </>
                ) : (
                  <EmptyState title={t("contests.noPlagiarismPairsTitle")} description={t("contests.noPlagiarismPairsDescription")} />
                )}
              </div>
              ) : activeTab === "graph" ? (
                <PlagiarismGraphView
                  graph={graphQuery.data ?? null}
                  loading={graphQuery.isLoading}
                  error={graphQuery.isError}
                  onRefresh={() => void graphQuery.refetch()}
                  onOpenPair={(edge) => {
                    setSelectedJobId(edge.jobId);
                    setSelectedPairId(edge.pairId);
                  }}
                />
              ) : (
                <FairnessAlertsView
                  alerts={alertsQuery.data?.records ?? []}
                  loading={alertsQuery.isLoading}
                  error={alertsQuery.isError}
                  page={alertPage}
                  totalPages={alertTotalPages}
                  total={alertTotal}
                  notes={alertNotes}
                  setNotes={setAlertNotes}
                  saving={updateAlertMutation.isPending}
                  onRefresh={() => void alertsQuery.refetch()}
                  onPageChange={setAlertPage}
                  onUpdate={(alertId, status, note) => void updateAlert(alertId, status, note)}
                  onOpenPair={(alert) => {
                    const pair = pairsQuery.data?.records.find((candidate) => candidate.id === alert.plagiarismPairId);
                    if (pair) {
                      setSelectedJobId(pair.jobId);
                    }
                    if (alert.plagiarismPairId) {
                      setSelectedPairId(alert.plagiarismPairId);
                    }
                  }}
                />
              )}
            </div>
          </>
        ) : null}
      </div>
      </SidePanel>
      <SidePanel
        open={open && Boolean(selectedPairId)}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) {
            setSelectedPairId(null);
          }
        }}
        title={t("contests.plagiarismPairDetail")}
        description={selectedPairId ? `#${selectedPairId}` : undefined}
        presentation="workspace"
        workspaceSize="lg"
        workspaceHeight="fit"
        footer={(
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => setSelectedPairId(null)}>{t("common.close")}</Button>
          </div>
        )}
      >
        <PlagiarismPairDetail
          detail={detail ?? null}
          loading={detailQuery.isLoading}
          reviewStatus={reviewStatus}
          setReviewStatus={setReviewStatus}
          teacherNote={teacherNote}
          setTeacherNote={setTeacherNote}
          saving={updatePairMutation.isPending}
          retrying={retryAiMutation.isPending}
          onSave={() => void updatePair()}
          onRetryAi={() => void retryAi()}
        />
      </SidePanel>
    </>
  );
}

function PlagiarismGraphView({
  graph,
  loading,
  error,
  onRefresh,
  onOpenPair
}: {
  graph: ContestPlagiarismGraphResponse | null;
  loading: boolean;
  error: boolean;
  onRefresh: () => void;
  onOpenPair: (edge: ContestPlagiarismGraphEdge) => void;
}) {
  const { t } = useI18n();
  const edgeById = React.useMemo(() => {
    const map = new Map<EntityId, ContestPlagiarismGraphEdge>();
    graph?.edges.forEach((edge) => map.set(edge.pairId, edge));
    return map;
  }, [graph]);

  if (loading) return <LoadingPanel label={t("common.loading")} />;
  if (error) {
    return <ErrorPanel title={t("contests.plagiarismPairsLoadFailed")} action={<Button variant="outline" onClick={onRefresh}>{t("common.refresh")}</Button>} />;
  }
  if (!graph || graph.summary.edgeCount === 0) {
    return <EmptyState title={t("contests.graphEmptyTitle")} description={t("contests.graphEmptyDescription")} />;
  }

  return (
    <section className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <InfoPill label={t("contests.graphNodes")} value={String(graph.summary.nodeCount)} />
        <InfoPill label={t("contests.graphEdges")} value={String(graph.summary.edgeCount)} />
        <InfoPill label={t("contests.plagiarismHighRisk")} value={String(graph.summary.highRiskEdgeCount)} />
        <InfoPill label={t("contests.plagiarismRisk.CRITICAL")} value={String(graph.summary.criticalRiskEdgeCount)} />
        <InfoPill label={t("contests.graphClusters")} value={String(graph.summary.repeatedPairCount)} />
      </div>
      {graph.nodes.length > 18 ? (
        <div className="grid gap-3 lg:grid-cols-2">
          {graph.clusters.length ? graph.clusters.map((cluster) => {
            const firstPairId = cluster.pairIds[0];
            const firstEdge = firstPairId ? edgeById.get(firstPairId) : null;
            return (
              <article key={`${cluster.leftParticipantId}-${cluster.rightParticipantId}`} className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h4 className="truncate text-sm font-semibold text-[var(--oj-ink)]" title={`${cluster.leftDisplayNameSnapshot} ↔ ${cluster.rightDisplayNameSnapshot}`}>
                      {cluster.leftDisplayNameSnapshot} ↔ {cluster.rightDisplayNameSnapshot}
                    </h4>
                    <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
                      {t("contests.plagiarismPairs")}: {cluster.pairCount} · {t("contests.plagiarismHighRisk")}: {cluster.highRiskPairCount}
                    </p>
                  </div>
                  <Badge tone={cluster.highRiskPairCount > 0 ? "red" : "amber"}>{formatPercent(cluster.maxSimilarity)}</Badge>
                </div>
                <div className="mt-4 flex justify-end">
                  <Button size="sm" variant="outline" disabled={!firstEdge} onClick={() => firstEdge && onOpenPair(firstEdge)}>
                    <Eye className="size-4" aria-hidden="true" />
                    {t("common.view")}
                  </Button>
                </div>
              </article>
            );
          }) : (
            <EmptyState title={t("contests.graphClusterFallback")} description={t("contests.graphEmptyDescription")} />
          )}
        </div>
      ) : (
        <PlagiarismGraphCanvas graph={graph} onOpenPair={onOpenPair} />
      )}
    </section>
  );
}

function PlagiarismGraphCanvas({
  graph,
  onOpenPair
}: {
  graph: ContestPlagiarismGraphResponse;
  onOpenPair: (edge: ContestPlagiarismGraphEdge) => void;
}) {
  const { t } = useI18n();
  const positions = React.useMemo(() => {
    const cx = 420;
    const cy = 260;
    const radius = Math.max(150, Math.min(220, 90 + graph.nodes.length * 10));
    return new Map(graph.nodes.map((node, index) => {
      const angle = (Math.PI * 2 * index) / Math.max(1, graph.nodes.length) - Math.PI / 2;
      return [node.participantId, {
        x: cx + Math.cos(angle) * radius,
        y: cy + Math.sin(angle) * radius
      }];
    }));
  }, [graph.nodes]);

  return (
    <div className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="overflow-x-auto">
        <svg className="min-w-[840px]" viewBox="0 0 840 520" role="img" aria-label={t("contests.plagiarismGraph")}>
          <rect x="0" y="0" width="840" height="520" rx="16" fill="var(--oj-surface-muted)" />
          {graph.edges.map((edge) => {
            const left = positions.get(edge.leftParticipantId);
            const right = positions.get(edge.rightParticipantId);
            if (!left || !right) return null;
            const riskColor = edge.riskLevel === "CRITICAL" ? "#dc2626" : edge.riskLevel === "HIGH" ? "#ef4444" : edge.riskLevel === "MEDIUM" ? "#f59e0b" : "#94a3b8";
            const width = edge.riskLevel === "CRITICAL" ? 4 : edge.riskLevel === "HIGH" ? 3 : 2;
            return (
              <g key={edge.pairId}>
                <line
                  x1={left.x}
                  y1={left.y}
                  x2={right.x}
                  y2={right.y}
                  stroke={riskColor}
                  strokeWidth={width}
                  strokeOpacity="0.72"
                />
                <g
                  role="button"
                  tabIndex={0}
                  className="cursor-pointer focus:outline-none"
                  onClick={() => onOpenPair(edge)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onOpenPair(edge);
                    }
                  }}
                  aria-label={`${edge.leftDisplayNameSnapshot} ${edge.rightDisplayNameSnapshot}`}
                >
                  <circle cx={(left.x + right.x) / 2} cy={(left.y + right.y) / 2} r="12" fill="white" stroke={riskColor} strokeWidth="2" />
                  <text x={(left.x + right.x) / 2} y={(left.y + right.y) / 2 + 4} textAnchor="middle" fontSize="10" fill={riskColor}>
                    {Math.round(edge.similarity * 100)}
                  </text>
                </g>
              </g>
            );
          })}
          {graph.nodes.map((node) => {
            const position = positions.get(node.participantId);
            if (!position) return null;
            const fill = node.criticalRiskPairCount > 0 ? "#fee2e2" : node.highRiskPairCount > 0 ? "#ffedd5" : "#eff6ff";
            const stroke = node.criticalRiskPairCount > 0 ? "#dc2626" : node.highRiskPairCount > 0 ? "#f97316" : "#2563eb";
            return (
              <g key={node.participantId}>
                <circle cx={position.x} cy={position.y} r="42" fill={fill} stroke={stroke} strokeWidth="2" />
                <text x={position.x} y={position.y - 2} textAnchor="middle" fontSize="12" fontWeight="700" fill="#0f172a">
                  {truncateSvgLabel(node.displayNameSnapshot || node.accountSnapshot)}
                </text>
                <text x={position.x} y={position.y + 16} textAnchor="middle" fontSize="10" fill="#475569">
                  {t("contests.plagiarismPairs")} {node.pairCount}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <div className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {graph.nodes.map((node) => (
          <div key={node.participantId} className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-xs">
            <div className="truncate font-semibold text-[var(--oj-ink)]" title={`${node.displayNameSnapshot} · ${node.accountSnapshot}`}>
              {node.displayNameSnapshot} · {node.accountSnapshot}
            </div>
            <div className="mt-1 text-[var(--oj-ink-muted)]">
              {t("contests.plagiarismPairs")}: {node.pairCount} · {t("contests.plagiarismHighRisk")}: {node.highRiskPairCount}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function FairnessAlertsView({
  alerts,
  loading,
  error,
  page,
  totalPages,
  total,
  notes,
  setNotes,
  saving,
  onRefresh,
  onPageChange,
  onUpdate,
  onOpenPair
}: {
  alerts: FairnessAlertResponse[];
  loading: boolean;
  error: boolean;
  page: number;
  totalPages: number;
  total: number;
  notes: Record<EntityId, string>;
  setNotes: React.Dispatch<React.SetStateAction<Record<EntityId, string>>>;
  saving: boolean;
  onRefresh: () => void;
  onPageChange: (page: number) => void;
  onUpdate: (alertId: EntityId, status: FairnessAlertStatus, note: string | null) => void;
  onOpenPair: (alert: FairnessAlertResponse) => void;
}) {
  const { t } = useI18n();
  if (loading) return <LoadingPanel label={t("common.loading")} />;
  if (error) {
    return <ErrorPanel title={t("contests.fairnessLoadFailed")} action={<Button variant="outline" onClick={onRefresh}>{t("common.refresh")}</Button>} />;
  }
  if (!alerts.length) {
    return <EmptyState title={t("contests.noFairnessAlertsTitle")} description={t("contests.noFairnessAlertsDescription")} />;
  }
  return (
    <section className="space-y-3">
      {alerts.map((alert) => {
        const note = notes[alert.id] ?? alert.teacherNote ?? "";
        const evidenceEntries = Object.entries(alert.evidence ?? {}).slice(0, 6);
        return (
          <article key={alert.id} className="space-y-4 rounded-xl border border-[var(--oj-border)] bg-white p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <FairnessSeverityChip severity={alert.severity} />
                  <FairnessStatusChip status={alert.status} />
                  <Badge tone="blue">{t(`contests.fairnessAlertType.${alert.type}`)}</Badge>
                </div>
                <h4 className="mt-3 text-base font-semibold text-[var(--oj-ink)]">{alert.title}</h4>
                <p className="mt-1 text-sm text-pretty text-[var(--oj-ink-muted)]">{alert.summary}</p>
              </div>
              <div className="text-right text-xs tabular-nums text-[var(--oj-ink-muted)]">
                <div>#{shortId(alert.id)}</div>
                <div className="mt-1">{formatDateTime(alert.createdAt)}</div>
              </div>
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <DetailTile label={t("contests.participant")} value={[alert.primaryDisplayName, alert.secondaryDisplayName].filter(Boolean).join(" / ") || "--"} />
              <DetailTile label={t("contests.alertRelatedPair")} value={alert.plagiarismPairId ? `#${shortId(alert.plagiarismPairId)}` : "--"} />
            </div>
            <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
              <h5 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.alertEvidence")}</h5>
              {evidenceEntries.length ? (
                <dl className="mt-2 grid gap-2 md:grid-cols-2">
                  {evidenceEntries.map(([key, value]) => {
                    const formattedValue = formatEvidenceValue(t, key, value);
                    return (
                      <div key={key} className="min-w-0 rounded-lg bg-white px-3 py-2">
                        <dt className="text-xs text-[var(--oj-ink-muted)]">{formatEvidenceLabel(t, key)}</dt>
                        <dd className="mt-1 truncate text-sm text-[var(--oj-ink)]" title={formattedValue}>{formattedValue}</dd>
                      </div>
                    );
                  })}
                </dl>
              ) : (
                <p className="mt-2 text-sm text-[var(--oj-ink-muted)]">--</p>
              )}
            </div>
            <div className="grid gap-3 lg:grid-cols-[220px_minmax(0,1fr)_auto] lg:items-end">
              <Field label={t("contests.reviewStatus")}>
                <select
                  className={selectClass}
                  value={alert.status}
                  onChange={(event) => onUpdate(alert.id, event.target.value as FairnessAlertStatus, note || null)}
                >
                  {FAIRNESS_ALERT_STATUSES.filter(Boolean).map((status) => (
                    <option key={status} value={status}>{t(`contests.fairnessAlertStatus.${status}`)}</option>
                  ))}
                </select>
              </Field>
              <Field label={t("contests.teacherNote")}>
                <textarea
                  className={`${textareaClass} min-h-24 resize-y`}
                  maxLength={500}
                  value={note}
                  onChange={(event) => setNotes((prev) => ({ ...prev, [alert.id]: event.target.value }))}
                  placeholder={t("contests.alertReviewNotePlaceholder")}
                />
              </Field>
              <div className="flex flex-wrap gap-2 lg:justify-end">
                {alert.plagiarismPairId ? (
                  <Button size="sm" variant="outline" onClick={() => onOpenPair(alert)}>
                    <Eye className="size-4" aria-hidden="true" />
                    {t("common.view")}
                  </Button>
                ) : null}
                <Button size="sm" disabled={saving} onClick={() => onUpdate(alert.id, alert.status, note || null)}>
                  <Save className="size-4" aria-hidden="true" />
                  {t("contests.saveReview")}
                </Button>
              </div>
            </div>
          </article>
        );
      })}
      <PaginationRow page={page} totalPages={totalPages} total={total} onPageChange={onPageChange} />
    </section>
  );
}

function FairnessSeverityChip({ severity }: { severity: FairnessAlertSeverity }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = severity === "CRITICAL" || severity === "HIGH" ? "red"
    : severity === "MEDIUM" ? "amber"
      : "neutral";
  return <Badge tone={tone}>{t(`contests.fairnessAlertSeverity.${severity}`)}</Badge>;
}

function FairnessStatusChip({ status }: { status: FairnessAlertStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "CONFIRMED" ? "red"
    : status === "REVIEWED" ? "green"
      : status === "DISMISSED" ? "neutral"
        : "amber";
  return <Badge tone={tone}>{t(`contests.fairnessAlertStatus.${status}`)}</Badge>;
}

function PlagiarismPairDetail({
  detail,
  loading,
  reviewStatus,
  setReviewStatus,
  teacherNote,
  setTeacherNote,
  saving,
  retrying,
  onSave,
  onRetryAi
}: {
  detail: PlagiarismPairDetailResponse | null;
  loading: boolean;
  reviewStatus: PlagiarismReviewStatus;
  setReviewStatus: (value: PlagiarismReviewStatus) => void;
  teacherNote: string;
  setTeacherNote: (value: string) => void;
  saving: boolean;
  retrying: boolean;
  onSave: () => void;
  onRetryAi: () => void;
}) {
  const { t } = useI18n();
  if (loading) return <LoadingPanel label={t("common.loading")} />;
  if (!detail) {
    return (
      <aside className="rounded-xl border border-dashed border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-5 text-sm leading-6 text-[var(--oj-ink-muted)]">
        {t("contests.selectPlagiarismPairHint")}
      </aside>
    );
  }
  const pair = detail.pair;
  return (
    <aside className="space-y-4 rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div>
        <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.plagiarismPairDetail")}</h3>
        <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{pair.id}</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <DetailTile label={t("contests.problem")} value={`${pair.problemLabel} · ${pair.problemTitle || `#${pair.problemId}`}`} />
        <DetailTile label={t("contests.similarity")} value={`${formatPercent(pair.similarity)} · ${t("contests.tokenCount", { count: pair.matchedTokens })}`} />
        <DetailTile label={t("contests.leftSubmission")} value={`${pair.leftDisplayNameSnapshot} · #${shortId(pair.leftSubmissionId)}`} />
        <DetailTile label={t("contests.rightSubmission")} value={`${pair.rightDisplayNameSnapshot} · #${shortId(pair.rightSubmissionId)}`} />
      </div>
      <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
        <div className="mb-2 flex items-center justify-between gap-2">
          <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.aiAnalysis")}</h4>
          <Button size="sm" variant="outline" disabled={retrying} onClick={onRetryAi}>
            <RotateCw className="size-4" aria-hidden="true" />
            {t("contests.retryAiAnalysis")}
          </Button>
        </div>
        {detail.aiAnalysis ? (
          <MarkdownView content={detail.aiAnalysis} className="rounded-lg bg-white" />
        ) : detail.aiErrorMessage ? (
          <ErrorPanel title={detail.aiErrorMessage} />
        ) : (
          <p className="text-sm text-[var(--oj-ink-muted)]">{t("contests.noAiAnalysis")}</p>
        )}
      </section>
      <section className="space-y-3">
        <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.fragments")}</h4>
        {detail.fragments.length ? detail.fragments.map((fragment) => (
          <div key={fragment.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-3">
            <div className="mb-2 text-xs font-medium tabular-nums text-[var(--oj-ink-muted)]">
              #{fragment.sequenceNo} · {t("contests.tokenCount", { count: fragment.tokenLength })}
            </div>
            <div className="grid gap-2 xl:grid-cols-2">
              <pre className="overflow-x-auto whitespace-pre rounded-lg bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink)]">{fragment.leftExcerpt || "--"}</pre>
              <pre className="overflow-x-auto whitespace-pre rounded-lg bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink)]">{fragment.rightExcerpt || "--"}</pre>
            </div>
          </div>
        )) : (
          <p className="rounded-xl border border-dashed border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-3 text-sm text-[var(--oj-ink-muted)]">{t("contests.noFragments")}</p>
        )}
      </section>
      <section className="space-y-3 rounded-xl border border-[var(--oj-border)] bg-white p-3">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <Field label={t("contests.reviewStatus")}>
            <select className={`${selectClass} sm:w-[220px]`} value={reviewStatus} onChange={(event) => setReviewStatus(event.target.value as PlagiarismReviewStatus)}>
              {PLAGIARISM_REVIEW_STATUSES.filter(Boolean).map((status) => (
                <option key={status} value={status}>{t(`contests.plagiarismReview.${status}`)}</option>
              ))}
            </select>
          </Field>
          <Button className="w-fit" disabled={saving} onClick={onSave}>
            <Save className="size-4" aria-hidden="true" />
            {t("contests.saveReview")}
          </Button>
        </div>
        <Field label={t("contests.teacherNote")}>
          <textarea
            className={`${textareaClass} min-h-28 resize-y`}
            maxLength={500}
            value={teacherNote}
            onChange={(event) => setTeacherNote(event.target.value)}
            placeholder={t("contests.teacherNotePlaceholder")}
          />
        </Field>
      </section>
      <p className="flex gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-950">
        <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {t("contests.plagiarismBoundary")}
      </p>
    </aside>
  );
}

function ContestSubmissionDetail({
  submission,
  loading,
  accessReason,
  setAccessReason,
  accessing,
  onAccess
}: {
  submission: ContestSubmissionResponse | null;
  loading: boolean;
  accessReason: string;
  setAccessReason: (value: string) => void;
  accessing: boolean;
  onAccess: () => void;
}) {
  const { t, locale } = useI18n();
  if (loading) return <LoadingPanel label={t("common.loading")} />;
  if (!submission) {
    return (
      <div className="rounded-xl border border-dashed border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-5 text-sm leading-6 text-[var(--oj-ink-muted)]">
        {t("contests.selectSubmissionHint")}
      </div>
    );
  }
  return (
    <aside className="space-y-4 rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div>
        <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.submissionDetail")}</h3>
        <p className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{submission.id}</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <DetailTile label={t("contests.participant")} value={`${submission.displayNameSnapshot} · ${submission.accountSnapshot}`} />
        <DetailTile label={t("contests.problem")} value={`${submission.problemLabel || "--"} · ${submission.problemTitle || `#${submission.problemId}`}`} />
        <DetailTile label={t("submissions.viewLanguageLabel")} value={submission.language} />
        <DetailTile label={t("common.status")} value={t(`submissionStatus.${submission.status}`)} />
        <DetailTile label={t("submissions.viewRunTimeLabel")} value={submission.runTimeMillis ? `${submission.runTimeMillis} ms` : "--"} />
        <DetailTile label={t("submissions.viewMemoryLabel")} value={submission.memoryKb ? formatBytes(submission.memoryKb * 1024) : "--"} />
        <DetailTile label={t("contests.maxScore")} value={formatScoreSummary(submission.score, submission.maxScore)} />
      </div>
      {submission.caseResults?.length ? <SubmissionCaseResultsTable caseResults={submission.caseResults} /> : null}
      {submission.codeIncluded && submission.code ? (
        <div className="overflow-hidden rounded-xl border border-[var(--oj-border-soft)] bg-white">
          <div className="flex items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] px-3 py-2">
            <span className="text-sm font-medium text-[var(--oj-ink)]">{t("contests.sourceCode")}</span>
          </div>
          <MarkdownView content={codeToMarkdown(submission.code, submission.language)} className="[&_pre]:max-h-[440px] [&_pre]:overflow-auto" />
        </div>
      ) : (
        <div className="space-y-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
          <div className="flex gap-2">
            <ShieldCheck className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <p>{t("contests.sourceAuditNotice")}</p>
          </div>
          <textarea
            className={`${textareaClass} min-h-20 bg-white`}
            maxLength={240}
            value={accessReason}
            onChange={(event) => setAccessReason(event.target.value)}
            placeholder={t("contests.sourceAccessReasonPlaceholder")}
          />
          <Button disabled={accessing} onClick={onAccess}>
            <Eye className="size-4" aria-hidden="true" />
            {t("contests.accessSourceCode")}
          </Button>
        </div>
      )}
      {submission.stderrExcerpt ? <OutputBlock label={t("submissions.viewStderrLabel")} value={submission.stderrExcerpt} /> : null}
      <OutputBlock label={t("submissions.viewJudgeMessage")} value={readableJudgeMessage(submission.judgeMessage, submission.status, locale, t(`submissionStatus.${submission.status}`))} />
    </aside>
  );
}

function DetailTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
      <div className="text-xs text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-1 truncate text-sm font-medium text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function OutputBlock({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="mb-1 text-sm font-medium text-[var(--oj-ink)]">{label}</div>
      <pre className="max-h-40 overflow-auto rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink)]">{value}</pre>
    </div>
  );
}

function SubmissionCaseResultsTable({ caseResults }: { caseResults: NonNullable<ContestSubmissionResponse["caseResults"]> }) {
  const { t } = useI18n();
  const sorted = [...caseResults].sort((left, right) => left.caseIndex - right.caseIndex);
  return (
    <section className="overflow-hidden rounded-xl border border-[var(--oj-border-soft)] bg-white">
      <div className="border-b border-[var(--oj-border-soft)] px-3 py-2">
        <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.caseResults")}</h4>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
            <tr>
              <th className="px-3 py-2 text-left">{t("contests.caseName")}</th>
              <th className="px-3 py-2 text-left">{t("common.status")}</th>
              <th className="px-3 py-2 text-center">{t("contests.caseScore")}</th>
              <th className="px-3 py-2 text-center">{t("submissions.viewRunTimeLabel")}</th>
              <th className="px-3 py-2 text-center">{t("submissions.viewMemoryLabel")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--oj-border-soft)]">
            {sorted.map((item) => (
              <tr key={item.id}>
                <td className="px-3 py-2">
                  <div className="font-medium text-[var(--oj-ink)]">{item.caseName || `${t("contests.caseName")} ${item.caseIndex + 1}`}</div>
                  <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{item.caseIndex + 1}</div>
                </td>
                <td className="px-3 py-2"><SubmissionStatusChip status={item.status} /></td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink)]">{formatScoreSummary(item.score, item.maxScore)}</td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink-muted)]">{item.timeMillis == null ? "--" : `${item.timeMillis} ms`}</td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink-muted)]">{item.memoryKb == null ? "--" : formatBytes(item.memoryKb * 1024)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function SubmissionStatusChip({ status }: { status: SubmissionStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "ACCEPTED" ? "green"
    : status === "QUEUED" || status === "RUNNING" ? "blue"
      : status === "SYSTEM_ERROR" || status === "RUNTIME_ERROR" || status === "COMPILE_ERROR" ? "red"
        : status === "WRONG_ANSWER" || status === "TIME_LIMIT_EXCEEDED" || status === "MEMORY_LIMIT_EXCEEDED" ? "amber"
          : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`submissionStatus.${status}`)}</Badge>;
}

function PlagiarismJobStatusChip({ status }: { status: PlagiarismJobStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "COMPLETED" ? "green"
    : status === "QUEUED" || status === "RUNNING" ? "blue"
      : status === "FAILED" ? "red"
        : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.plagiarismJobStatus.${status}`)}</Badge>;
}

function PlagiarismRiskChip({ risk }: { risk: PlagiarismRiskLevel }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = risk === "CRITICAL" || risk === "HIGH" ? "red"
    : risk === "MEDIUM" ? "amber"
      : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.plagiarismRisk.${risk}`)}</Badge>;
}

function PlagiarismAiStatusChip({ status }: { status: PlagiarismAiStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "COMPLETED" ? "green"
    : status === "RUNNING" || status === "PENDING" ? "blue"
      : status === "FAILED" ? "red"
        : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.plagiarismAiStatus.${status}`)}</Badge>;
}

function PaginationRow({ page, totalPages, total, onPageChange }: { page: number; totalPages: number; total: number; onPageChange: (page: number) => void }) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-white px-4 py-3 text-sm text-[var(--oj-ink-muted)] sm:flex-row sm:items-center sm:justify-between">
      <span className="tabular-nums">{page} / {totalPages} · {total}</span>
      <div className="flex gap-2">
        <Button variant="outline" disabled={page <= 1} onClick={() => onPageChange(Math.max(1, page - 1))}>{t("common.previous")}</Button>
        <Button variant="outline" disabled={page >= totalPages} onClick={() => onPageChange(Math.min(totalPages, page + 1))}>{t("common.next")}</Button>
      </div>
    </div>
  );
}

function AdminScoreboardTimeline({
  ticks,
  currentIndex,
  onChange
}: {
  ticks: Array<{ bucketMillis: number }>;
  currentIndex: number | null;
  onChange: (index: number | null) => void;
}) {
  const { t } = useI18n();
  const latestIndex = Math.max(0, ticks.length - 1);
  const [draftIndex, setDraftIndex] = React.useState(currentIndex ?? latestIndex);
  const [draftDirty, setDraftDirty] = React.useState(false);

  React.useEffect(() => {
    setDraftIndex(currentIndex ?? latestIndex);
    setDraftDirty(false);
  }, [currentIndex, latestIndex]);

  const selected = currentIndex == null ? null : ticks[currentIndex];
  const preview = ticks[draftIndex] ?? ticks[latestIndex] ?? null;
  const commitDraft = React.useCallback(() => {
    if (!draftDirty) return;
    onChange(draftIndex);
    setDraftDirty(false);
  }, [draftDirty, draftIndex, onChange]);

  return (
    <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.scoreboardTimeline")}</h3>
          <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
            {selected
              ? t("contests.selectedTimelineMinute", { time: formatContestClock(selected.bucketMillis) })
              : draftDirty && preview
                ? t("contests.selectedTimelineMinute", { time: formatContestClock(preview.bucketMillis) })
                : t("contests.liveScoreboard")}
          </p>
        </div>
        <Button size="sm" variant="outline" type="button" disabled={currentIndex === null} onClick={() => onChange(null)}>{t("contests.latestTimeline")}</Button>
      </div>
      <input
        className="mt-3 w-full accent-[var(--oj-primary)]"
        type="range"
        min={0}
        max={Math.max(0, ticks.length - 1)}
        value={draftIndex}
        onChange={(event) => {
          setDraftIndex(Number(event.target.value));
          setDraftDirty(true);
        }}
        onMouseUp={commitDraft}
        onTouchEnd={commitDraft}
        onKeyUp={commitDraft}
        onBlur={commitDraft}
      />
    </section>
  );
}

function codeToMarkdown(code: string, language: string) {
  const maxBacktickRun = Math.max(0, ...Array.from(code.matchAll(/`+/g), (match) => match[0].length));
  const fence = "`".repeat(Math.max(3, maxBacktickRun + 1));
  const markdownLanguage = language.toLowerCase() === "python" ? "python"
    : language.toLowerCase() === "java" ? "java"
      : language.toLowerCase() === "cpp" ? "cpp"
        : "";
  return `${fence}${markdownLanguage}\n${code.replace(/\r\n/g, "\n")}\n${fence}`;
}

function InfoPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-4">
      <div className="text-xs font-medium text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-1 text-sm font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function StatusChip({ status }: { status: ContestStatus }) {
  const { t } = useI18n();
  const tone = status === "PUBLISHED" ? "green" : status === "ARCHIVED" ? "neutral" : "amber";
  return <Badge className="w-fit" tone={tone}>{t(`contests.status.${status}`)}</Badge>;
}

function RunStatusChip({ status }: { status: ContestRunStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "RUNNING" ? "green"
    : status === "SCHEDULED" ? "blue"
      : status === "DRAFT" ? "amber"
        : status === "EXPIRED" ? "red"
        : status === "ARCHIVED" ? "neutral"
          : "green";
  return <Badge className="w-fit" tone={tone}>{t(`contests.runStatus.${status}`)}</Badge>;
}

function RegistrationStatusChip({ status }: { status: ContestRegistrationStatus }) {
  const { t } = useI18n();
  const tone: "green" | "blue" | "amber" | "red" | "neutral" = status === "APPROVED" ? "green"
    : status === "PENDING" ? "amber"
      : status === "INVITED" ? "blue"
        : status === "REJECTED" || status === "DECLINED" ? "red"
          : "neutral";
  return <Badge className="w-fit" tone={tone}>{t(`contests.registrationStatus.${status}`)}</Badge>;
}

function updateRow(setRows: React.Dispatch<React.SetStateAction<ProblemRow[]>>, index: number, patch: Partial<ProblemRow>) {
  setRows((current) => current.map((row, rowIndex) => rowIndex === index ? { ...row, ...patch } : row));
}

function moveRow(setRows: React.Dispatch<React.SetStateAction<ProblemRow[]>>, index: number, offset: -1 | 1) {
  setRows((current) => {
    const nextIndex = index + offset;
    if (nextIndex < 0 || nextIndex >= current.length) return current;
    const next = [...current];
    const [item] = next.splice(index, 1);
    next.splice(nextIndex, 0, item);
    return next.map((row, rowIndex) => ({ ...row, sortOrder: rowIndex }));
  });
}

const PICKER_PAGE_SIZE = 10;

function ContestProblemPicker({ onAdd }: { onAdd: (problems: ProblemResponse[]) => void }) {
  const { t } = useI18n();
  const [keyword, setKeyword] = React.useState("");
  const [sort, setSort] = React.useState<ProblemListSort>("NEWEST");
  const [page, setPage] = React.useState(1);
  const [selected, setSelected] = React.useState<Map<EntityId, ProblemResponse>>(new Map());

  const pickerQuery = useQuery({
    queryKey: ["contest-problem-picker", keyword.trim(), sort, page],
    queryFn: () => api.problems({
      page,
      pageSize: PICKER_PAGE_SIZE,
      keyword: keyword.trim() || undefined,
      sort,
      status: "ACTIVE",
      visibility: "ALL"
    })
  });

  const problems = pickerQuery.data?.records ?? [];
  const total = pickerQuery.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PICKER_PAGE_SIZE));
  const allPageSelected = problems.length > 0 && problems.every((problem) => selected.has(problem.id));

  function toggleProblem(problem: ProblemResponse) {
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(problem.id)) {
        next.delete(problem.id);
      } else {
        next.set(problem.id, problem);
      }
      return next;
    });
  }

  function togglePage() {
    setSelected((current) => {
      const next = new Map(current);
      if (allPageSelected) {
        problems.forEach((problem) => next.delete(problem.id));
      } else {
        problems.forEach((problem) => next.set(problem.id, problem));
      }
      return next;
    });
  }

  function confirmAdd() {
    if (!selected.size) return;
    onAdd([...selected.values()]);
    setSelected(new Map());
  }

  return (
    <div className="space-y-3 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
      <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_200px]">
        <input
          className={inputClass}
          value={keyword}
          onChange={(event) => { setKeyword(event.target.value); setPage(1); }}
          placeholder={t("contests.problemSearchPlaceholder")}
        />
        <select className={selectClass} value={sort} onChange={(event) => { setSort(event.target.value as ProblemListSort); setPage(1); }}>
          <option value="NEWEST">{t("problems.sortNewest")}</option>
          <option value="OLDEST">{t("problems.sortOldest")}</option>
          <option value="DIFFICULTY_ASC">{t("problems.sortDifficultyAsc")}</option>
          <option value="DIFFICULTY_DESC">{t("problems.sortDifficultyDesc")}</option>
        </select>
      </div>

      {pickerQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : pickerQuery.isError ? (
        <ErrorPanel title={t("problems.loadFailed")} action={<Button variant="outline" onClick={() => void pickerQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : problems.length === 0 ? (
        <EmptyState title={t("contests.problemPickerEmpty")} description={t("contests.problemPickerEmptyCopy")} />
      ) : (
        <TableShell>
          <table className="w-full min-w-[640px] text-sm">
            <thead className="border-b border-[var(--oj-border-soft)] bg-white text-xs font-semibold text-[var(--oj-ink-muted)]">
              <tr>
                <th className="w-10 px-3 py-2">
                  <input type="checkbox" checked={allPageSelected} onChange={togglePage} aria-label={t("contests.problemPickerSelectPage")} />
                </th>
                <th className="px-3 py-2 text-left">{t("common.title")}</th>
                <th className="px-3 py-2 text-left">{t("common.difficulty")}</th>
                <th className="px-3 py-2 text-left">{t("common.tags")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--oj-border-soft)] bg-white">
              {problems.map((problem) => (
                <tr
                  key={problem.id}
                  className={`cursor-pointer transition-colors ${selected.has(problem.id) ? "bg-blue-50" : "hover:bg-[var(--oj-surface-muted)]"}`}
                  onClick={(event) => {
                    if (shouldToggleRowSelection(event)) toggleProblem(problem);
                  }}
                >
                  <td className="px-3 py-2">
                    <input type="checkbox" checked={selected.has(problem.id)} onChange={() => toggleProblem(problem)} aria-label={problem.title} />
                  </td>
                  <td className="px-3 py-2">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-medium text-[var(--oj-ink)]">{problem.title}</span>
                      <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(problem.id)}</span>
                      {problem.visibility === "PRIVATE" ? <Badge tone="red">{t("problems.visibilityPrivate")}</Badge> : null}
                    </span>
                  </td>
                  <td className="px-3 py-2">
                    <Badge tone="neutral">{t(`difficulty.${problem.difficulty}`, undefined, String(problem.difficulty))}</Badge>
                  </td>
                  <td className="px-3 py-2">
                    <span className="flex flex-wrap gap-1">
                      {problem.tags.slice(0, 4).map((tag) => <Badge key={tag} tone="neutral">{tag}</Badge>)}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableShell>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <PaginationRow page={page} totalPages={totalPages} total={total} onPageChange={setPage} />
        <Button type="button" disabled={!selected.size} onClick={confirmAdd}>
          <Plus className="size-4" aria-hidden="true" />
          {t("contests.problemPickerAdd", { count: selected.size })}
        </Button>
      </div>
    </div>
  );
}

function nextLabel(index: number) {
  if (index < 26) return String.fromCharCode(65 + index);
  return `P${index + 1}`;
}

function toDateTimeLocal(value?: string | null) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function fromDateTimeLocal(value: string) {
  return new Date(value).toISOString();
}

function parseOptionalMillis(value: string): number | "" {
  if (!value.trim()) return "";
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : "";
}

function formatContestClock(millis: number) {
  const totalMinutes = Math.max(0, Math.floor(millis / 60_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return `${hours}:${String(minutes).padStart(2, "0")}`;
}

function formatPercent(value: number) {
  return `${Math.round(value * 1000) / 10}%`;
}

function truncateSvgLabel(value: string) {
  return value.length > 8 ? `${value.slice(0, 7)}…` : value;
}

function formatEvidenceLabel(t: ReturnType<typeof useI18n>["t"], key: string) {
  return t(`contests.evidenceLabel.${key}`, undefined, key);
}

function formatEvidenceValue(t: ReturnType<typeof useI18n>["t"], key: string, value: unknown): string {
  if (value == null) return "--";
  if (Array.isArray(value)) return value.map((item) => formatEvidenceScalar(t, key, item)).join(", ");
  if (typeof value === "object") {
    const entries: string[] = Object.entries(value as Record<string, unknown>)
      .filter(([, nestedValue]) => nestedValue != null && nestedValue !== "")
      .slice(0, 4)
      .map(([nestedKey, nestedValue]) => `${formatEvidenceLabel(t, nestedKey)}：${formatEvidenceValue(t, nestedKey, nestedValue)}`);
    return entries.length ? entries.join("；") : "--";
  }
  return formatEvidenceScalar(t, key, value);
}

function formatEvidenceScalar(t: ReturnType<typeof useI18n>["t"], key: string, value: unknown) {
  if (typeof value === "boolean") return value ? t("common.yes") : t("common.no");
  if (typeof value === "number" && Number.isFinite(value)) {
    if (key === "similarity" || key === "maxSimilarity") return formatPercent(value);
    if (key === "deltaSeconds") return t("contests.secondsCount", { count: value });
    return Number.isInteger(value) ? String(value) : String(Math.round(value * 1000) / 1000);
  }
  if (typeof value === "string") {
    if ((key === "riskLevel" || value === "LOW" || value === "MEDIUM" || value === "HIGH" || value === "CRITICAL") && PLAGIARISM_RISK_LEVELS.includes(value as PlagiarismRiskLevel)) {
      return t(`contests.plagiarismRisk.${value}`, undefined, value);
    }
    if (key === "statuses" && SUBMISSION_STATUSES.includes(value as SubmissionStatus)) {
      return t(`submissionStatus.${value}`, undefined, value);
    }
    if ((key === "leftSubmittedAt" || key === "rightSubmittedAt") && value) {
      return formatDateTime(value);
    }
  }
  return String(value);
}

function formatShortTraceId(value: string) {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

function scoreboardLabels(t: ReturnType<typeof useI18n>["t"]) {
  return {
    rank: t("contests.scoreboardRank"),
    participant: t("contests.scoreboardParticipant"),
    solved: t("contests.scoreboardSolved"),
    penalty: t("contests.scoreboardPenalty"),
    empty: t("contests.scoreboardEmpty"),
    pending: t("contests.scoreboardPending"),
    solvedStatus: t("contests.scoreboardSolvedStatus"),
    attempted: t("contests.scoreboardAttempted"),
    unsolved: t("contests.scoreboardUnsolved"),
    totalScore: t("contests.scoreboardTotalScore"),
    score: t("contests.scoreboardScore")
  };
}

function formatScoreSummary(score?: number | null, maxScore?: number | null) {
  if (score == null && maxScore == null) return "--";
  return `${formatScore(score)}/${formatScore(maxScore)}`;
}

function formatScore(value?: number | null) {
  if (value == null) return "0";
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}

function OperationJobNotice({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm leading-6 text-blue-800">
      {message}
    </div>
  );
}

async function invalidateContestQueries(queryClient: ReturnType<typeof useQueryClient>) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["admin-contests"] }),
    queryClient.invalidateQueries({ queryKey: ["contest-problems"] })
  ]);
}
