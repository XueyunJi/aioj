import * as React from "react";
import { Link, useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, CalendarClock, Eye, Megaphone, MessagesSquare, Send, Trophy, XCircle } from "lucide-react";
import { activeQueryRefetchInterval, ApiError, api, type ContestAnnouncementResponse, type ContestClarificationResponse, type ContestProblemResponse, type ContestRunProblemSnapshotResponse, type ContestRunResponse, type EntityId } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ConfirmDialog, EmptyState, ErrorPanel, LoadingPanel, PageSection } from "../components/Common";
import { MarkdownView } from "../components/MarkdownView";
import { SubmissionDetailDialog } from "../components/SubmissionDetailDialog";
import { hasLiveSubmissions, SubmissionStatusBadge } from "../components/SubmissionStatusBadge";
import { useI18n } from "../lib/i18n";
import { formatDateTime } from "../lib/format";
import { ContestScoreboardPanel } from "./ContestScoreboardView";
import { ContestStudentPostmortemPanel } from "./ContestStudentPostmortemView";

type ContestProblemLike = ContestProblemResponse | ContestRunProblemSnapshotResponse;
type ContestDetailTab = "overview" | "announcements" | "clarifications" | "scoreboard" | "postmortem" | "problems" | "submissions";
const contestDetailTabs: ContestDetailTab[] = ["overview", "announcements", "clarifications", "scoreboard", "postmortem", "problems", "submissions"];
const CLARIFICATION_PAGE_SIZE = 50;

export function ContestDetailView() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const params = useParams({ strict: false }) as { contestId: string };
  const search = useSearch({ strict: false }) as { runId?: EntityId; tab?: ContestDetailTab };
  const contestId = params.contestId;
  const runId = search.runId;
  const activeTab: ContestDetailTab = contestDetailTabs.includes(search.tab ?? "overview") ? (search.tab ?? "overview") : "overview";
  const [cancelOpen, setCancelOpen] = React.useState(false);
  const [actionError, setActionError] = React.useState<string | null>(null);
  const [communicationError, setCommunicationError] = React.useState<string | null>(null);
  const [clarificationQuestion, setClarificationQuestion] = React.useState("");
  const [clarificationProblemId, setClarificationProblemId] = React.useState<EntityId | "">("");
  const [selectedSubmissionId, setSelectedSubmissionId] = React.useState<EntityId | null>(null);
  const queryClient = useQueryClient();

  const contestQuery = useQuery({
    queryKey: ["student-contest", contestId],
    queryFn: () => api.contest(contestId),
    enabled: !runId
  });

  const openRunQuery = useQuery({
    queryKey: ["student-contest-open-run", contestId, runId],
    queryFn: () => api.openContestRun(contestId, runId!),
    enabled: Boolean(runId)
  });

  const contest = runId ? openRunQuery.data?.contest : contestQuery.data;
  const run = runId ? openRunQuery.data?.run : undefined;
  const canSubmit = !runId || Boolean(openRunQuery.data?.canSubmit);
  const canViewProblems = !runId || Boolean(openRunQuery.data?.canViewProblems);
  const canViewScoreboard = Boolean(runId && openRunQuery.data?.canViewScoreboard);

  const legacyProblemsQuery = useQuery({
    queryKey: ["student-contest-problems", contestId],
    queryFn: () => api.contestProblems(contestId),
    enabled: !runId
  });

  const runProblemsQuery = useQuery({
    queryKey: ["student-contest-run-problems", contestId, runId],
    queryFn: () => api.contestRunProblems(contestId, runId!),
    enabled: Boolean(runId) && canViewProblems && (activeTab === "problems" || activeTab === "clarifications")
  });

  const submissionsQuery = useQuery({
    queryKey: ["student-contest-submissions", contestId, runId],
    queryFn: () => api.mySubmissions({ page: 1, pageSize: 8, contestId, contestRunId: runId, scope: "CONTEST" }),
    enabled: Boolean(runId) && canViewProblems && activeTab === "submissions",
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => hasLiveSubmissions(data?.records))
  });

  const registerMutation = useMutation({
    mutationFn: () => api.registerContestRun(contestId, runId!),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["student-contest-open-run", contestId, runId] }),
        queryClient.invalidateQueries({ queryKey: ["student-open-contest-runs"] })
      ]);
    },
    onError: (caught) => {
      setActionError(caught instanceof ApiError ? caught.userMessage : t("contests.registrationFailed"));
    }
  });

  const cancelMutation = useMutation({
    mutationFn: () => api.cancelContestRunRegistration(contestId, runId!),
    onSuccess: async () => {
      setCancelOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["student-contest-open-run", contestId, runId] }),
        queryClient.invalidateQueries({ queryKey: ["student-open-contest-runs"] })
      ]);
    },
    onError: (caught) => {
      setActionError(caught instanceof ApiError ? caught.userMessage : t("contests.registrationCancelFailed"));
    }
  });

  const problems = (runId ? runProblemsQuery.data : legacyProblemsQuery.data) ?? [];
  const problemsLoading = runId ? runProblemsQuery.isLoading : legacyProblemsQuery.isLoading;
  const problemsError = runId ? runProblemsQuery.isError : legacyProblemsQuery.isError;
  const startAt = run?.startAt ?? contest?.startAt;
  const endAt = run?.endAt ?? contest?.endAt;
  const freezeAt = run?.freezeAt ?? contest?.freezeAt;
  const hasStarted = run ? Date.now() >= new Date(run.startAt).getTime() : true;
  const hasEnded = run ? Date.now() >= new Date(run.endAt).getTime() : false;
  const hasRegistrationWindow = Boolean(run?.registrationStartAt || run?.registrationEndAt);
  const cancellable = Boolean(runId && run && !hasStarted && openRunQuery.data?.registration
    && (openRunQuery.data.registration.status === "APPROVED" || openRunQuery.data.registration.status === "PENDING"));
  const canAskClarification = Boolean(runId && run && canSubmit && hasStarted && !hasEnded);
  const [clarificationPage, setClarificationPage] = React.useState(1);

  const announcementsQuery = useQuery({
    queryKey: ["student-contest-announcements", contestId, runId],
    queryFn: () => api.contestAnnouncements(contestId, runId!),
    enabled: Boolean(runId && run)
  });

  const clarificationsQuery = useQuery({
    queryKey: ["student-contest-clarifications", contestId, runId, clarificationPage],
    queryFn: () => api.contestClarifications(contestId, runId!, { page: clarificationPage, pageSize: CLARIFICATION_PAGE_SIZE }),
    enabled: Boolean(runId && run && hasStarted && canViewProblems && activeTab === "clarifications")
  });

  const clarificationsTotal = clarificationsQuery.data?.total ?? 0;
  const clarificationsMaxPage = Math.max(1, Math.ceil(clarificationsTotal / CLARIFICATION_PAGE_SIZE));

  const createClarificationMutation = useMutation({
    mutationFn: () => api.createContestClarification(contestId, runId!, {
      contestProblemId: clarificationProblemId || undefined,
      question: clarificationQuestion
    }),
    onSuccess: async () => {
      setClarificationQuestion("");
      setClarificationProblemId("");
      await queryClient.invalidateQueries({ queryKey: ["student-contest-clarifications", contestId, runId] });
    },
    onError: (caught) => {
      setCommunicationError(caught instanceof ApiError ? caught.userMessage : t("contests.clarificationSubmitFailed"));
    }
  });

  function updateTab(tab: ContestDetailTab) {
    void navigate({ to: "/contests/$contestId", params: { contestId }, search: { runId, tab } });
  }

  return (
    <div className="mx-auto flex max-w-[1200px] flex-col gap-6 px-4 py-5 md:px-8">
      <Button asChild variant="ghost" className="w-fit">
        <Link to="/contests">
          <ArrowLeft className="size-4" aria-hidden="true" />
          {t("contests.backToList")}
        </Link>
      </Button>

      {(!runId && contestQuery.isLoading) || (runId && openRunQuery.isLoading) ? (
        <LoadingPanel label={t("contests.loading")} />
      ) : (!runId && (contestQuery.isError || !contest)) || (runId && (openRunQuery.isError || !contest || !run)) ? (
        <ErrorPanel title={t("contests.loadFailed")} />
      ) : (
        <>
          <PageSection
            eyebrow={run ? t("contests.runDetail") : t("contests.detailTitle")}
            title={run?.title ?? contest!.title}
            actions={(
              <div className="flex flex-wrap gap-2">
                <Badge tone="blue"><Trophy className="size-4" aria-hidden="true" />{t(`contests.mode.${contest!.mode}`)}</Badge>
                {run ? <Badge tone="neutral">{t(`contests.runKind.${run.runKind}`)}</Badge> : null}
                <Badge tone="green">{run ? t(`contests.runStatus.${run.status}`) : t(`contests.status.${contest!.status}`)}</Badge>
              </div>
            )}
          />

          <section className="grid gap-4 md:grid-cols-3">
            <InfoTile label={t("contests.startAt")} value={startAt ? formatDateTime(startAt) : "--"} />
            <InfoTile label={t("contests.endAt")} value={endAt ? formatDateTime(endAt) : "--"} />
            <InfoTile label={t("contests.freezeAt")} value={freezeAt ? formatDateTime(freezeAt) : "--"} />
          </section>

          {actionError ? <ErrorPanel title={actionError} /> : null}
          {communicationError ? <ErrorPanel title={communicationError} /> : null}

          <ContestDetailTabs activeTab={activeTab} onChange={updateTab} />

          <div className="transition-[opacity,transform] duration-200 motion-reduce:transition-none">
            {activeTab === "overview" ? (
              <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
                <div className="flex flex-col gap-5 lg:flex-row lg:items-start">
                  <div className="min-w-0 flex-1">
                    <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.detailTabs.overview")}</h2>
                    <div className="mt-2 w-full text-sm leading-6 text-[var(--oj-ink-muted)]">
                      <MarkdownView content={contest!.description || t("contests.noDescription")} className="w-full max-w-none" />
                    </div>
                    {run ? (
                      <div className="mt-4 grid gap-3 text-sm md:grid-cols-3">
                        <InfoTile label={t("contests.registrationAccessLabel")} value={t(`contests.registrationAccess.${run.registrationAccess}`)} />
                        <InfoTile label={t("contests.registrationWindow")} value={`${run.registrationStartAt ? formatDateTime(run.registrationStartAt) : "--"} - ${run.registrationEndAt ? formatDateTime(run.registrationEndAt) : "--"}`} />
                        <InfoTile label={t("contests.maxParticipants")} value={run.maxParticipants ? t("contests.maxParticipantsValue", { count: run.maxParticipants }) : t("contests.noCapacityLimit")} />
                      </div>
                    ) : null}
                  </div>
                  {runId && run ? (
                    <div className="flex shrink-0 flex-wrap gap-2">
                      {openRunQuery.data?.canRegister ? (
                        <Button
                          disabled={registerMutation.isPending}
                          onClick={async () => {
                            setActionError(null);
                            try {
                              await registerMutation.mutateAsync();
                            } catch {
                              // The mutation onError handler already surfaces the user-facing message.
                            }
                          }}
                        >
                          {hasRegistrationWindow ? t("contests.register") : t("contests.joinRun")}
                        </Button>
                      ) : null}
                      {cancellable ? (
                        <Button variant="outline" disabled={cancelMutation.isPending} onClick={() => setCancelOpen(true)}>
                          <XCircle className="size-4" aria-hidden="true" />
                          {t("contests.cancelRegistration")}
                        </Button>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              </section>
            ) : null}

            {activeTab === "announcements" && runId && run ? (
              <ContestCommunicationSection
                mode="announcements"
                runId={runId}
                canAsk={canAskClarification}
                hasStarted={hasStarted}
                hasEnded={hasEnded}
                problems={problems}
                announcements={announcementsQuery.data ?? []}
                clarifications={clarificationsQuery.data?.records ?? []}
                clarificationsTotal={clarificationsTotal}
                clarificationPage={clarificationPage}
                clarificationsMaxPage={clarificationsMaxPage}
                onClarificationPageChange={setClarificationPage}
                announcementsLoading={announcementsQuery.isLoading}
                clarificationsLoading={clarificationsQuery.isLoading}
                announcementsError={announcementsQuery.isError}
                clarificationsError={clarificationsQuery.isError}
                question={clarificationQuestion}
                problemId={clarificationProblemId}
                submitting={createClarificationMutation.isPending}
                onQuestionChange={setClarificationQuestion}
                onProblemChange={setClarificationProblemId}
                onSubmit={async () => {
                  setCommunicationError(null);
                  try {
                    await createClarificationMutation.mutateAsync();
                  } catch {
                    // The mutation onError handler already surfaces the user-facing message.
                  }
                }}
              />
            ) : null}

            {activeTab === "clarifications" ? (
              runId && run && hasStarted && canViewProblems ? (
                <ContestCommunicationSection
                  mode="clarifications"
                  runId={runId}
                  canAsk={canAskClarification}
                  hasStarted={hasStarted}
                  hasEnded={hasEnded}
                  problems={problems}
                  announcements={announcementsQuery.data ?? []}
                  clarifications={clarificationsQuery.data?.records ?? []}
                  clarificationsTotal={clarificationsTotal}
                  clarificationPage={clarificationPage}
                  clarificationsMaxPage={clarificationsMaxPage}
                  onClarificationPageChange={setClarificationPage}
                  announcementsLoading={announcementsQuery.isLoading}
                  clarificationsLoading={clarificationsQuery.isLoading}
                  announcementsError={announcementsQuery.isError}
                  clarificationsError={clarificationsQuery.isError}
                  question={clarificationQuestion}
                  problemId={clarificationProblemId}
                  submitting={createClarificationMutation.isPending}
                  onQuestionChange={setClarificationQuestion}
                  onProblemChange={setClarificationProblemId}
                  onSubmit={async () => {
                    setCommunicationError(null);
                    try {
                      await createClarificationMutation.mutateAsync();
                    } catch {
                      // The mutation onError handler already surfaces the user-facing message.
                    }
                  }}
                />
              ) : (
                <LockedPanel />
              )
            ) : null}

            {activeTab === "scoreboard" ? (
              runId && canViewScoreboard ? <ContestScoreboardPanel contestId={contestId} runId={runId} embedded /> : <LockedPanel />
            ) : null}

            {activeTab === "postmortem" ? (
              runId && hasEnded && canViewProblems ? <ContestStudentPostmortemPanel contestId={contestId} runId={runId} embedded /> : <LockedPanel />
            ) : null}

            {activeTab === "problems" ? (
              canViewProblems ? (
                <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
                  <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.problemEditor")}</h2>
                      <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">
                        {runId
                          ? canSubmit ? t("contests.runProblemSnapshotCopy") : t("contests.postContestProblemSnapshotCopy")
                          : t("contests.studentDescription")}
                      </p>
                    </div>
                    <Badge tone="neutral">{t("contests.problemCount")}: {problems.length}</Badge>
                  </div>
                  {problemsLoading ? (
                    <LoadingPanel label={t("common.loading")} />
                  ) : problemsError ? (
                    <ErrorPanel title={t("contests.loadFailed")} />
                  ) : problems.length ? (
                    <div className="grid gap-3">
                      {problems.map((problem) => (
                        <ProblemLink key={problem.id} contestId={contestId} runId={runId} problem={problem} canOpen={canSubmit} />
                      ))}
                    </div>
                  ) : (
                    <EmptyState title={t("contests.noProblemsTitle")} description={t("contests.noProblemsDescription")} />
                  )}
                </section>
              ) : (
                <LockedPanel />
              )
            ) : null}

            {activeTab === "submissions" ? (
              canViewProblems ? (
                <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
                  <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.myContestSubmissions")}</h2>
                      <p className="mt-1 text-sm text-pretty text-[var(--oj-ink-muted)]">{t("contests.myContestSubmissionsCopy")}</p>
                    </div>
                    <Badge tone="neutral">{submissionsQuery.data?.total ?? 0}</Badge>
                  </div>
                  {submissionsQuery.isLoading ? (
                    <LoadingPanel label={t("submissions.loading")} />
                  ) : submissionsQuery.isError ? (
                    <ErrorPanel title={t("problems.submissionsLoadFailed")} />
                  ) : submissionsQuery.data?.records.length ? (
                    <div className="grid gap-3">
                      {submissionsQuery.data.records.map((submission) => (
                        <button
                          key={submission.id}
                          type="button"
                          className="flex w-full flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 text-left outline-none transition-colors hover:border-[var(--oj-border)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] sm:flex-row sm:items-center sm:justify-between"
                          onClick={() => setSelectedSubmissionId(submission.id)}
                        >
                          <div className="flex flex-wrap items-center gap-2">
                            <SubmissionStatusBadge status={submission.status} label={t(`submissionStatus.${submission.status}`)} />
                            <span className="text-sm font-medium text-[var(--oj-ink)]">{submission.language}</span>
                            {submission.score != null || submission.maxScore != null ? (
                              <span className="text-xs font-medium tabular-nums text-[var(--oj-ink-muted)]">
                                {t("contests.score")}: {formatScoreSummary(submission.score, submission.maxScore)}
                              </span>
                            ) : null}
                            <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">#{submission.problemId}</span>
                          </div>
                          <span className="inline-flex items-center gap-1.5 text-xs tabular-nums text-[var(--oj-ink-muted)]">
                            {formatDateTime(submission.createdAt)}
                            <Eye className="size-3.5" aria-hidden="true" />
                          </span>
                        </button>
                      ))}
                    </div>
                  ) : (
                    <EmptyState title={t("contests.noContestSubmissionsTitle")} description={t("contests.noContestSubmissionsDescription")} />
                  )}
                </section>
              ) : (
                <LockedPanel />
              )
            ) : null}
          </div>

          <ConfirmDialog
            open={cancelOpen}
            onOpenChange={setCancelOpen}
            title={t("contests.cancelRegistration")}
            description={t("contests.cancelRegistrationConfirm")}
            cancelLabel={t("common.cancel")}
            confirmLabel={t("contests.cancelRegistration")}
            onConfirm={async () => {
              setActionError(null);
              await cancelMutation.mutateAsync();
            }}
          />
          <SubmissionDetailDialog
            submissionId={selectedSubmissionId}
            onOpenChange={(open) => {
              if (!open) setSelectedSubmissionId(null);
            }}
          />
        </>
      )}
    </div>
  );
}

function ContestDetailTabs({ activeTab, onChange }: { activeTab: ContestDetailTab; onChange: (tab: ContestDetailTab) => void }) {
  const { t } = useI18n();
  return (
    <div className="overflow-x-auto rounded-2xl border border-[var(--oj-border)] bg-white p-1">
      <div className="flex min-w-max gap-1">
        {contestDetailTabs.map((tab) => (
          <button
            key={tab}
            type="button"
            className={cn(
              "h-10 whitespace-nowrap rounded-xl px-4 text-sm font-medium outline-none transition-[background-color,color,box-shadow] duration-200 focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] motion-reduce:transition-none",
              activeTab === tab
                ? "bg-[var(--oj-primary)] text-white shadow-sm"
                : "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)]"
            )}
            onClick={() => onChange(tab)}
          >
            {t(`contests.detailTabs.${tab}`)}
          </button>
        ))}
      </div>
    </div>
  );
}

function LockedPanel() {
  const { t } = useI18n();
  return (
    <EmptyState title={t("contests.lockedTabTitle")} description={t("contests.lockedTabDescription")} />
  );
}

function ContestCommunicationSection({
  mode,
  canAsk,
  hasStarted,
  hasEnded,
  problems,
  announcements,
  clarifications,
  clarificationsTotal,
  clarificationPage,
  clarificationsMaxPage,
  onClarificationPageChange,
  announcementsLoading,
  clarificationsLoading,
  announcementsError,
  clarificationsError,
  question,
  problemId,
  submitting,
  onQuestionChange,
  onProblemChange,
  onSubmit
}: {
  mode?: "announcements" | "clarifications";
  runId: EntityId;
  canAsk: boolean;
  hasStarted: boolean;
  hasEnded: boolean;
  problems: ContestProblemLike[];
  announcements: ContestAnnouncementResponse[];
  clarifications: ContestClarificationResponse[];
  clarificationsTotal: number;
  clarificationPage: number;
  clarificationsMaxPage: number;
  onClarificationPageChange: (page: number) => void;
  announcementsLoading: boolean;
  clarificationsLoading: boolean;
  announcementsError: boolean;
  clarificationsError: boolean;
  question: string;
  problemId: EntityId | "";
  submitting: boolean;
  onQuestionChange: (value: string) => void;
  onProblemChange: (value: EntityId | "") => void;
  onSubmit: () => Promise<void>;
}) {
  const { t } = useI18n();
  const visibleClarifications = clarifications.filter((item) => item.status !== "CLOSED" || item.answer);

  return (
    <section className={mode ? "grid gap-4" : "grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(340px,0.85fr)]"}>
      {mode !== "clarifications" ? <div className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <Megaphone className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentAnnouncements")}</h2>
            </div>
            <p className="mt-1 text-sm leading-6 text-pretty text-[var(--oj-ink-muted)]">{t("contests.announcementsCopy")}</p>
          </div>
          <Badge tone="neutral">{announcements.length}</Badge>
        </div>
        {announcementsLoading ? (
          <LoadingPanel label={t("common.loading")} />
        ) : announcementsError ? (
          <ErrorPanel title={t("contests.loadFailed")} />
        ) : announcements.length ? (
          <div className="grid gap-3">
            {announcements.map((announcement) => (
              <article key={announcement.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="min-w-0 flex-1 truncate text-sm font-semibold text-[var(--oj-ink)]" title={announcement.title}>{announcement.title}</h3>
                  {announcement.pinned ? <Badge tone="blue">{t("contests.announcementPinned")}</Badge> : null}
                  <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(announcement.publishedAt)}</span>
                </div>
                <div className="mt-3 rounded-lg bg-white px-3 py-2">
                  <MarkdownView content={announcement.content} />
                </div>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState title={t("contests.noAnnouncementsTitle")} description={t("contests.noAnnouncementsDescription")} />
        )}
      </div> : null}

      {mode !== "announcements" ? <div className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <MessagesSquare className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("contests.studentClarifications")}</h2>
            </div>
            <p className="mt-1 text-sm leading-6 text-pretty text-[var(--oj-ink-muted)]">
              {hasEnded ? t("contests.studentClarificationReadOnly") : t("contests.clarificationsCopy")}
            </p>
          </div>
          <Badge tone="neutral">{visibleClarifications.length}</Badge>
        </div>

        {hasStarted ? (
          <div className="space-y-4">
            {canAsk ? (
              <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                <div className="grid gap-3">
                  {problems.length ? (
                    <label className="block">
                      <span className="mb-1 block text-sm font-medium text-[var(--oj-ink)]">{t("contests.problemEditor")}</span>
                      <select
                        className="h-10 w-full rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)] outline-none focus:ring-2 focus:ring-[var(--oj-focus)]"
                        value={problemId}
                        onChange={(event) => onProblemChange(event.target.value)}
                      >
                        <option value="">{t("contests.allProblems")}</option>
                        {problems.map((problem) => {
                          const contestProblemId = "contestProblemId" in problem ? problem.contestProblemId : problem.id;
                          return (
                            <option key={contestProblemId} value={contestProblemId}>
                              {problem.label} · {problem.displayTitle || `#${problem.problemId}`}
                            </option>
                          );
                        })}
                      </select>
                    </label>
                  ) : null}
                  <label className="block">
                    <span className="mb-1 block text-sm font-medium text-[var(--oj-ink)]">{t("contests.clarificationQuestion")}</span>
                    <textarea
                      className="min-h-28 w-full resize-y rounded-lg border border-[var(--oj-border)] bg-white px-3 py-2 text-sm leading-6 text-[var(--oj-ink)] outline-none focus:ring-2 focus:ring-[var(--oj-focus)]"
                      value={question}
                      placeholder={t("contests.clarificationQuestionPlaceholder")}
                      onChange={(event) => onQuestionChange(event.target.value)}
                    />
                  </label>
                  <Button className="w-fit" disabled={submitting || !question.trim()} onClick={() => void onSubmit()}>
                    <Send className="size-4" aria-hidden="true" />
                    {t("contests.askClarification")}
                  </Button>
                </div>
              </div>
            ) : null}

            {clarificationsLoading ? (
              <LoadingPanel label={t("common.loading")} />
            ) : clarificationsError ? (
              <ErrorPanel title={t("contests.loadFailed")} />
            ) : visibleClarifications.length ? (
              <div className="grid gap-3">
                {visibleClarifications.map((item) => (
                  <StudentClarificationCard key={item.id} item={item} />
                ))}
              </div>
            ) : (
              <EmptyState title={t("contests.noClarificationsTitle")} description={t("contests.noClarificationsDescription")} />
            )}
            {clarificationsTotal > 0 ? (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[var(--oj-border-soft)] bg-white px-4 py-3 text-sm text-[var(--oj-ink-muted)]">
                <span className="tabular-nums">{clarificationsTotal}</span>
                <div className="flex items-center gap-2">
                  <Button variant="outline" size="sm" disabled={clarificationPage <= 1} onClick={() => onClarificationPageChange(Math.max(1, clarificationPage - 1))}>{t("common.previous")}</Button>
                  <span className="tabular-nums">{clarificationPage}/{clarificationsMaxPage}</span>
                  <Button variant="outline" size="sm" disabled={clarificationPage >= clarificationsMaxPage} onClick={() => onClarificationPageChange(Math.min(clarificationsMaxPage, clarificationPage + 1))}>{t("common.next")}</Button>
                </div>
              </div>
            ) : null}
          </div>
        ) : (
          <EmptyState title={t("contests.studentClarifications")} description={t("contests.runLockedBeforeStart")} />
        )}
      </div> : null}
    </section>
  );
}

function StudentClarificationCard({ item }: { item: ContestClarificationResponse }) {
  const { t } = useI18n();
  return (
    <article className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={item.status === "OPEN" ? "amber" : item.status === "ANSWERED" ? "green" : "neutral"}>
          {t(`contests.clarificationStatusLabels.${item.status}`)}
        </Badge>
        {item.answerVisibility ? (
          <Badge tone={item.answerVisibility === "PUBLIC" ? "blue" : "neutral"}>
            {t(`contests.clarificationVisibilityLabels.${item.answerVisibility}`)}
          </Badge>
        ) : null}
        <span className="ml-auto text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(item.createdAt)}</span>
      </div>
      <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink)]">{item.question}</p>
      {item.publicAnswer && !item.mine ? (
        <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{t("contests.publicClarificationIdentityHidden")}</p>
      ) : null}
      {item.answer ? (
        <div className="mt-3 rounded-lg bg-white px-3 py-2">
          <div className="mb-2 text-xs font-medium text-[var(--oj-ink-muted)]">{t("contests.clarificationAnswer")}</div>
          <MarkdownView content={item.answer} />
        </div>
      ) : null}
    </article>
  );
}

function ProblemLink({ contestId, runId, problem, canOpen }: { contestId: EntityId; runId?: EntityId; problem: ContestProblemLike; canOpen: boolean }) {
  const { t } = useI18n();
  const contestProblemId = "contestProblemId" in problem ? problem.contestProblemId : problem.id;
  const content = (
    <>
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="blue">{problem.label}</Badge>
          <h3 className="truncate text-sm font-semibold text-[var(--oj-ink)]">{problem.displayTitle || `#${problem.problemId}`}</h3>
        </div>
        <p className="mt-2 text-xs tabular-nums text-[var(--oj-ink-muted)]">#{problem.problemId}</p>
      </div>
      <div className="flex items-center gap-3">
        <span className="text-sm font-medium text-[var(--oj-primary)]">
          {canOpen ? t("contests.openProblem") : t("contests.postContestReviewOnly")}
        </span>
      </div>
    </>
  );
  const className = "flex flex-col gap-3 rounded-xl border border-[var(--oj-border-soft)] p-4 outline-none transition-colors sm:flex-row sm:items-center sm:justify-between";
  if (!canOpen) {
    return (
      <article className={`${className} bg-[var(--oj-surface-muted)]`}>
        {content}
      </article>
    );
  }
  if (runId) {
    return (
      <Link
        to="/contests/$contestId/runs/$contestRunId/problems/$contestProblemId"
        params={{ contestId, contestRunId: runId, contestProblemId }}
        className={`${className} hover:border-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]`}
      >
        {content}
      </Link>
    );
  }
  return (
    <Link
      to="/problems/$problemId"
      params={{ problemId: problem.problemId }}
      search={{ contestId, contestRunId: runId, contestProblemId }}
      className={`${className} hover:border-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]`}
    >
      {content}
    </Link>
  );
}

function formatScoreSummary(score?: number | null, maxScore?: number | null) {
  if (score == null && maxScore == null) return "--";
  return `${formatScore(score)}/${formatScore(maxScore)}`;
}

function formatScore(value?: number | null) {
  if (value == null) return "0";
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}

function InfoTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
      <div className="flex items-center gap-2 text-xs font-medium text-[var(--oj-ink-muted)]">
        <CalendarClock className="size-4" aria-hidden="true" />
        {label}
      </div>
      <div className="mt-2 text-sm font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}
