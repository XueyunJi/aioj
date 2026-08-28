import * as Dialog from "@radix-ui/react-dialog";
import * as React from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, X } from "lucide-react";
import { activeQueryRefetchInterval, api, type EntityId, type SubmissionResponse, type SubmissionStatus } from "@aioj/api-client";
import { Button } from "@aioj/ui-react";
import { AiTutorWorkspace } from "./AiTutorWorkspace";
import { ErrorPanel, LoadingPanel } from "./Common";
import { MarkdownView } from "./MarkdownView";
import { isLiveSubmissionStatus, SubmissionStatusBadge, submissionStatusDetailClass } from "./SubmissionStatusBadge";
import { useI18n } from "../lib/i18n";
import { formatMemory } from "../lib/format";
import { readableJudgeMessage } from "../lib/readableError";

export function SubmissionDetailDialog({
  submissionId,
  problemTitle,
  onOpenChange
}: {
  submissionId: EntityId | null;
  problemTitle?: string | null;
  onOpenChange: (open: boolean) => void;
}) {
  const { t, locale } = useI18n();
  const queryClient = useQueryClient();
  const hadLiveStatus = React.useRef(false);
  const [aiOpen, setAiOpen] = React.useState(false);
  const detailQuery = useQuery({
    queryKey: ["submission", submissionId],
    queryFn: () => api.submission(submissionId!),
    enabled: Boolean(submissionId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => isLiveSubmissionStatus(data?.status))
  });
  const fallbackProblemTitleQuery = useQuery({
    queryKey: ["problem", "submission-dialog-title", detailQuery.data?.problemId],
    queryFn: () => api.problem(detailQuery.data!.problemId),
    enabled: Boolean(detailQuery.data?.problemId && !problemTitle?.trim()),
    staleTime: 5 * 60 * 1000
  });

  React.useEffect(() => {
    hadLiveStatus.current = false;
    setAiOpen(false);
  }, [submissionId]);

  React.useEffect(() => {
    const status = detailQuery.data?.status;
    if (!status) return;
    if (isLiveSubmissionStatus(status)) {
      hadLiveStatus.current = true;
      return;
    }
    if (hadLiveStatus.current) {
      hadLiveStatus.current = false;
      void queryClient.invalidateQueries({ queryKey: ["submissions"] });
    }
  }, [detailQuery.data?.status, queryClient]);

  const submission = detailQuery.data;
  const aiAnalysisUnavailable = submission ? isLiveSubmissionStatus(submission.status) : false;
  const submissionProblemTitle = problemTitle?.trim() || fallbackProblemTitleQuery.data?.title?.trim() || null;

  return (
    <>
      <Dialog.Root open={Boolean(submissionId)} onOpenChange={(open) => onOpenChange(open)}>
        <Dialog.Portal>
          <Dialog.Close asChild>
            <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-950/35" />
          </Dialog.Close>
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[86dvh] w-[min(92vw,820px)] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white shadow-lg outline-none">
          <div className="flex items-center justify-between border-b border-[var(--oj-border-soft)] px-5 py-4">
            <Dialog.Title className="text-base font-semibold text-[var(--oj-ink)]">{t("submissions.viewCodeTitle")}</Dialog.Title>
            <Dialog.Close asChild>
              <button className="grid size-8 place-items-center rounded-xl text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]" aria-label={t("common.cancel")}>
                <X className="size-4" aria-hidden="true" />
              </button>
            </Dialog.Close>
          </div>
          <div className="max-h-[calc(86dvh-64px)] overflow-y-auto p-5">
            {detailQuery.isLoading ? <LoadingPanel label={t("common.loading")} /> : submission ? (
              <div className="space-y-4">
                <div className="flex flex-wrap items-start justify-between gap-3 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3">
                  <div className="min-w-0">
                    <h3 className="text-sm font-semibold text-blue-950">{t("submissions.analyzeWithAiTitle")}</h3>
                    <p className="mt-1 text-xs leading-5 text-blue-900">
                      {aiAnalysisUnavailable ? t("submissions.analyzeWithAiUnavailable") : t("submissions.analyzeWithAiCopy")}
                    </p>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    disabled={aiAnalysisUnavailable}
                    title={aiAnalysisUnavailable ? t("submissions.analyzeWithAiUnavailable") : undefined}
                    onClick={() => {
                      if (!aiAnalysisUnavailable) setAiOpen(true);
                    }}
                  >
                    <Bot className="size-4" aria-hidden="true" />
                    {t("submissions.analyzeWithAi")}
                  </Button>
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  <Detail label={t("submissions.viewProblemLabel")} value={submissionProblemTitle ?? `#${submission.problemId}`} />
                  <Detail label={t("submissions.viewLanguageLabel")} value={submission.language} />
                  <StatusDetail label={t("submissions.viewStatusLabel")} status={submission.status} value={t(`submissionStatus.${submission.status}`)} />
                  <Detail label={t("submissions.viewMemoryLabel")} value={formatMemory(submission.memoryKb)} />
                  <Detail label={t("submissions.viewRunTimeLabel")} value={submission.runTimeMillis ? `${submission.runTimeMillis} ms` : "--"} />
                  <Detail label={t("submissions.viewExitStatusLabel")} value={submission.exitStatus ?? "--"} />
                  <Detail label={t("contests.maxScore")} value={formatScoreSummary(submission.score, submission.maxScore)} />
                </div>
                {submission.caseResults?.length ? <SubmissionCaseResultsTable caseResults={submission.caseResults} /> : null}
                {submission.code ? (
                  <div className="overflow-hidden rounded-xl border border-[var(--oj-border-soft)] bg-white">
                    <div className="flex items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] px-3 py-2">
                      <span className="text-sm font-medium text-[var(--oj-ink)]">{t("contests.sourceCode")}</span>
                    </div>
                    <MarkdownView
                      content={codeToMarkdown(submission.code, submission.language)}
                      className="max-h-80 overflow-auto"
                    />
                  </div>
                ) : (
                  <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4 text-sm text-[var(--oj-ink-muted)]">
                    {t("submissions.viewCodeUnavailable")}
                  </div>
                )}
                {submission.stderrExcerpt ? <OutputBlock label={t("submissions.viewStderrLabel")} value={submission.stderrExcerpt} /> : null}
                <OutputBlock label={t("submissions.viewJudgeMessage")} value={readableJudgeMessage(submission.judgeMessage, submission.status, locale, t(`submissionStatus.${submission.status}`))} />
              </div>
            ) : <ErrorPanel title={t("submissions.viewLoadFailed")} />}
          </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <Dialog.Root open={aiOpen && Boolean(submission) && !aiAnalysisUnavailable} onOpenChange={setAiOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-[60] bg-slate-950/35" />
          <Dialog.Content className="fixed inset-y-0 right-0 z-[70] flex w-[min(100vw,1120px)] flex-col border-l border-[var(--oj-border)] bg-[var(--oj-app-bg)] shadow-lg outline-none">
            <div className="flex items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] bg-white px-4 py-3">
              <div className="min-w-0">
                <Dialog.Title className="truncate text-lg font-semibold text-[var(--oj-ink)]">
                  {t("submissions.analyzeWithAiTitle")}
                </Dialog.Title>
                <Dialog.Description className="mt-1 truncate text-sm text-[var(--oj-ink-muted)]">
                  {submission ? `${submissionProblemTitle ?? `${t("submissions.viewProblemLabel")} #${submission.problemId}`} · ${t(`submissionStatus.${submission.status}`)}` : ""}
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <button
                  type="button"
                  className="grid size-9 shrink-0 place-items-center rounded-xl text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
                  aria-label={t("common.cancel")}
                >
                  <X className="size-4" aria-hidden="true" />
                </button>
              </Dialog.Close>
            </div>
            <div className="min-h-0 flex-1 p-3 md:p-4">
              {submission ? (
                <AiTutorWorkspace
                  source="submission_analysis"
                  problemId={submission.problemId}
                  problemTitle={submissionProblemTitle}
                  lockedProblem
                  compact
                  submissionContext={{
                    submissionId: submission.id,
                    intent: submission.status === "ACCEPTED" ? "OPTIMIZE" : "EXPLAIN_ERROR",
                    userSelected: true,
                    note: `${t("submissions.viewStatusLabel")}: ${t(`submissionStatus.${submission.status}`)}`
                  }}
                  sourceRefType="SUBMISSION"
                  sourceRefId={submission.id}
                  initialPrompt={t(submissionAnalysisPromptKey(submission.status))}
                />
              ) : null}
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}

function Detail({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
      <div className="text-xs text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-1 text-sm font-medium tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function StatusDetail({ label, status, value }: { label: string; status: SubmissionStatus; value: string }) {
  return (
    <div className={submissionStatusDetailClass(status) + " rounded-xl border p-3"}>
      <div className="text-xs text-[var(--oj-ink-muted)]">{label}</div>
      <SubmissionStatusBadge status={status} label={value} className="mt-1 h-7 px-2.5 text-sm" />
    </div>
  );
}

function submissionAnalysisPromptKey(status: SubmissionStatus) {
  switch (status) {
    case "ACCEPTED":
      return "submissions.aiAcceptedAnalysisPrompt";
    case "TIME_LIMIT_EXCEEDED":
      return "submissions.aiTimeLimitAnalysisPrompt";
    case "MEMORY_LIMIT_EXCEEDED":
      return "submissions.aiMemoryLimitAnalysisPrompt";
    case "COMPILE_ERROR":
      return "submissions.aiCompilationAnalysisPrompt";
    case "RUNTIME_ERROR":
      return "submissions.aiRuntimeAnalysisPrompt";
    case "WRONG_ANSWER":
      return "submissions.aiWrongAnswerAnalysisPrompt";
    case "OUTPUT_LIMIT_EXCEEDED":
      return "submissions.aiOutputLimitAnalysisPrompt";
    case "SYSTEM_ERROR":
      return "submissions.aiSystemErrorAnalysisPrompt";
    default:
      return "submissions.aiAnalysisPrompt";
  }
}

function OutputBlock({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="mb-1 text-sm font-medium text-[var(--oj-ink)]">{label}</div>
      <pre className="max-h-56 overflow-auto rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink)]">{value}</pre>
    </div>
  );
}

function SubmissionCaseResultsTable({ caseResults }: { caseResults: NonNullable<SubmissionResponse["caseResults"]> }) {
  const { t } = useI18n();
  const sorted = [...caseResults].sort((left, right) => left.caseIndex - right.caseIndex);
  return (
    <section className="overflow-hidden rounded-xl border border-[var(--oj-border-soft)] bg-white">
      <div className="border-b border-[var(--oj-border-soft)] px-3 py-2">
        <h4 className="text-sm font-semibold text-[var(--oj-ink)]">{t("contests.caseResults")}</h4>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[620px] text-sm">
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
                <td className="px-3 py-2">
                  <SubmissionStatusBadge status={item.status} label={t(`submissionStatus.${item.status}`)} />
                </td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink)]">{formatScoreSummary(item.score, item.maxScore)}</td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink-muted)]">{item.timeMillis == null ? "--" : `${item.timeMillis} ms`}</td>
                <td className="px-3 py-2 text-center tabular-nums text-[var(--oj-ink-muted)]">{formatMemory(item.memoryKb)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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

function formatScoreSummary(score?: number | null, maxScore?: number | null) {
  if (score == null && maxScore == null) return "--";
  return `${formatScore(score)}/${formatScore(maxScore)}`;
}

function formatScore(value?: number | null) {
  if (value == null) return "0";
  return Number.isInteger(value) ? String(value) : value.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
}
