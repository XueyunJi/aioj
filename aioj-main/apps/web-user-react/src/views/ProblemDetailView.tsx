import * as React from "react";
import { Link, useParams, useSearch } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ClipboardCopy, Eye, GripVertical, Send } from "lucide-react";
import { activeQueryRefetchInterval, api, ApiError, type EntityId, type ProblemLanguageTimeLimitMultipliers } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { AiAssistantDialog } from "../components/AiAssistantDialog";
import { CodeEditor } from "../components/CodeEditor";
import { ConfirmDialog, EmptyState, ErrorPanel, LoadingPanel, PageSection } from "../components/Common";
import { LanguageEffectiveLimitPanel } from "../components/LanguageEffectiveLimitPanel";
import { MarkdownView } from "../components/MarkdownView";
import { SubmissionDetailDialog } from "../components/SubmissionDetailDialog";
import { hasLiveSubmissions, SubmissionStatusBadge } from "../components/SubmissionStatusBadge";
import { useI18n } from "../lib/i18n";
import { useAuth } from "../lib/auth";
import { contestDraftKey, practiceDraftKey, readCodeDraft, writeCodeDraft } from "../lib/codeDrafts";
import { copyToClipboard } from "../lib/clipboard";
import { difficultyTone, formatDateTime, formatMemory, languageTemplates } from "../lib/format";
import { readableCaughtError, readableJudgeMessage } from "../lib/readableError";
import { passWheelToPageAtScrollBoundary } from "../lib/wheelScroll";

const languageOptions = [
  { value: "cpp", label: "C++17" },
  { value: "python", label: "Python 3" },
  { value: "java", label: "Java 17" }
];
const TIME_LIMIT_LANGUAGES = ["cpp", "python", "java"] as const;

export function ProblemDetailView() {
  const { t, locale } = useI18n();
  const { profile } = useAuth();
  const userId = profile?.userId ?? null;
  const { problemId } = useParams({ strict: false }) as { problemId: EntityId };
  const search = useSearch({ strict: false }) as { contestId?: EntityId; contestRunId?: EntityId; contestProblemId?: EntityId };
  const contestContext = search.contestId && search.contestProblemId
    ? { contestId: search.contestId, contestRunId: search.contestRunId, contestProblemId: search.contestProblemId }
    : null;
  const queryClient = useQueryClient();
  const [tab, setTab] = React.useState<"statement" | "notes" | "submissions">("statement");
  const [language, setLanguage] = React.useState("cpp");
  const draftKey = contestContext?.contestRunId && contestContext?.contestProblemId
    ? contestDraftKey(userId, contestContext.contestRunId, contestContext.contestProblemId, language)
    : practiceDraftKey(userId, problemId, language);
  const [code, setCode] = React.useState(() => readCodeDraft(draftKey) || languageTemplates.cpp);
  const [submitMessage, setSubmitMessage] = React.useState<string | null>(null);
  const [submittedId, setSubmittedId] = React.useState<EntityId | null>(null);
  const [selectedSubmissionId, setSelectedSubmissionId] = React.useState<EntityId | null>(null);
  const [resetCodeConfirmOpen, setResetCodeConfirmOpen] = React.useState(false);
  const [paneRatio, setPaneRatio] = React.useState(54);
  const splitRef = React.useRef<HTMLElement | null>(null);

  const problemQuery = useQuery({
    queryKey: ["problem", problemId, contestContext?.contestRunId ?? null, contestContext?.contestProblemId ?? null],
    queryFn: () => api.problem(problemId, {
      contestRunId: contestContext?.contestRunId,
      contestProblemId: contestContext?.contestProblemId
    })
  });

  const submissionsQuery = useQuery({
    queryKey: ["submissions", "problem", problemId, "PRACTICE"],
    queryFn: () => api.mySubmissions({
      page: 1,
      pageSize: 8,
      problemId,
      scope: "PRACTICE"
    }),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => hasLiveSubmissions(data?.records))
  });

  React.useEffect(() => {
    setCode(readCodeDraft(draftKey) || languageTemplates[language] || "");
  }, [draftKey, language]);

  React.useEffect(() => {
    writeCodeDraft(draftKey, code);
  }, [code, draftKey]);

  React.useEffect(() => {
    if (!submittedId) return;
    const submitted = submissionsQuery.data?.records.find((submission) => submission.id === submittedId);
    if (!submitted) return;
    setSubmitMessage(t("problems.submittedStatus", { id: submitted.id, status: t(`submissionStatus.${submitted.status}`) }));
  }, [submittedId, submissionsQuery.data?.records, t]);

  const updatePaneRatio = React.useCallback((clientX: number) => {
    const rect = splitRef.current?.getBoundingClientRect();
    if (!rect) return;
    setPaneRatio(clampPaneRatio(((clientX - rect.left) / rect.width) * 100));
  }, []);

  const beginPaneResize = React.useCallback((event: React.PointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    updatePaneRatio(event.clientX);

    const handleMove = (moveEvent: PointerEvent) => updatePaneRatio(moveEvent.clientX);
    const handleUp = () => {
      window.removeEventListener("pointermove", handleMove);
      window.removeEventListener("pointerup", handleUp);
    };

    window.addEventListener("pointermove", handleMove);
    window.addEventListener("pointerup", handleUp, { once: true });
  }, [updatePaneRatio]);

  const submitMutation = useMutation({
    mutationFn: () => api.submit({
      problemId,
      language,
      code,
      contestId: contestContext?.contestId,
      contestRunId: contestContext?.contestRunId,
      contestProblemId: contestContext?.contestProblemId
    }),
    onSuccess: async (submission) => {
      setSubmittedId(submission.id);
      setSubmitMessage(t("problems.submittedStatus", { id: submission.id, status: t(`submissionStatus.${submission.status}`) }));
      await queryClient.invalidateQueries({ queryKey: ["submissions"] });
    },
    onError: (err) => {
      setSubmitMessage(err instanceof ApiError ? err.userMessage : t("problems.submitFailed"));
    }
  });

  if (problemQuery.isLoading) {
    return <div className="mx-auto max-w-[1500px] px-4 py-5 md:px-8"><LoadingPanel label={t("problems.loading")} /></div>;
  }

  if (problemQuery.isError || !problemQuery.data) {
    const message = readableCaughtError(problemQuery.error, locale, t("problems.loadFailed"));
    return (
      <div className="mx-auto max-w-[1500px] px-4 py-5 md:px-8">
        <ErrorPanel title={message} action={<Button asChild variant="outline"><Link to="/problems">{t("problems.backToList")}</Link></Button>} />
      </div>
    );
  }

  const problem = problemQuery.data;

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection
        eyebrow={t("problems.problemEyebrow", { id: problem.id })}
        title={problem.title}
        description={t("problems.notesCopy")}
        actions={(
          <>
            <Button asChild variant="outline">
              <Link to="/problems"><ArrowLeft className="size-4" aria-hidden="true" />{t("problems.backToList")}</Link>
            </Button>
            <AiAssistantDialog problem={problem} code={code} language={language} contestContext={contestContext} />
          </>
        )}
      />

      {contestContext ? (
        <section className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[var(--oj-border)] bg-white px-4 py-3">
          <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
            <Badge tone="blue">{t("contests.contestSubmissionMode")}</Badge>
            <span className="text-pretty text-[var(--oj-ink-muted)]">{t("contests.contestSubmissionModeCopy")}</span>
          </div>
          <Button asChild variant="outline" size="sm">
            <Link
              to="/contests/$contestId"
              params={{ contestId: contestContext.contestId }}
              search={{ runId: contestContext.contestRunId }}
            >
              {t("contests.backToContest")}
            </Link>
          </Button>
        </section>
      ) : null}

      <section
        ref={splitRef}
        style={{
          "--problem-left": `${paneRatio}fr`,
          "--problem-right": `${100 - paneRatio}fr`
        } as React.CSSProperties}
        className="grid min-h-0 gap-5 xl:h-[clamp(806px,calc(100dvh-7rem),1040px)] xl:grid-cols-[minmax(360px,var(--problem-left))_14px_minmax(420px,var(--problem-right))] xl:gap-0"
      >
        <div className="flex min-h-0 flex-col overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white">
          <div className="flex flex-wrap items-center gap-2 border-b border-[var(--oj-border-soft)] px-5 py-4">
            <Badge tone={difficultyTone(problem.difficulty)}>{t(`difficulty.${problem.difficulty}`)}</Badge>
            <Badge tone="neutral">{problem.timeLimitMillis} ms</Badge>
            {languageEffectiveLimits(problem.timeLimitMillis, problem.languageTimeLimitMultipliers, t).map((item) => (
              <Badge key={item.language} tone="neutral">{item.label} {item.timeLimitMillis} ms</Badge>
            ))}
            <Badge tone="neutral">{formatMemory(problem.memoryLimitKb)}</Badge>
            {problem.tags.map((tag) => <Badge key={tag}>{tag}</Badge>)}
          </div>
          <div className="flex gap-1 border-b border-[var(--oj-border-soft)] px-3 py-2">
            {[
              ["statement", t("problems.descriptionTab")],
              ["notes", t("problems.notesTab")],
              ["submissions", t("problems.submissionsTab")]
            ].map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={cn(
                  "h-9 rounded-xl px-3 text-sm font-medium outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
                  tab === value ? "bg-[var(--oj-primary-soft)] text-[var(--oj-primary)]" : "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)]"
                )}
                onClick={() => setTab(value as typeof tab)}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="problem-statement-scroll min-h-[520px] overflow-y-auto p-5 md:min-h-[676px] xl:min-h-0 xl:flex-1" onWheel={passWheelToPageAtScrollBoundary}>
            {tab === "statement" ? (
              <div>
                <MarkdownView content={problem.statement} />
                {problem.samples.length ? <SampleList samples={problem.samples} /> : null}
              </div>
            ) : null}
            {tab === "notes" ? (
              <div>
                <LanguageEffectiveLimitPanel
                  baseTimeLimitMillis={problem.timeLimitMillis}
                  multipliers={problem.languageTimeLimitMultipliers}
                />
                {problem.notes ? <MarkdownView content={problem.notes} /> : <EmptyState title={t("problems.notesEmpty")} />}
              </div>
            ) : null}
            {tab === "submissions" ? <ProblemSubmissions /> : null}
          </div>
        </div>

        <button
          type="button"
          role="separator"
          aria-orientation="vertical"
          aria-label={t("problems.resizePanels")}
          aria-valuemin={38}
          aria-valuemax={68}
          aria-valuenow={Math.round(paneRatio)}
          className="hidden cursor-col-resize items-center justify-center text-[var(--oj-ink-soft)] outline-none hover:text-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] xl:flex"
          onPointerDown={beginPaneResize}
          onKeyDown={(event) => {
            if (event.key === "ArrowLeft") {
              event.preventDefault();
              setPaneRatio((value) => clampPaneRatio(value - 3));
            }
            if (event.key === "ArrowRight") {
              event.preventDefault();
              setPaneRatio((value) => clampPaneRatio(value + 3));
            }
          }}
        >
          <span className="grid h-full w-full place-items-center rounded-full hover:bg-white">
            <GripVertical className="size-4" aria-hidden="true" />
          </span>
        </button>

        <aside className="flex min-h-0 flex-col overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] px-5 py-4">
            <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("problems.submitSolution")}</h2>
            <select
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
              className="h-9 rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm outline-none focus:ring-2 focus:ring-[var(--oj-focus)]"
              aria-label={t("common.language")}
            >
              {languageOptions.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </div>
          <div className="flex min-h-0 flex-1 flex-col p-4">
            <CodeEditor
              value={code}
              language={language}
              onChange={setCode}
              className="min-h-[520px] flex-1 md:min-h-[546px] xl:min-h-0"
            />
            <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
              <div className="text-xs text-[var(--oj-ink-muted)]">{t("problems.templateReady")} · {t("problems.indentSpaces")}</div>
              <div className="flex gap-2">
                <Button variant="outline" onClick={() => setResetCodeConfirmOpen(true)}>{t("problems.resetCode")}</Button>
                <Button
                  disabled={!code.trim() || submitMutation.isPending}
                  onClick={() => submitMutation.mutate()}
                >
                  <Send className="size-4" aria-hidden="true" />
                  {submitMutation.isPending ? t("problems.submitting") : t("problems.submit")}
                </Button>
              </div>
            </div>
            {submitMessage ? <p className="mt-3 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-sm text-[var(--oj-ink-muted)]">{submitMessage}</p> : null}
          </div>
        </aside>
      </section>

      <SubmissionDetailDialog
        submissionId={selectedSubmissionId}
        problemTitle={problemQuery.data?.title}
        onOpenChange={(open) => !open && setSelectedSubmissionId(null)}
      />
      <ConfirmDialog
        open={resetCodeConfirmOpen}
        onOpenChange={setResetCodeConfirmOpen}
        title={t("problems.resetCodeTitle")}
        description={t("problems.resetCodeDescription")}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("problems.resetCode")}
        onConfirm={() => setCode(languageTemplates[language] || "")}
      />
    </div>
  );

  function ProblemSubmissions() {
    if (submissionsQuery.isLoading) return <LoadingPanel label={t("submissions.loading")} />;
    if (submissionsQuery.isError) return <ErrorPanel title={readableCaughtError(submissionsQuery.error, locale, t("problems.submissionsLoadFailed"))} />;
    const submissions = submissionsQuery.data?.records ?? [];
    if (!submissions.length) {
      return (
        <div className="space-y-3">
          {contestContext ? <ContestSubmissionHint /> : null}
          <EmptyState title={t("problems.submissionsEmptyTitle")} description={t("problems.submissionsEmptyHint")} />
        </div>
      );
    }
    return (
      <div className="space-y-3">
        {contestContext ? <ContestSubmissionHint /> : null}
        {submissions.map((submission) => (
          <button
            key={submission.id}
            type="button"
            className="block w-full rounded-2xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 text-left outline-none hover:border-[var(--oj-border)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
            onClick={() => setSelectedSubmissionId(submission.id)}
          >
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <SubmissionStatusBadge status={submission.status} label={t(`submissionStatus.${submission.status}`)} />
                <span className="text-sm font-medium text-[var(--oj-ink)]">{submission.language}</span>
              </div>
              <span className="inline-flex items-center gap-1.5 text-xs text-[var(--oj-ink-muted)]">
                {formatDateTime(submission.createdAt)}
                <Eye className="size-3.5" aria-hidden="true" />
              </span>
            </div>
            <p className="mt-2 line-clamp-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{readableJudgeMessage(submission.judgeMessage, submission.status, locale, t(`submissionStatus.${submission.status}`))}</p>
          </button>
        ))}
      </div>
    );
  }

  function ContestSubmissionHint() {
    if (!contestContext) return null;
    return (
      <div className="flex flex-col gap-3 rounded-2xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 text-sm text-[var(--oj-ink-muted)] sm:flex-row sm:items-center sm:justify-between">
        <span>{t("problems.practiceSubmissionsOnly")}</span>
        <Button asChild size="sm" variant="outline" className="w-fit">
          <Link
            to="/submissions"
            search={{
              scope: "CONTEST",
              problemId,
              contestId: contestContext.contestId,
              contestRunId: contestContext.contestRunId,
              contestProblemId: contestContext.contestProblemId,
              page: 1
            }}
          >
            {t("submissions.viewContestSubmissions")}
          </Link>
        </Button>
      </div>
    );
  }
}

function SampleList({ samples }: { samples: Array<{ input: string; expectedOutput: string }> }) {
  const { t } = useI18n();
  return (
    <section className="mt-6 border-t border-[var(--oj-border-soft)] pt-5" aria-labelledby="problem-samples-title">
      <h2 id="problem-samples-title" className="mb-4 text-base font-semibold text-[var(--oj-ink)]">{t("problems.samplesTab")}</h2>
      <div className="space-y-4">
        {samples.map((sample, index) => (
          <article key={index} className="rounded-2xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.sampleTitle", { index: index + 1 })}</h3>
            <div className="mt-3 grid gap-3 lg:grid-cols-2">
              <SampleBlock label={t("problems.input")} value={sample.input} />
              <SampleBlock label={t("problems.output")} value={sample.expectedOutput} />
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function clampPaneRatio(value: number) {
  return Math.min(68, Math.max(38, value));
}

function languageEffectiveLimits(
  baseTimeLimitMillis: number,
  multipliers: ProblemLanguageTimeLimitMultipliers | null | undefined,
  t: (key: string, params?: Record<string, string | number>, fallback?: string) => string
) {
  return TIME_LIMIT_LANGUAGES.flatMap((language) => {
    const multiplier = normalizeMultiplier(multipliers?.[language]);
    if (multiplier <= 1) return [];
    return [{
      language,
      label: t(`problems.languages.${language}`),
      timeLimitMillis: Math.ceil(baseTimeLimitMillis * multiplier)
    }];
  });
}

function normalizeMultiplier(value?: number | null) {
  return typeof value === "number" && Number.isFinite(value) ? value : 1;
}

function SampleBlock({ label, value }: { label: string; value: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = React.useState(false);
  const copiedTimer = React.useRef<number | null>(null);

  React.useEffect(() => {
    return () => {
      if (copiedTimer.current !== null) window.clearTimeout(copiedTimer.current);
    };
  }, []);

  async function copySample() {
    await copyToClipboard(value);
    setCopied(true);
    if (copiedTimer.current !== null) window.clearTimeout(copiedTimer.current);
    copiedTimer.current = window.setTimeout(() => setCopied(false), 1200);
  }

  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <span className="text-xs font-medium text-[var(--oj-ink-muted)]">{label}</span>
        <button
          type="button"
          onClick={() => void copySample()}
          className="inline-flex h-7 items-center gap-1.5 rounded-lg px-2 text-xs font-medium text-[var(--oj-ink-muted)] outline-none hover:bg-white hover:text-[var(--oj-ink)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          aria-label={t("problems.copySample")}
        >
          <ClipboardCopy className="size-3.5" aria-hidden="true" />
          {copied ? t("problems.sampleCopied") : t("problems.copySample")}
        </button>
      </div>
      <pre className="min-h-24 overflow-auto rounded-xl border border-[var(--oj-border-soft)] bg-white p-3 text-xs leading-5 text-[var(--oj-ink)]">{value}</pre>
    </div>
  );
}
