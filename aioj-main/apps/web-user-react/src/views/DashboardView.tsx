import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { ArrowRight, Bot, CheckCircle2, ListChecks, Send } from "lucide-react";
import { api, type SubmissionResponse } from "@aioj/api-client";
import { Badge, Button, Card, CardBody, cn } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, PageSection, SkeletonBlock } from "../components/Common";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";
import { difficultyTone, formatDateTime, statusTone } from "../lib/format";
import { readableJudgeMessage } from "../lib/readableError";

export function DashboardView() {
  const { t } = useI18n();
  const { profile } = useAuth();
  const problemsQuery = useQuery({ queryKey: ["problems", "dashboard"], queryFn: () => api.problems({ page: 1, pageSize: 6 }) });
  const submissionsQuery = useQuery({ queryKey: ["submissions", "mine", "dashboard"], queryFn: () => api.mySubmissions({ page: 1, pageSize: 8 }) });
  const usageQuery = useQuery({ queryKey: ["ai", "usage"], queryFn: () => api.usage(), retry: 0 });

  const submissions = submissionsQuery.data?.records ?? [];
  const accepted = submissions.filter((item) => item.status === "ACCEPTED").length;
  const latest = submissions[0];
  const aiUsage = usageQuery.data ? normalizeAiUsage(usageQuery.data) : null;

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection
        eyebrow={t("dashboard.eyebrow")}
        title={t("dashboard.greeting", { name: profile?.displayName ?? t("shell.studentFallback") })}
        description={t("dashboard.heroSubtitle")}
        actions={(
          <>
            <Button asChild>
              <Link to="/problems">
                {t("dashboard.startPractice")}
                <ArrowRight className="size-4" aria-hidden="true" />
              </Link>
            </Button>
            <Button asChild variant="outline">
              <Link to="/ai-chat">
                <Bot className="size-4" aria-hidden="true" />
                {t("nav.aiChat")}
              </Link>
            </Button>
          </>
        )}
      />

      <section className="grid gap-4 md:grid-cols-3">
        <Metric icon={<ListChecks className="size-4" />} label={t("dashboard.problemsAvailable")} value={problemsQuery.data?.total ?? "--"} />
        <Metric icon={<Send className="size-4" />} label={t("dashboard.mySubmissions")} value={submissionsQuery.data?.total ?? "--"} />
        <Metric icon={<CheckCircle2 className="size-4" />} label={t("dashboard.accepted")} value={accepted} />
      </section>

      <section className="grid min-h-0 gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(340px,0.7fr)]">
        <div className="rounded-2xl border border-[var(--oj-border)] bg-white">
          <div className="flex items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] px-5 py-4">
            <div>
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("dashboard.recommended")}</h2>
              <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("dashboard.recommendedCopy")}</p>
            </div>
            <Button asChild variant="ghost" size="sm">
              <Link to="/problems">{t("dashboard.viewAll")}</Link>
            </Button>
          </div>
          <div className="divide-y divide-[var(--oj-border-soft)]">
            {problemsQuery.isLoading ? Array.from({ length: 4 }).map((_, index) => (
              <div key={index} className="p-4"><SkeletonBlock className="h-14" /></div>
            )) : problemsQuery.isError ? (
              <div className="p-5"><ErrorPanel title={t("problems.loadFailed")} /></div>
            ) : problemsQuery.data?.records.length ? problemsQuery.data.records.map((problem) => (
              <Link
                key={problem.id}
                to="/problems/$problemId"
                params={{ problemId: problem.id }}
                className="block px-5 py-4 outline-none transition-colors hover:bg-[var(--oj-surface-muted)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
              >
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="truncate text-sm font-semibold text-[var(--oj-ink)]">{problem.title}</h3>
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Badge tone={difficultyTone(problem.difficulty)}>{t(`difficulty.${problem.difficulty}`)}</Badge>
                      {problem.tags.slice(0, 3).map((tag) => <Badge key={tag}>{tag}</Badge>)}
                    </div>
                  </div>
                  <span className="text-sm font-medium text-[var(--oj-primary)]">{t("dashboard.practice")}</span>
                </div>
              </Link>
            )) : (
              <EmptyState title={t("dashboard.noRecommendedTitle")} description={t("dashboard.noRecommendedDesc")} />
            )}
          </div>
        </div>

        <div className="flex min-h-0 flex-col gap-5">
          <Card>
            <CardBody>
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("dashboard.latestStatus")}</h2>
                  <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{latest ? formatDateTime(latest.createdAt) : t("dashboard.noSubmissions")}</p>
                </div>
                {latest ? <Badge tone={statusTone(latest.status)}>{t(`submissionStatus.${latest.status}`)}</Badge> : null}
              </div>
              {latest ? <LatestSubmission submission={latest} /> : (
                <Button asChild className="mt-5" variant="outline">
                  <Link to="/problems">{t("dashboard.firstProblem")}</Link>
                </Button>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardBody>
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("dashboard.myAiUsage")}</h2>
              {aiUsage ? (
                <div className="mt-4 space-y-3">
                  <UsageRow label={t("dashboard.recentAiUsage", { hours: aiUsage.recentWindowHours })} used={aiUsage.usedRecent} total={aiUsage.rollingLimit} />
                  <UsageRow label={t("dashboard.thisMonth")} used={aiUsage.usedThisMonth} total={aiUsage.monthlyLimit} />
                </div>
              ) : (
                <p className="mt-3 text-sm text-[var(--oj-ink-muted)]">{t("dashboard.usageUnavailable")}</p>
              )}
            </CardBody>
          </Card>
        </div>
      </section>
    </div>
  );
}

function normalizeAiUsage(usage: {
  usedRecent?: number;
  rollingLimit?: number;
  recentWindowHours?: number;
  usedToday?: number;
  dailyLimit?: number;
  usedThisMonth: number;
  monthlyLimit: number;
}) {
  return {
    usedRecent: usage.usedRecent ?? usage.usedToday ?? 0,
    rollingLimit: usage.rollingLimit ?? usage.dailyLimit ?? 50,
    recentWindowHours: usage.recentWindowHours ?? 2,
    usedThisMonth: usage.usedThisMonth,
    monthlyLimit: usage.monthlyLimit
  };
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string | number }) {
  return (
    <div className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-[var(--oj-ink-muted)]">{label}</span>
        <span className="grid size-8 place-items-center rounded-xl bg-[var(--oj-primary-soft)] text-[var(--oj-primary)]">{icon}</span>
      </div>
      <div className="mt-5 text-3xl font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function LatestSubmission({ submission }: { submission: SubmissionResponse }) {
  const { t, locale } = useI18n();
  return (
    <div className="mt-5 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex items-center justify-between gap-3">
        <span className="text-sm font-medium text-[var(--oj-ink)]">{t("submissions.problem")} #{submission.problemId}</span>
        <span className="text-xs text-[var(--oj-ink-muted)]">{submission.language}</span>
      </div>
      <p className="mt-3 line-clamp-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{readableJudgeMessage(submission.judgeMessage, submission.status, locale, t(`submissionStatus.${submission.status}`))}</p>
      <Button asChild className="mt-4" size="sm" variant="outline">
        <Link to="/submissions">{t("dashboard.viewSubmissions")}</Link>
      </Button>
    </div>
  );
}

function UsageRow({ label, used, total }: { label: string; used: number; total: number }) {
  const pct = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;
  return (
    <div>
      <div className="mb-1 flex justify-between text-sm">
        <span className="text-[var(--oj-ink-muted)]">{label}</span>
        <span className="tabular-nums text-[var(--oj-ink)]">{used}/{total}</span>
      </div>
      <div className="h-2 rounded-full bg-[var(--oj-border-soft)]">
        <div className={cn("h-2 rounded-full bg-[var(--oj-primary)]")} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
