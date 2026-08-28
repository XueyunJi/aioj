import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Eye } from "lucide-react";
import {
  api,
  type AdminContestAiConversationSummary,
  type AdminContestAiAssistanceSummary,
  type ContestResponse,
  type EntityId
} from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { EmptyState, ErrorPanel, LoadingPanel, SidePanel, TableShell, inputClass, selectClass } from "../components/Common";
import { MarkdownView } from "../components/MarkdownView";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { formatDateTime, shortId } from "../lib/format";
import { readableCaughtError } from "../lib/readableError";

export function ContestAiUsagePanel({
  contest,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  onOpenChange: (open: boolean) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const open = Boolean(contest);
  const [runFilter, setRunFilter] = React.useState<EntityId | "">("");
  const [keyword, setKeyword] = React.useState("");
  const [detailTarget, setDetailTarget] = React.useState<AdminContestAiAssistanceSummary | null>(null);

  React.useEffect(() => {
    if (!contest) return;
    setRunFilter("");
    setKeyword("");
    setDetailTarget(null);
  }, [contest?.id]);

  const runsQuery = useQuery({
    queryKey: ["admin-contest-runs", contest?.id, "ai-usage-selector"],
    queryFn: () => api.contestRuns(contest!.id, { page: 1, pageSize: 50 }),
    enabled: open
  });

  const usageQuery = useQuery({
    queryKey: ["admin-contest-ai-assistance-statistics", contest?.id, runFilter],
    queryFn: async () => {
      try {
        return await api.contestAiAssistanceStatistics(contest!.id, runFilter || null);
      } catch (caught) {
        toast.error(readableCaughtError(caught, locale, t("contests.aiUsage.loadFailed")));
        throw caught;
      }
    },
    enabled: open
  });

  const runs = runsQuery.data?.records ?? [];
  const trimmedKeyword = keyword.trim().toLowerCase();
  const rows = (usageQuery.data ?? []).filter((row) => {
    if (!trimmedKeyword) return true;
    return `${row.account} ${row.displayName}`.toLowerCase().includes(trimmedKeyword);
  });

  return (
    <>
      <SidePanel
        wide
        open={open}
        onOpenChange={onOpenChange}
        title={contest?.title ?? t("contests.aiUsage.title")}
        description={t("contests.aiUsage.copy")}
        footer={(
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
          </div>
        )}
      >
        <div className="min-w-0 space-y-4">
          <div className="grid gap-3 md:grid-cols-2">
            <select className={selectClass} value={runFilter} onChange={(event) => setRunFilter(event.target.value)}>
              <option value="">{t("contests.allRuns")}</option>
              {runs.map((run) => (
                <option key={run.id} value={run.id}>{run.title}</option>
              ))}
            </select>
            <input
              className={inputClass}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder={t("contests.aiUsage.searchPlaceholder")}
            />
          </div>

          {usageQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : usageQuery.isError ? (
            <ErrorPanel title={t("contests.aiUsage.loadFailed")} action={<Button variant="outline" onClick={() => void usageQuery.refetch()}>{t("common.refresh")}</Button>} />
          ) : rows.length === 0 ? (
            <EmptyState title={t("contests.aiUsage.emptyTitle")} description={t("contests.aiUsage.emptyDescription")} />
          ) : (
            <TableShell>
              <table className="w-full min-w-[920px] table-fixed text-sm">
                <colgroup>
                  <col className="w-[18%]" />
                  <col className="w-[8%]" />
                  <col className="w-[9%]" />
                  <col className="w-[9%]" />
                  <col className="w-[8%]" />
                  <col className="w-[8%]" />
                  <col className="w-[14%]" />
                  <col className="w-[15%]" />
                  <col className="w-[11%]" />
                </colgroup>
                <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                  <tr>
                    <th className="px-2 py-2 text-left whitespace-nowrap">{t("contests.aiUsage.student")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.turnCount")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.inputTokens")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.outputTokens")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.conversationCount")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.interceptedCount")}</th>
                    <th className="px-2 py-2 text-left whitespace-nowrap">{t("contests.aiUsage.dataSource")}</th>
                    <th className="px-2 py-2 text-left whitespace-nowrap">{t("contests.aiUsage.lastUsedAt")}</th>
                    <th className="px-2 py-2 text-right whitespace-nowrap">{t("contests.aiUsage.actions")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--oj-border-soft)]">
                  {rows.map((row) => (
                    <tr key={row.userId} className="hover:bg-[var(--oj-surface-muted)]">
                      <td className="min-w-0 overflow-hidden px-2 py-2">
                        <span className="block truncate font-medium text-[var(--oj-ink)]" title={row.displayName || row.account || `#${shortId(row.userId)}`}>{row.displayName || row.account || `#${shortId(row.userId)}`}</span>
                        <span className="block truncate text-xs tabular-nums text-[var(--oj-ink-muted)]" title={row.account || `#${shortId(row.userId)}`}>{row.account || `#${shortId(row.userId)}`}</span>
                      </td>
                      <td className="overflow-hidden px-2 py-2 text-right tabular-nums whitespace-nowrap">{row.turnCount}</td>
                      <td className="overflow-hidden px-2 py-2 text-right tabular-nums whitespace-nowrap">{row.promptTokens}</td>
                      <td className="overflow-hidden px-2 py-2 text-right tabular-nums whitespace-nowrap">{row.completionTokens}</td>
                      <td className="overflow-hidden px-2 py-2 text-right tabular-nums whitespace-nowrap">{row.conversationCount}</td>
                      <td className="overflow-hidden px-2 py-2 text-right whitespace-nowrap">
                        {row.interceptedCount > 0 ? <Badge tone="red">{row.interceptedCount}</Badge> : <span className="tabular-nums">0</span>}
                      </td>
                      <td className="min-w-0 overflow-hidden px-2 py-2">
                        <div className="flex min-w-0 flex-wrap gap-1">
                          <AssistanceSourceBadge source={row.dataSource} />
                          {row.tokenAccountingStatus === "PARTIAL" ? <Badge tone="amber">{t("contests.aiUsage.tokensPartial")}</Badge> : null}
                          {row.tokenAccountingStatus === "ESTIMATED" ? <Badge tone="amber">{t("contests.aiUsage.tokensEstimated")}</Badge> : null}
                        </div>
                      </td>
                      <td className="overflow-hidden px-2 py-2 text-xs tabular-nums text-[var(--oj-ink-muted)] whitespace-nowrap">{row.lastUsedAt ? formatDateTime(row.lastUsedAt) : "--"}</td>
                      <td className="overflow-hidden px-2 py-2 text-right">
                        <Button size="sm" variant="outline" className="max-w-full gap-1 px-2" onClick={() => setDetailTarget(row)} aria-label={t("contests.aiUsage.view")}>
                          <Eye className="size-4" aria-hidden="true" />
                          <span className="truncate">{t("contests.aiUsage.view")}</span>
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableShell>
          )}
        </div>
      </SidePanel>

      {detailTarget ? (
        <AiUsageDetailDialog
          contest={contest}
          student={detailTarget}
          runFilter={runFilter}
          onOpenChange={(next) => !next && setDetailTarget(null)}
        />
      ) : null}
    </>
  );
}

function AiUsageDetailDialog({
  contest,
  student,
  runFilter,
  onOpenChange
}: {
  contest: ContestResponse | undefined;
  student: AdminContestAiAssistanceSummary;
  runFilter: EntityId | "";
  onOpenChange: (open: boolean) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [selectedConversationId, setSelectedConversationId] = React.useState<string>("");

  const conversationsQuery = useQuery({
    queryKey: ["admin-contest-ai-assistance-conversations", contest?.id, student.userId, runFilter],
    queryFn: async () => {
      try {
        return await api.contestAiAssistanceConversations(contest!.id, student.userId, runFilter || null);
      } catch (caught) {
        toast.error(readableCaughtError(caught, locale, t("contests.aiUsage.conversationsLoadFailed")));
        throw caught;
      }
    },
    enabled: Boolean(contest)
  });

  const messagesQuery = useQuery({
    queryKey: ["admin-contest-ai-assistance-messages", contest?.id, student.userId, selectedConversationId, runFilter],
    queryFn: async () => {
      try {
        return await api.contestAiAssistanceMessages(contest!.id, student.userId, selectedConversationId, runFilter || null);
      } catch (caught) {
        toast.error(readableCaughtError(caught, locale, t("contests.aiUsage.messagesLoadFailed")));
        throw caught;
      }
    },
    enabled: Boolean(contest && selectedConversationId)
  });

  const conversations = conversationsQuery.data ?? [];
  const selectedConversation = conversations.find((item) => item.conversationId === selectedConversationId) ?? null;

  return (
    <SidePanel
      open
      onOpenChange={onOpenChange}
      presentation="workspace"
      workspaceSize="lg"
      title={t("contests.aiUsage.detailTitle")}
      description={`${student.displayName || student.account || `#${shortId(student.userId)}`} · ${student.account ? `@${student.account}` : `#${shortId(student.userId)}`}`}
      footer={(
        <div className="flex justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>
        </div>
      )}
    >
      {!selectedConversationId ? (
        conversationsQuery.isLoading ? (
          <LoadingPanel label={t("common.loading")} />
        ) : conversationsQuery.isError ? (
          <ErrorPanel title={t("contests.aiUsage.conversationsLoadFailed")} action={<Button variant="outline" onClick={() => void conversationsQuery.refetch()}>{t("common.refresh")}</Button>} />
        ) : conversations.length === 0 ? (
          <EmptyState title={t("contests.aiUsage.noConversations")} description={t("contests.aiUsage.noConversationsCopy")} />
        ) : (
          <div className="space-y-3">
            {conversations.map((conversation) => (
              <ConversationRow
                key={conversation.conversationId}
                conversation={conversation}
                onOpen={() => setSelectedConversationId(conversation.conversationId)}
              />
            ))}
          </div>
        )
      ) : (
        <div className="flex h-full min-h-0 flex-col gap-3">
          <div className="flex min-w-0 flex-col items-start gap-2 sm:flex-row sm:items-center">
            <Button variant="outline" size="sm" onClick={() => setSelectedConversationId("")}>
              <ArrowLeft className="size-4" aria-hidden="true" />
              {t("contests.aiUsage.backToConversations")}
            </Button>
            <span className="truncate text-sm font-medium text-[var(--oj-ink)]">
              {selectedConversation?.title || `#${shortId(selectedConversationId)}`}
            </span>
            {selectedConversation?.problemId ? (
              <Badge tone="neutral" className="max-w-full truncate">
                {t("contests.aiUsage.problem")}: {selectedConversation.problemTitle || `#${shortId(selectedConversation.problemId)}`}
                {selectedConversation.problemTitle ? ` · #${shortId(selectedConversation.problemId)}` : ""}
              </Badge>
            ) : null}
          </div>
          {messagesQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : messagesQuery.isError ? (
            <ErrorPanel title={t("contests.aiUsage.messagesLoadFailed")} action={<Button variant="outline" onClick={() => void messagesQuery.refetch()}>{t("common.refresh")}</Button>} />
          ) : (messagesQuery.data ?? []).length === 0 ? (
            <EmptyState title={t("contests.aiUsage.noMessages")} description="" />
          ) : (
            <div className="space-y-3">
              {(messagesQuery.data ?? []).map((message) => (
                <article
                  key={message.id}
                  className={`rounded-xl border p-3 ${message.role === "user" ? "border-blue-200 bg-blue-50/60" : "border-[var(--oj-border-soft)] bg-white"}`}
                >
                  <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-[var(--oj-ink-muted)]">
                    <Badge tone={message.role === "user" ? "blue" : "green"}>
                      {message.role === "user" ? t("contests.aiUsage.roleUser") : message.role === "assistant" ? t("contests.aiUsage.roleAssistant") : message.role}
                    </Badge>
                    {message.status && message.status !== "COMPLETED" ? <Badge tone="amber">{message.status}</Badge> : null}
                    <span className="tabular-nums">{message.createdAt ? formatDateTime(message.createdAt) : "--"}</span>
                  </div>
                  {message.role === "assistant" ? (
                    <MarkdownView content={message.content || ""} />
                  ) : (
                    <p className="whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink)]">{message.content}</p>
                  )}
                </article>
              ))}
            </div>
          )}
        </div>
      )}
    </SidePanel>
  );
}

function AssistanceSourceBadge({ source }: { source: string }) {
  const { t } = useI18n();
  if (source === "HISTORICAL_SNAPSHOT") {
    return <Badge tone="amber">{t("contests.aiUsage.historicalSnapshot")}</Badge>;
  }
  if (source === "MIXED") {
    return <Badge tone="amber">{t("contests.aiUsage.mixedSource")}</Badge>;
  }
  return <Badge tone="green">{t("contests.aiUsage.liveLedger")}</Badge>;
}

function ConversationRow({
  conversation,
  onOpen
}: {
  conversation: AdminContestAiConversationSummary;
  onOpen: () => void;
}) {
  const { t } = useI18n();
  return (
    <button
      type="button"
      onClick={onOpen}
      className="block w-full rounded-xl border border-[var(--oj-border-soft)] bg-white p-3 text-left transition hover:border-[var(--oj-primary)]"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="truncate text-sm font-medium text-[var(--oj-ink)]">
          {conversation.title || `#${shortId(conversation.conversationId)}`}
        </span>
        {conversation.mode ? <Badge tone="neutral">{conversation.mode}</Badge> : null}
        <Badge tone="blue">{t("contests.aiUsage.messageCount", { count: conversation.messageCount })}</Badge>
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-2 text-xs tabular-nums text-[var(--oj-ink-muted)]">
        <span>{conversation.lastMessageAt ? formatDateTime(conversation.lastMessageAt) : "--"}</span>
        {conversation.problemId ? (
          <Badge tone="neutral" className="max-w-full truncate">
            {t("contests.aiUsage.problem")}: {conversation.problemTitle || `#${shortId(conversation.problemId)}`}
            {conversation.problemTitle ? ` · #${shortId(conversation.problemId)}` : ""}
          </Badge>
        ) : null}
      </div>
    </button>
  );
}
