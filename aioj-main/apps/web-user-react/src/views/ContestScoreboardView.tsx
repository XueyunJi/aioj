import * as React from "react";
import { Link, useParams, useSearch } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Brain, CalendarClock, ChevronLeft, ChevronRight, Clock3, Pause, Play, RadioTower, Trophy } from "lucide-react";
import { api, steadyQueryRefetchInterval, type ContestResolverSessionDetailResponse, type ContestScoreboardTimelineTickResponse, type EntityId } from "@aioj/api-client";
import { Badge, Button, ContestScoreboardTable } from "@aioj/ui-react";
import { ErrorPanel, LoadingPanel, PageSection } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { formatDateTime } from "../lib/format";

export function ContestScoreboardView() {
  const params = useParams({ strict: false }) as { contestId: string };
  const search = useSearch({ strict: false }) as { runId?: EntityId };
  const contestId = params.contestId;
  const runId = search.runId;

  return <ContestScoreboardPanel contestId={contestId} runId={runId} />;
}

export function ContestScoreboardPanel({
  contestId,
  runId,
  embedded = false
}: {
  contestId: EntityId;
  runId?: EntityId | null;
  embedded?: boolean;
}) {
  const { t } = useI18n();
  const [timelineIndex, setTimelineIndex] = React.useState<number | null>(null);
  const [resolverSessionId, setResolverSessionId] = React.useState<EntityId>("");

  React.useEffect(() => {
    setTimelineIndex(null);
    setResolverSessionId("");
  }, [runId]);

  const openRunQuery = useQuery({
    queryKey: ["student-contest-scoreboard-open-run", contestId, runId],
    queryFn: () => api.openContestRun(contestId, runId!),
    enabled: Boolean(runId),
    refetchInterval: (query) => {
      const run = query.state.data?.run;
      if (!run?.freezeAt || run.publicScoreboardUnfrozenAt) return false;
      return Date.now() >= new Date(run.endAt).getTime() ? 15000 : false;
    }
  });

  const canViewScoreboard = Boolean(openRunQuery.data?.canViewScoreboard);
  const run = openRunQuery.data?.run;
  const contest = openRunQuery.data?.contest;
  const hasScoreboardFreeze = Boolean(run?.freezeAt);
  const publicBoardUnfrozen = Boolean(run?.publicScoreboardUnfrozenAt);
  const timelineQuery = useQuery({
    queryKey: ["student-contest-scoreboard-page-timeline", contestId, runId],
    queryFn: () => api.contestScoreboardTimeline(contestId, runId!, { view: "PUBLIC" }),
    enabled: Boolean(runId && canViewScoreboard),
    refetchInterval: (query) => {
      if (query.state.data?.status === "READY" || query.state.data?.status === "FAILED") return false;
      return steadyQueryRefetchInterval(query, true, 3000, 5000);
    }
  });

  const timelineTicks = timelineQuery.data?.ticks ?? [];
  const selectedTick = timelineIndex == null ? null : timelineTicks[timelineIndex] ?? null;
  const scoreboardQuery = useQuery({
    queryKey: ["student-contest-scoreboard-page", contestId, runId, selectedTick?.snapshotId ?? "live", run?.publicScoreboardUnfrozenAt ?? "frozen"],
    queryFn: () => api.contestScoreboard(contestId, {
      runId: runId ?? "",
      view: "PUBLIC",
      snapshotId: selectedTick?.snapshotId ?? ""
    }),
    enabled: Boolean(runId && canViewScoreboard),
    refetchInterval: (query) => {
      if (timelineIndex !== null) return false;
      if (query.state.data?.frozen && !publicBoardUnfrozen) return false;
      const endAt = openRunQuery.data?.run.endAt;
      return steadyQueryRefetchInterval(query, !(endAt && Date.now() >= new Date(endAt).getTime()), 5000, 15000);
    },
    staleTime: selectedTick ? 30 * 60_000 : 0,
    gcTime: selectedTick ? 60 * 60_000 : 5 * 60_000
  });

  const resolverSessionsQuery = useQuery({
    queryKey: ["student-contest-resolver-sessions", contestId, runId],
    queryFn: () => api.contestResolverSessions(contestId, runId!),
    enabled: Boolean(runId && canViewScoreboard)
  });

  React.useEffect(() => {
    if (!resolverSessionId && resolverSessionsQuery.data?.length) {
      setResolverSessionId(resolverSessionsQuery.data[0].id);
    }
  }, [resolverSessionId, resolverSessionsQuery.data]);

  const resolverDetailQuery = useQuery({
    queryKey: ["student-contest-resolver-session", contestId, runId, resolverSessionId],
    queryFn: () => api.contestResolverSession(contestId, runId!, resolverSessionId),
    enabled: Boolean(runId && resolverSessionId)
  });

  if (!runId) {
    if (embedded) {
      return <ErrorPanel title={t("contests.scoreboardRunRequired")} />;
    }
    return (
      <div className="mx-auto flex max-w-[1200px] flex-col gap-6 px-4 py-5 md:px-8">
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests">
            <ArrowLeft className="size-4" aria-hidden="true" />
            {t("contests.backToList")}
          </Link>
        </Button>
        <ErrorPanel title={t("contests.scoreboardRunRequired")} />
      </div>
    );
  }

  const content = (
    <>
      {!embedded ? <div className="flex flex-wrap items-center gap-2">
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests/$contestId" params={{ contestId }} search={{ runId }}>
            <ArrowLeft className="size-4" aria-hidden="true" />
            {t("contests.backToContest")}
          </Link>
        </Button>
        <Button asChild variant="ghost" className="w-fit">
          <Link to="/contests">
            {t("contests.backToList")}
          </Link>
        </Button>
      </div> : null}

      {openRunQuery.isLoading ? (
        <LoadingPanel label={t("contests.loading")} />
      ) : openRunQuery.isError || !contest || !run ? (
        <ErrorPanel title={t("contests.loadFailed")} />
      ) : !canViewScoreboard ? (
        <ErrorPanel title={t("contests.scoreboardUnavailable")} />
      ) : (
        <>
          {!embedded ? (
            <>
              <PageSection
                eyebrow={t("contests.scoreboardPageTitle")}
                title={run.title || contest.title}
                description={t("contests.scoreboardPageCopy")}
                actions={(
                  <div className="flex flex-wrap gap-2">
                    <Badge tone="blue"><Trophy className="size-4" aria-hidden="true" />{t(`contests.mode.${contest.mode}`)}</Badge>
                    <Badge tone="neutral">{t(`contests.runKind.${run.runKind}`)}</Badge>
                    {scoreboardQuery.data ? (
                      <Badge tone={scoreboardQuery.data.frozen ? "amber" : "green"}>
                        {scoreboardQuery.data.frozen
                          ? t("contests.publicFrozen")
                          : publicBoardUnfrozen
                            ? t("contests.publicUnfrozen")
                            : t("contests.liveScoreboard")}
                      </Badge>
                    ) : null}
                    {Date.now() >= new Date(run.endAt).getTime() ? (
                      <Button asChild size="sm">
                        <Link to="/contests/$contestId/postmortem" params={{ contestId }} search={{ runId }}>
                          <Brain className="size-4" aria-hidden="true" />
                          {t("contests.studentPostmortem")}
                        </Link>
                      </Button>
                    ) : null}
                  </div>
                )}
              />

              <section className="grid gap-4 md:grid-cols-3">
                <InfoTile label={t("contests.startAt")} value={formatDateTime(run.startAt)} />
                <InfoTile label={t("contests.endAt")} value={formatDateTime(run.endAt)} />
                <InfoTile label={t("contests.freezeAt")} value={run.freezeAt ? formatDateTime(run.freezeAt) : "--"} />
              </section>
            </>
          ) : null}

          {timelineQuery.data && timelineQuery.data.status !== "READY" ? (
            <ErrorPanel
              title={t(timelineQuery.data.status === "FAILED" ? "contests.scoreboardTimelineFailed" : "contests.scoreboardTimelineGenerating")}
              description={timelineQuery.data.message || t("contests.scoreboardTimelineGeneratingCopy")}
            />
          ) : null}

          {timelineTicks.length ? (
            <TimelinePicker
              ticks={timelineTicks}
              currentIndex={timelineIndex}
              onChange={setTimelineIndex}
            />
          ) : null}

          <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold text-[var(--oj-ink)]">
                  {timelineIndex == null ? t("contests.finalScoreboard") : t("contests.scoreboardTimeline")}
                </h2>
                <p className="mt-1 text-sm text-pretty text-[var(--oj-ink-muted)]">{t("contests.studentScoreboardCopy")}</p>
              </div>
              {selectedTick ? (
                <Badge tone="neutral">{t("contests.selectedTimelineMinute", { time: formatContestClock(selectedTick.bucketMillis) })}</Badge>
              ) : null}
            </div>
            {scoreboardQuery.isLoading ? (
              <LoadingPanel label={t("common.loading")} />
            ) : scoreboardQuery.isError || !scoreboardQuery.data ? (
              <ErrorPanel title={t("contests.scoreboardLoadFailed")} />
            ) : (
              <div className="space-y-3">
                {hasScoreboardFreeze && publicBoardUnfrozen ? (
                  <ErrorPanel title={t("contests.unfrozenNotice")} tone="success" />
                ) : hasScoreboardFreeze && scoreboardQuery.data.frozen ? (
                  <ErrorPanel title={t("contests.frozenNotice")} />
                ) : null}
                <ContestScoreboardTable
                  mode={scoreboardQuery.data.mode}
                  problems={scoreboardQuery.data.problems}
                  rows={scoreboardQuery.data.rows}
                  labels={scoreboardLabels(t)}
                />
              </div>
            )}
          </section>

          {resolverSessionsQuery.data?.length ? (
            <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
              <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="flex items-center gap-2 text-base font-semibold text-[var(--oj-ink)]">
                    <RadioTower className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
                    {t("contests.resolverReplay")}
                  </div>
                  <p className="mt-1 text-sm text-pretty text-[var(--oj-ink-muted)]">{t("contests.studentResolverReplayCopy")}</p>
                </div>
                <select className="min-w-56 rounded-xl border border-[var(--oj-border)] bg-white px-3 py-2 text-sm" value={resolverSessionId} onChange={(event) => setResolverSessionId(event.target.value)}>
                  {resolverSessionsQuery.data.map((session) => (
                    <option key={session.id} value={session.id}>{session.title}</option>
                  ))}
                </select>
              </div>
              {resolverDetailQuery.isLoading ? (
                <LoadingPanel label={t("common.loading")} />
              ) : resolverDetailQuery.isError || !resolverDetailQuery.data ? (
                <ErrorPanel title={t("contests.resolverLoadFailed")} />
              ) : (
                <StudentResolverReplay detail={resolverDetailQuery.data} />
              )}
            </section>
          ) : null}
        </>
      )}
    </>
  );

  if (embedded) {
    return <div className="space-y-5">{content}</div>;
  }

  return (
    <div className="mx-auto flex max-w-[1400px] flex-col gap-6 px-4 py-5 md:px-8">
      {content}
    </div>
  );
}

function StudentResolverReplay({ detail }: { detail: ContestResolverSessionDetailResponse }) {
  const { t } = useI18n();
  const [index, setIndex] = React.useState(0);
  const [playing, setPlaying] = React.useState(false);

  React.useEffect(() => {
    setIndex(0);
    setPlaying(false);
  }, [detail.session.id]);

  React.useEffect(() => {
    if (!playing) return undefined;
    const timer = window.setInterval(() => {
      setIndex((current) => {
        if (current >= detail.steps.length - 1) {
          setPlaying(false);
          return current;
        }
        return current + 1;
      });
    }, 1200);
    return () => window.clearInterval(timer);
  }, [detail.steps.length, playing]);

  const steps = detail.steps;
  const currentStep = steps[index];
  if (!currentStep) {
    return <ErrorPanel title={t("contests.resolverNoSteps")} />;
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
        <div className="flex flex-wrap gap-2">
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

function TimelinePicker({
  ticks,
  currentIndex,
  onChange
}: {
  ticks: ContestScoreboardTimelineTickResponse[];
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
    <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold text-[var(--oj-ink)]">
            <Clock3 className="size-4 text-[var(--oj-primary)]" aria-hidden="true" />
            {t("contests.scoreboardTimeline")}
          </div>
          <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
            {selected
              ? t("contests.selectedTimelineMinute", { time: formatContestClock(selected.bucketMillis) })
              : draftDirty && preview
                ? t("contests.selectedTimelineMinute", { time: formatContestClock(preview.bucketMillis) })
                : t("contests.latestTimeline")}
          </p>
        </div>
        <Button size="sm" variant="outline" type="button" disabled={currentIndex === null} onClick={() => onChange(null)}>
          {t("contests.latestTimeline")}
        </Button>
      </div>
      <input
        className="mt-4 w-full accent-[var(--oj-primary)]"
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
      <div className="mt-2 flex justify-between text-xs tabular-nums text-[var(--oj-ink-muted)]">
        <span>{formatContestClock(ticks[0]?.bucketMillis ?? 0)}</span>
        <span>{formatContestClock(ticks[ticks.length - 1]?.bucketMillis ?? 0)}</span>
      </div>
    </section>
  );
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

function formatContestClock(value?: number | null) {
  if (value == null) return "--";
  const totalSeconds = Math.max(0, Math.floor(value / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  return `${hours}:${String(minutes).padStart(2, "0")}`;
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
