import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Link, useNavigate } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock, Check, CheckCircle2, Clock3, MailOpen, RotateCcw, Search, Trophy, X } from "lucide-react";
import { ApiError, api, type ContestMode, type ContestOpenRunResponse, type ContestRegistrationResponse, type ContestRunStatus } from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { ConfirmDialog, EmptyState, ErrorPanel, LoadingPanel, PageSection } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { formatDateTime } from "../lib/format";

export function ContestsView() {
  const { t } = useI18n();
  const [keyword, setKeyword] = React.useState("");
  const [modeFilter, setModeFilter] = React.useState<"" | Extract<ContestMode, "ACM" | "IOI">>("");
  const [statusFilter, setStatusFilter] = React.useState<"" | Extract<ContestRunStatus, "SCHEDULED" | "RUNNING" | "ENDED">>("");
  const [page, setPage] = React.useState(1);
  const [invitationsOpen, setInvitationsOpen] = React.useState(false);
  const queryClient = useQueryClient();
  const pageSize = 50;
  const trimmedKeyword = keyword.trim();
  const hasFilters = Boolean(trimmedKeyword || modeFilter || statusFilter);

  const contestsQuery = useQuery({
    queryKey: ["student-open-contest-runs", trimmedKeyword, modeFilter, statusFilter, page],
    queryFn: () => api.openContestRuns({
      page,
      pageSize,
      keyword: trimmedKeyword,
      mode: modeFilter,
      status: statusFilter
    })
  });
  const invitationUnreadQuery = useQuery({
    queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"],
    queryFn: () => api.userNotificationUnreadCount("CONTEST_INVITATION")
  });
  const markInvitationsReadMutation = useMutation({
    mutationFn: () => api.markUserNotificationsRead({ type: "CONTEST_INVITATION" }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"] });
    }
  });

  const records = contestsQuery.data?.records ?? [];
  const hasUnreadInvitations = (invitationUnreadQuery.data?.count ?? 0) > 0;
  const markInvitationsViewed = React.useCallback(() => {
    if (!hasUnreadInvitations || markInvitationsReadMutation.isPending) return;
    void markInvitationsReadMutation.mutateAsync().catch(() => undefined);
  }, [hasUnreadInvitations, markInvitationsReadMutation.isPending, markInvitationsReadMutation.mutateAsync]);
  const total = contestsQuery.data?.total ?? 0;
  const maxPage = Math.max(1, Math.ceil(total / pageSize));
  const rangeStart = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const rangeEnd = Math.min(total, page * pageSize);
  const resetFilters = () => {
    setKeyword("");
    setModeFilter("");
    setStatusFilter("");
    setPage(1);
  };

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection
        eyebrow={t("nav.contests")}
        title={t("contests.studentTitle")}
        description={t("contests.studentDescription")}
      />

      <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-4">
        <div className="grid gap-3 lg:grid-cols-[minmax(260px,1fr)_220px_220px_auto_auto]">
          <label className="relative block">
            <span className="sr-only">{t("contests.studentRunSearchPlaceholder")}</span>
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--oj-ink-muted)]" aria-hidden="true" />
            <input
              value={keyword}
              onChange={(event) => { setKeyword(event.target.value); setPage(1); }}
              placeholder={t("contests.studentRunSearchPlaceholder")}
              className="h-11 w-full rounded-xl border border-[var(--oj-border)] bg-white pl-10 pr-3 text-sm outline-none transition focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
            />
          </label>
          <label className="block">
            <span className="sr-only">{t("contests.modeLabel")}</span>
            <select
              value={modeFilter}
              onChange={(event) => { setModeFilter(event.target.value as "" | Extract<ContestMode, "ACM" | "IOI">); setPage(1); }}
              className="h-11 w-full rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm outline-none transition focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
            >
              <option value="">{t("contests.allModes")}</option>
              <option value="ACM">{t("contests.acmOnly")}</option>
              <option value="IOI">{t("contests.nonAcmOnly")}</option>
            </select>
          </label>
          <label className="block">
            <span className="sr-only">{t("contests.runStatusLabel")}</span>
            <select
              value={statusFilter}
              onChange={(event) => { setStatusFilter(event.target.value as "" | Extract<ContestRunStatus, "SCHEDULED" | "RUNNING" | "ENDED">); setPage(1); }}
              className="h-11 w-full rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm outline-none transition focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
            >
              <option value="">{t("contests.allRunStatuses")}</option>
              <option value="SCHEDULED">{t("contests.runStatusFilter.SCHEDULED")}</option>
              <option value="RUNNING">{t("contests.runStatusFilter.RUNNING")}</option>
              <option value="ENDED">{t("contests.runStatusFilter.ENDED")}</option>
            </select>
          </label>
          <div className="relative w-full lg:w-auto">
            <Button variant="outline" className="h-11 w-full" onClick={() => setInvitationsOpen(true)}>
              <MailOpen className="size-4" aria-hidden="true" />
              {t("contests.invitationsTitle")}
            </Button>
            {hasUnreadInvitations ? (
              <span className="absolute right-2 top-2 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-label={t("shell.unreadNotifications")} />
            ) : null}
          </div>
          <button
            type="button"
            onClick={resetFilters}
            disabled={!hasFilters}
            className="inline-flex h-11 items-center justify-center gap-2 rounded-xl border border-[var(--oj-border)] bg-white px-4 text-sm font-medium text-[var(--oj-ink)] transition hover:border-[var(--oj-primary)] hover:text-[var(--oj-primary)] disabled:cursor-not-allowed disabled:opacity-50"
          >
            <RotateCcw className="size-4" aria-hidden="true" />
            {t("common.reset")}
          </button>
        </div>
      </section>

      {contestsQuery.isLoading ? (
        <LoadingPanel label={t("contests.loading")} />
      ) : contestsQuery.isError ? (
        <ErrorPanel title={t("contests.loadFailed")} />
      ) : records.length ? (
        <>
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {records.map((item) => (
              <ContestRunCard
                key={item.run.id}
                item={item}
              />
            ))}
          </section>
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[var(--oj-border-soft)] bg-white px-4 py-3 text-sm text-[var(--oj-ink-muted)]">
            <span className="tabular-nums">{rangeStart}-{rangeEnd} / {total}</span>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>{t("common.previous")}</Button>
              <span className="tabular-nums">{page}/{maxPage}</span>
              <Button variant="outline" size="sm" disabled={page >= maxPage} onClick={() => setPage((current) => Math.min(maxPage, current + 1))}>{t("common.next")}</Button>
            </div>
          </div>
        </>
      ) : hasFilters ? (
        <EmptyState title={t("contests.studentFilteredEmptyTitle")} description={t("contests.studentFilteredEmptyDescription")} />
      ) : (
        <EmptyState title={t("contests.studentEmptyTitle")} description={t("contests.studentEmptyDescription")} />
      )}

      <InvitationsDialog
        open={invitationsOpen}
        onOpenChange={setInvitationsOpen}
        hasUnread={hasUnreadInvitations}
        markingRead={markInvitationsReadMutation.isPending}
        onContentViewed={markInvitationsViewed}
      />

    </div>
  );
}

const INVITATIONS_PAGE_SIZE = 10;

function InvitationsDialog({
  open,
  onOpenChange,
  hasUnread,
  markingRead,
  onContentViewed
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  hasUnread: boolean;
  markingRead: boolean;
  onContentViewed: () => void;
}) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = React.useState(1);
  const [actionError, setActionError] = React.useState<string | null>(null);
  const [declineTarget, setDeclineTarget] = React.useState<ContestRegistrationResponse | null>(null);

  const invitationsQuery = useQuery({
    queryKey: ["student-contest-invitations", page],
    queryFn: () => api.myContestInvitations({ page, pageSize: INVITATIONS_PAGE_SIZE }),
    enabled: open
  });

  React.useEffect(() => {
    if (open && hasUnread && !markingRead && invitationsQuery.isSuccess) {
      onContentViewed();
    }
  }, [hasUnread, invitationsQuery.isSuccess, markingRead, onContentViewed, open]);

  const acceptMutation = useMutation({
    mutationFn: (registration: ContestRegistrationResponse) => api.acceptContestInvitation(registration.contestId, registration.contestRunId),
    onSuccess: async (_data, registration) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["student-contest-invitations"] }),
        queryClient.invalidateQueries({ queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"] }),
        queryClient.invalidateQueries({ queryKey: ["student-open-contest-runs"] }),
        queryClient.invalidateQueries({ queryKey: ["student-contest-open-run", registration.contestId, registration.contestRunId] })
      ]);
      void navigate({
        to: "/contests/$contestId",
        params: { contestId: registration.contestId },
        search: { runId: registration.contestRunId, tab: "overview" }
      });
    },
    onError: (caught) => {
      setActionError(caught instanceof ApiError ? caught.userMessage : t("contests.invitationAcceptFailed"));
    }
  });

  const declineMutation = useMutation({
    mutationFn: (registration: ContestRegistrationResponse) => api.declineContestInvitation(registration.contestId, registration.contestRunId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["student-contest-invitations"] }),
        queryClient.invalidateQueries({ queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"] }),
        queryClient.invalidateQueries({ queryKey: ["student-open-contest-runs"] })
      ]);
    },
    onError: (caught) => {
      setActionError(caught instanceof ApiError ? caught.userMessage : t("contests.invitationDeclineFailed"));
    }
  });

  const records = invitationsQuery.data?.records ?? [];
  const total = invitationsQuery.data?.total ?? 0;
  const maxPage = Math.max(1, Math.ceil(total / INVITATIONS_PAGE_SIZE));
  const rangeStart = total === 0 ? 0 : (page - 1) * INVITATIONS_PAGE_SIZE + 1;
  const rangeEnd = Math.min(total, page * INVITATIONS_PAGE_SIZE);
  const busy = acceptMutation.isPending || declineMutation.isPending;

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-950/35" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[82dvh] w-[min(92vw,1080px)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white shadow-lg outline-none">
          <div className="flex items-start justify-between gap-4 border-b border-[var(--oj-border-soft)] px-5 py-4">
            <div className="min-w-0">
              <Dialog.Title className="flex flex-wrap items-center gap-2 text-base font-semibold text-[var(--oj-ink)]">
                <MailOpen className="size-4" aria-hidden="true" />
                {t("contests.invitationsTitle")}
                {total > 0 ? <Badge tone="amber">{total}</Badge> : null}
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("contests.invitationsCopy")}</Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <Button variant="ghost" size="sm" aria-label={t("common.close")}>
                <X className="size-4" aria-hidden="true" />
              </Button>
            </Dialog.Close>
          </div>
          <section className="min-h-0 overflow-y-auto p-5">
      {actionError ? <div className="mb-3"><ErrorPanel title={actionError} /></div> : null}
      {invitationsQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : invitationsQuery.isError ? (
        <ErrorPanel title={t("contests.invitationsLoadFailed")} />
      ) : records.length === 0 ? (
        <EmptyState title={t("contests.invitationsEmptyTitle")} description={t("contests.invitationsEmptyDescription")} />
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-2">
            {records.map((registration) => (
              <InvitationCard
                key={registration.id}
                registration={registration}
                busy={busy}
                onAccept={(target) => {
                  setActionError(null);
                  acceptMutation.mutate(target);
                }}
                onDecline={setDeclineTarget}
              />
            ))}
          </div>
          {total > 0 ? (
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-sm text-[var(--oj-ink-muted)]">
              <span className="tabular-nums">{rangeStart}-{rangeEnd} / {total}</span>
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>{t("common.previous")}</Button>
                <span className="tabular-nums">{page}/{maxPage}</span>
                <Button variant="outline" size="sm" disabled={page >= maxPage} onClick={() => setPage((current) => Math.min(maxPage, current + 1))}>{t("common.next")}</Button>
              </div>
            </div>
          ) : null}
        </>
      )}
          </section>
        </Dialog.Content>
      </Dialog.Portal>
      <ConfirmDialog
        open={Boolean(declineTarget)}
        onOpenChange={(open) => !open && setDeclineTarget(null)}
        title={t("contests.invitationDeclineConfirmTitle")}
        description={t("contests.invitationDeclineConfirmCopy")}
        cancelLabel={t("common.cancel")}
        confirmLabel={t("contests.invitationDecline")}
        onConfirm={async () => {
          if (!declineTarget) return;
          setActionError(null);
          try {
            await declineMutation.mutateAsync(declineTarget);
          } catch {
            // The mutation onError handler already surfaces the user-facing message.
          } finally {
            setDeclineTarget(null);
          }
        }}
      />
    </Dialog.Root>
  );
}

function InvitationCard({
  registration,
  busy,
  onAccept,
  onDecline
}: {
  registration: ContestRegistrationResponse;
  busy: boolean;
  onAccept: (registration: ContestRegistrationResponse) => void;
  onDecline: (registration: ContestRegistrationResponse) => void;
}) {
  const { t } = useI18n();
  const openRunQuery = useQuery({
    queryKey: ["student-contest-open-run", registration.contestId, registration.contestRunId],
    queryFn: () => api.openContestRun(registration.contestId, registration.contestRunId)
  });
  const contest = openRunQuery.data?.contest;
  const run = openRunQuery.data?.run;
  const title = run?.title?.trim() || contest?.title || t("contests.untitledRun");

  return (
    <article className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-[var(--oj-ink)]" title={title}>{title}</div>
          {contest && run && contest.title !== run.title ? (
            <div className="mt-0.5 truncate text-xs text-[var(--oj-ink-muted)]" title={contest.title}>{contest.title}</div>
          ) : null}
          <div className="mt-1 flex items-center gap-1.5 text-xs tabular-nums text-[var(--oj-ink-muted)]">
            <CalendarClock className="size-3.5 shrink-0" aria-hidden="true" />
            <span>{openRunQuery.isLoading ? t("common.loading") : run ? `${formatDateTime(run.startAt)} - ${formatDateTime(run.endAt)}` : "--"}</span>
          </div>
          <div className="mt-1 text-xs tabular-nums text-[var(--oj-ink-muted)]">
            {t("contests.invitationInvitedAt")}: {formatDateTime(registration.requestedAt)}
          </div>
        </div>
        <Badge tone="blue">{t("contests.registrationStatus.INVITED")}</Badge>
      </div>
      <div className="mt-3 flex flex-wrap justify-end gap-2">
        <Button size="sm" variant="outline" disabled={busy} onClick={() => onDecline(registration)}>
          <X className="size-4" aria-hidden="true" />
          {t("contests.invitationDecline")}
        </Button>
        <Button size="sm" disabled={busy} onClick={() => onAccept(registration)}>
          <Check className="size-4" aria-hidden="true" />
          {t("contests.invitationAccept")}
        </Button>
      </div>
    </article>
  );
}

function ContestRunCard({ item }: { item: ContestOpenRunResponse }) {
  const { t } = useI18n();
  const { contest, run, registration } = item;
  const runTitle = run.title?.trim() || t("contests.untitledRun");
  const approved = registration?.status === "APPROVED";
  const rejected = registration?.status === "REJECTED";
  const ended = isEnded(run.endAt);

  return (
    <Link
      to="/contests/$contestId"
      params={{ contestId: contest.id }}
      search={{ runId: run.id, tab: "overview" }}
      className="group relative flex min-h-[205px] flex-col justify-between overflow-hidden rounded-xl border border-[var(--oj-border)] bg-white p-4 outline-none transition-[border-color,box-shadow,transform] duration-200 hover:-translate-y-0.5 hover:border-[var(--oj-primary)] hover:shadow-[0_14px_34px_rgba(37,99,235,0.12)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] motion-reduce:transition-none motion-reduce:hover:translate-y-0"
      onMouseMove={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        event.currentTarget.style.setProperty("--contest-card-x", `${event.clientX - rect.left}px`);
        event.currentTarget.style.setProperty("--contest-card-y", `${event.clientY - rect.top}px`);
      }}
    >
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-200 group-hover:opacity-100 group-focus-visible:opacity-100 motion-reduce:hidden"
        style={{ background: "radial-gradient(360px circle at var(--contest-card-x, 50%) var(--contest-card-y, 0%), rgba(59,130,246,0.16), rgba(148,163,184,0.08) 38%, transparent 68%)" }}
      />
      <div className="relative z-10">
        <div className="mb-2 flex flex-wrap gap-1.5">
          <Badge tone="blue"><Trophy className="size-3.5" aria-hidden="true" />{t(`contests.mode.${contest.mode}`)}</Badge>
          <Badge tone="neutral">{t(`contests.runKind.${run.runKind}`)}</Badge>
          <RegistrationBadge item={item} />
        </div>
        <h2 className="line-clamp-2 text-base font-semibold leading-6 text-[var(--oj-ink)]">{runTitle}</h2>
      </div>
      <div className="mt-3 space-y-2">
        <div className="flex items-start gap-2 text-sm text-[var(--oj-ink-muted)]">
          <CalendarClock className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          <span className="line-clamp-1">{formatDateTime(run.startAt)} - {formatDateTime(run.endAt)}</span>
        </div>
        <div className="flex items-start gap-2 text-sm text-[var(--oj-ink-muted)]">
          <Clock3 className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          <span className="line-clamp-1">{t(`contests.registrationAccess.${run.registrationAccess}`)} · {run.approvalRequired ? t("contests.approvalRequiredShort") : t("contests.noApprovalRequiredShort")}</span>
        </div>
        {(run.registrationStartAt || run.registrationEndAt) ? (
          <div className="line-clamp-1 text-sm tabular-nums text-[var(--oj-ink-muted)]">
            {t("contests.registrationWindow")}: {run.registrationStartAt ? formatDateTime(run.registrationStartAt) : "--"} - {run.registrationEndAt ? formatDateTime(run.registrationEndAt) : "--"}
          </div>
        ) : null}
        {approved && !item.canSubmit && isBeforeStart(run.startAt) ? (
          <div className="line-clamp-1 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-1.5 text-sm text-[var(--oj-ink-muted)]">
            {t("contests.runLockedBeforeStart")}
          </div>
        ) : null}
        {ended && item.canViewScoreboard ? (
          <div className="line-clamp-1 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-1.5 text-sm text-[var(--oj-ink-muted)]">
            {t("contests.postContestScoreboardHint")}
          </div>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-[var(--oj-ink-muted)]">
          <span>{run.maxParticipants ? t("contests.maxParticipantsValue", { count: run.maxParticipants }) : t("contests.noCapacityLimit")}</span>
          <span className="font-medium text-[var(--oj-primary)]">{t("contests.openContestDetail")}</span>
          {rejected ? <Badge tone="red">{t("contests.registrationRejected")}</Badge> : null}
        </div>
      </div>
    </Link>
  );
}

function isEnded(endAt: string) {
  return Date.now() >= new Date(endAt).getTime();
}

function isBeforeStart(startAt: string) {
  return Date.now() < new Date(startAt).getTime();
}

function RegistrationBadge({ item }: { item: ContestOpenRunResponse }) {
  const { t } = useI18n();
  const hasRegistrationWindow = Boolean(item.run.registrationStartAt || item.run.registrationEndAt);
  const status = item.registration?.status;
  if (status === "APPROVED") return <Badge tone="green"><CheckCircle2 className="size-4" aria-hidden="true" />{t("contests.registrationStatus.APPROVED")}</Badge>;
  if (status === "PENDING") return <Badge tone="amber">{t("contests.registrationStatus.PENDING")}</Badge>;
  if (status === "INVITED") return <Badge tone="amber">{t("contests.registrationStatus.INVITED")}</Badge>;
  if (status === "REJECTED") return <Badge tone="red">{t("contests.registrationStatus.REJECTED")}</Badge>;
  if (status === "DECLINED") return <Badge tone="red">{t("contests.registrationStatus.DECLINED")}</Badge>;
  if (isBeforeStart(item.run.startAt)) return <Badge tone="neutral">{t("contests.runNotStarted")}</Badge>;
  if (item.full) return <Badge tone="neutral">{t("contests.registrationFull")}</Badge>;
  if (item.canRegister) return <Badge tone="blue">{hasRegistrationWindow ? t("contests.openForRegistration") : t("contests.openForJoin")}</Badge>;
  return <Badge tone="neutral">{t("contests.runAccessRestrictedShort")}</Badge>;
}
