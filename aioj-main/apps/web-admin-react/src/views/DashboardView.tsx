import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Activity, Bot, Database, ShieldCheck, Users } from "lucide-react";
import { api, type DailyAiUsageStatsResponse, type DailySubmissionStatsResponse, type DailyUserActivityResponse } from "@aioj/api-client";
import { Badge, Button, Card, CardBody } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, PageHeader } from "../components/Common";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";

interface DashboardStats {
  usersTotal: number;
  enabledUsers: number;
  problemsTotal: number;
  pendingDrafts: number;
  approvedDrafts: number;
  userActivity: DailyUserActivityResponse[];
  aiUsage: DailyAiUsageStatsResponse[];
  submissions: DailySubmissionStatsResponse[];
}

export function DashboardView() {
  const { t } = useI18n();
  const auth = useAuth();
  const dashboardQuery = useQuery({
    queryKey: ["admin-dashboard", auth.isAdmin ? "admin" : "teacher"],
    queryFn: () => loadDashboard(auth.isAdmin)
  });
  const stats = dashboardQuery.data;
  const tiles = [
    ...(auth.isAdmin ? [
      { label: t("dashboard.users"), value: stats?.usersTotal ?? 0, icon: Users, tone: "blue" as const },
      { label: t("dashboard.enabledUsers"), value: stats?.enabledUsers ?? 0, icon: ShieldCheck, tone: "green" as const }
    ] : []),
    { label: t("dashboard.problems"), value: stats?.problemsTotal ?? 0, icon: Database, tone: "amber" as const },
    { label: t("dashboard.pendingDrafts"), value: stats?.pendingDrafts ?? 0, icon: Bot, tone: "red" as const },
    { label: t("dashboard.approvedDrafts"), value: stats?.approvedDrafts ?? 0, icon: Activity, tone: "green" as const }
  ];

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("nav.dashboard")}
        description={t("auth.adminCopy")}
        actions={<Button variant="outline" disabled={dashboardQuery.isFetching} onClick={() => void dashboardQuery.refetch()}>{t("common.refresh")}</Button>}
      />

      {dashboardQuery.isLoading ? (
        <LoadingPanel label={t("dashboard.adminLoading")} />
      ) : dashboardQuery.isError ? (
        <ErrorPanel title={t("dashboard.loadFailed")} action={<Button variant="outline" onClick={() => void dashboardQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : stats ? (
        <>
          <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            {tiles.map((tile) => {
              const Icon = tile.icon;
              return (
                <Card key={tile.label} className="rounded-xl shadow-none">
                  <CardBody>
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-sm text-[var(--oj-ink-muted)]">{tile.label}</p>
                        <p className="mt-2 text-2xl font-semibold tabular-nums text-[var(--oj-ink)]">{tile.value}</p>
                      </div>
                      <Badge tone={tile.tone}><Icon className="size-4" aria-hidden="true" /></Badge>
                    </div>
                  </CardBody>
                </Card>
              );
            })}
          </section>

          <section className="grid gap-4 xl:grid-cols-3">
            <TrendCard
              title={t("dashboard.userActivityTrend")}
              description={t("dashboard.userActivityTrendCopy")}
              value={sum(stats.userActivity, (item) => item.activeUsers)}
              valueLabel={t("dashboard.activeUserDays")}
            >
              <LineChart
                data={stats.userActivity.map((item) => ({ label: dayLabel(item.date), value: item.activeUsers, secondary: item.newUsers }))}
                primaryLabel={t("dashboard.activeUsers")}
                secondaryLabel={t("dashboard.newUsers")}
              />
            </TrendCard>
            <TrendCard
              title={t("dashboard.aiUsageTrend")}
              description={t("dashboard.aiUsageTrendCopy")}
              value={sum(stats.aiUsage, (item) => item.calls)}
              valueLabel={t("dashboard.aiCalls")}
            >
              <BarChart
                data={stats.aiUsage.map((item) => ({ label: dayLabel(item.date), value: item.calls, secondary: item.promptTokens + item.completionTokens }))}
                primaryLabel={t("dashboard.aiCalls")}
                secondaryLabel={t("dashboard.aiTokens")}
              />
            </TrendCard>
            <TrendCard
              title={t("dashboard.submissionTrend")}
              description={t("dashboard.submissionTrendCopy")}
              value={sum(stats.submissions, (item) => item.totalSubmissions)}
              valueLabel={t("dashboard.totalSubmissions")}
            >
              <LineChart
                data={stats.submissions.map((item) => ({ label: dayLabel(item.date), value: item.totalSubmissions, secondary: item.acceptedSubmissions }))}
                primaryLabel={t("dashboard.totalSubmissions")}
                secondaryLabel={t("dashboard.acceptedSubmissions")}
              />
            </TrendCard>
          </section>

          <section className="grid gap-4 xl:grid-cols-[minmax(0,1fr)]">
            <Card className="rounded-xl shadow-none">
              <CardBody>
                <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("dashboard.localHealth")}</h2>
                <div className="mt-4 divide-y divide-[var(--oj-border-soft)]">
                  {[
                    ...(auth.isAdmin ? [[t("dashboard.healthUsers"), stats.usersTotal > 0] as const] : []),
                    [t("dashboard.healthProblems"), stats.problemsTotal > 0],
                    [t("dashboard.healthDrafts"), stats.pendingDrafts + stats.approvedDrafts > 0],
                    [t("dashboard.healthQuota"), stats.aiUsage.length > 0]
                  ].map(([label, ok]) => (
                    <div key={String(label)} className="flex items-center justify-between gap-3 py-3">
                      <span className="text-sm text-[var(--oj-ink)]">{label}</span>
                      <Badge tone={ok ? "green" : "amber"}>{ok ? t("common.ready") : t("common.check")}</Badge>
                    </div>
                  ))}
                </div>
              </CardBody>
            </Card>
          </section>
        </>
      ) : (
        <EmptyState title={t("dashboard.noAdminData")} />
      )}
    </div>
  );
}

async function loadDashboard(includeUserStats: boolean): Promise<DashboardStats> {
  const [usersResult, enabledUsersResult, problemsResult, pendingDraftsResult, approvedDraftsResult, userActivityResult, aiUsageResult, submissionsResult] = await Promise.allSettled([
    includeUserStats ? api.users({ page: 1, pageSize: 1 }) : Promise.resolve(null),
    includeUserStats ? api.users({ page: 1, pageSize: 1, enabled: true }) : Promise.resolve(null),
    api.problems({ page: 1, pageSize: 1 }),
    api.problemDrafts({ page: 1, pageSize: 1, status: "PENDING_REVIEW" }),
    api.problemDrafts({ page: 1, pageSize: 1, status: "APPROVED" }),
    includeUserStats ? api.userActivityAnalytics(14) : Promise.resolve([]),
    api.aiUsageAnalytics(14),
    api.submissionDailyAnalytics(7)
  ]);

  return {
    usersTotal: settled(usersResult)?.total ?? 0,
    enabledUsers: settled(enabledUsersResult)?.total ?? 0,
    problemsTotal: settled(problemsResult)?.total ?? 0,
    pendingDrafts: settled(pendingDraftsResult)?.total ?? 0,
    approvedDrafts: settled(approvedDraftsResult)?.total ?? 0,
    userActivity: settled(userActivityResult) ?? [],
    aiUsage: settled(aiUsageResult) ?? [],
    submissions: settled(submissionsResult) ?? []
  };
}

function settled<T>(result: PromiseSettledResult<T>) {
  return result.status === "fulfilled" ? result.value : null;
}

function TrendCard({
  title,
  description,
  value,
  valueLabel,
  children
}: {
  title: string;
  description: string;
  value: number;
  valueLabel: string;
  children: React.ReactNode;
}) {
  return (
    <Card className="rounded-xl shadow-none">
      <CardBody>
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-[var(--oj-ink)]">{title}</h2>
            <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{description}</p>
          </div>
          <div className="text-right">
            <div className="text-2xl font-semibold tabular-nums text-[var(--oj-ink)]">{value}</div>
            <div className="text-xs text-[var(--oj-ink-muted)]">{valueLabel}</div>
          </div>
        </div>
        <div className="mt-4">{children}</div>
      </CardBody>
    </Card>
  );
}

function LineChart({
  data,
  primaryLabel,
  secondaryLabel
}: {
  data: ChartDatum[];
  primaryLabel: string;
  secondaryLabel: string;
}) {
  const [activeIndex, setActiveIndex] = React.useState<number | null>(null);
  const max = Math.max(1, ...data.flatMap((item) => [item.value, item.secondary ?? 0]));
  const primaryPoints = points(data.map((item) => item.value), max);
  const secondaryPoints = points(data.map((item) => item.secondary ?? 0), max);
  const activeDatum = activeIndex == null ? null : data[activeIndex] ?? null;
  const activeX = activeIndex == null ? 0 : xForIndex(activeIndex, data.length);
  return (
    <div className="relative">
      {activeDatum ? <ChartTooltip datum={activeDatum} primaryLabel={primaryLabel} secondaryLabel={secondaryLabel} leftPercent={clampPercent(activeX)} /> : null}
      <svg className="h-36 w-full overflow-visible" viewBox="0 0 320 120" role="img" aria-label={`${primaryLabel}, ${secondaryLabel}`} onMouseLeave={() => setActiveIndex(null)}>
        <line x1="0" y1="110" x2="320" y2="110" stroke="var(--oj-border)" />
        {activeIndex != null ? <line x1={activeX} y1="12" x2={activeX} y2="110" stroke="var(--oj-border)" strokeDasharray="3 3" /> : null}
        <polyline points={secondaryPoints} fill="none" stroke="#f59e0b" strokeWidth="2" strokeLinecap="round" />
        <polyline points={primaryPoints} fill="none" stroke="var(--oj-primary)" strokeWidth="3" strokeLinecap="round" />
        {data.map((item, index) => {
          const x = xForIndex(index, data.length);
          const y = 110 - (item.value / max) * 96;
          return <circle key={`${item.label}-${index}`} cx={x} cy={y} r="3" fill="var(--oj-primary)" />;
        })}
        {data.map((item, index) => {
          const x = xForIndex(index, data.length);
          const width = data.length <= 1 ? 320 : 320 / data.length;
          const start = Math.max(0, x - width / 2);
          return (
            <rect
              key={`hit-${item.label}-${index}`}
              x={start}
              y="0"
              width={Math.min(width, 320 - start)}
              height="120"
              fill="transparent"
              tabIndex={0}
              role="button"
              aria-label={chartAccessibleText(item, primaryLabel, secondaryLabel)}
              className="cursor-pointer outline-none focus-visible:stroke-[var(--oj-primary)] focus-visible:stroke-2"
              onMouseEnter={() => setActiveIndex(index)}
              onFocus={() => setActiveIndex(index)}
              onBlur={() => setActiveIndex(null)}
            >
              <title>{chartAccessibleText(item, primaryLabel, secondaryLabel)}</title>
            </rect>
          );
        })}
      </svg>
      <ChartLegend primary={primaryLabel} secondary={secondaryLabel} labels={data.map((item) => item.label)} />
    </div>
  );
}

function BarChart({ data, primaryLabel, secondaryLabel }: { data: ChartDatum[]; primaryLabel: string; secondaryLabel: string }) {
  const [activeIndex, setActiveIndex] = React.useState<number | null>(null);
  const max = Math.max(1, ...data.map((item) => item.value));
  const width = Math.max(8, 260 / Math.max(1, data.length));
  const visibleWidth = Math.max(4, width - 2);
  const gap = data.length <= 1 ? 0 : (320 - width * data.length) / (data.length - 1);
  const activeDatum = activeIndex == null ? null : data[activeIndex] ?? null;
  const activeX = activeIndex == null ? 0 : xForBar(activeIndex, data.length, width, gap) + visibleWidth / 2;
  return (
    <div className="relative">
      {activeDatum ? <ChartTooltip datum={activeDatum} primaryLabel={primaryLabel} secondaryLabel={secondaryLabel} leftPercent={clampPercent(activeX)} /> : null}
      <svg className="h-36 w-full overflow-visible" viewBox="0 0 320 120" role="img" aria-label={`${primaryLabel}, ${secondaryLabel}`} onMouseLeave={() => setActiveIndex(null)}>
        <line x1="0" y1="110" x2="320" y2="110" stroke="var(--oj-border)" />
        {data.map((item, index) => {
          const x = xForBar(index, data.length, width, gap);
          const height = Math.max(2, (item.value / max) * 96);
          return <rect key={`${item.label}-${index}`} x={x} y={110 - height} width={visibleWidth} height={height} rx="3" fill="var(--oj-primary)" />;
        })}
        {data.map((item, index) => {
          const x = xForBar(index, data.length, width, gap);
          return (
            <rect
              key={`hit-${item.label}-${index}`}
              x={Math.max(0, x - 4)}
              y="0"
              width={Math.min(width + 8, 320 - Math.max(0, x - 4))}
              height="120"
              fill="transparent"
              tabIndex={0}
              role="button"
              aria-label={chartAccessibleText(item, primaryLabel, secondaryLabel)}
              className="cursor-pointer outline-none focus-visible:stroke-[var(--oj-primary)] focus-visible:stroke-2"
              onMouseEnter={() => setActiveIndex(index)}
              onFocus={() => setActiveIndex(index)}
              onBlur={() => setActiveIndex(null)}
            >
              <title>{chartAccessibleText(item, primaryLabel, secondaryLabel)}</title>
            </rect>
          );
        })}
      </svg>
      <ChartLegend primary={primaryLabel} secondary={secondaryLabel} labels={data.map((item) => item.label)} />
    </div>
  );
}

interface ChartDatum {
  label: string;
  value: number;
  secondary?: number;
}

function ChartTooltip({
  datum,
  primaryLabel,
  secondaryLabel,
  leftPercent
}: {
  datum: ChartDatum;
  primaryLabel: string;
  secondaryLabel: string;
  leftPercent: number;
}) {
  return (
    <div
      className="pointer-events-none absolute top-1 z-10 w-44 rounded-lg border border-[var(--oj-border)] bg-white px-3 py-2 text-xs shadow-lg"
      style={{ left: `${leftPercent}%`, transform: "translateX(-50%)" }}
    >
      <div className="font-semibold text-[var(--oj-ink)]">{datum.label}</div>
      <div className="mt-1 flex items-center justify-between gap-2 text-[var(--oj-ink-muted)]">
        <span>{primaryLabel}</span>
        <span className="font-medium tabular-nums text-[var(--oj-ink)]">{formatMetric(datum.value)}</span>
      </div>
      <div className="mt-1 flex items-center justify-between gap-2 text-[var(--oj-ink-muted)]">
        <span>{secondaryLabel}</span>
        <span className="font-medium tabular-nums text-[var(--oj-ink)]">{formatMetric(datum.secondary ?? 0)}</span>
      </div>
    </div>
  );
}

function ChartLegend({ primary, secondary, labels }: { primary: string; secondary?: string; labels: string[] }) {
  return (
    <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-[var(--oj-ink-muted)]">
      <span>{labels[0] ?? "--"} - {labels[labels.length - 1] ?? "--"}</span>
      <span className="flex items-center gap-3">
        <span className="inline-flex items-center gap-1"><span className="size-2 rounded-full bg-[var(--oj-primary)]" />{primary}</span>
        {secondary ? <span className="inline-flex items-center gap-1"><span className="size-2 rounded-full bg-amber-500" />{secondary}</span> : null}
      </span>
    </div>
  );
}

function points(values: number[], max: number) {
  if (!values.length) return "";
  return values.map((value, index) => {
    const x = values.length === 1 ? 160 : (index / (values.length - 1)) * 320;
    const y = 110 - (value / max) * 96;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  }).join(" ");
}

function xForIndex(index: number, total: number) {
  return total <= 1 ? 160 : (index / (total - 1)) * 320;
}

function xForBar(index: number, total: number, width: number, gap: number) {
  return total <= 1 ? (320 - Math.max(4, width - 2)) / 2 : index * (width + gap);
}

function clampPercent(x: number) {
  return Math.min(88, Math.max(12, (x / 320) * 100));
}

function chartAccessibleText(item: ChartDatum, primaryLabel: string, secondaryLabel: string) {
  return `${item.label}: ${primaryLabel} ${formatMetric(item.value)}, ${secondaryLabel} ${formatMetric(item.secondary ?? 0)}`;
}

function formatMetric(value: number) {
  return value.toLocaleString();
}

function sum<T>(items: T[], pick: (item: T) => number) {
  return items.reduce((total, item) => total + pick(item), 0);
}

function dayLabel(value: string) {
  if (!value) return "--";
  return value.slice(5);
}
