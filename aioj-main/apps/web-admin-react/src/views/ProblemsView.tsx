import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, ArchiveRestore, Edit, Plus, RotateCw, Trash2 } from "lucide-react";
import { ApiError, api, type Difficulty, type EntityId, type ProblemListSort, type ProblemResponse } from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { ConfirmDialog, EmptyState, ErrorPanel, LoadingPanel, PageHeader, TableShell, inputClass, selectClass } from "../components/Common";
import { ProblemEditorPanel } from "../components/ProblemEditorPanel";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { difficultyTone, formatBytes, formatDateTime, shortId } from "../lib/format";

const DIFFICULTIES: Difficulty[] = ["EASY", "MEDIUM", "HARD", "CHALLENGE"];
const TIME_LIMIT_LANGUAGES = ["cpp", "python", "java"] as const;
const PAGE_SIZE = 20;
type ProblemLifecycleStatus = "ACTIVE" | "ARCHIVED" | "ALL";
type ProblemVisibilityFilter = "" | "PUBLIC" | "PRIVATE";
type ProblemLifecycleTarget = { action: "archive" | "restore" | "delete"; problem: ProblemResponse };

export function ProblemsView() {
  const { t } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = React.useState("");
  const [difficulty, setDifficulty] = React.useState<Difficulty | "">("");
  const [tag, setTag] = React.useState("");
  const [lifecycleStatus, setLifecycleStatus] = React.useState<ProblemLifecycleStatus>("ALL");
  const [visibilityFilter, setVisibilityFilter] = React.useState<ProblemVisibilityFilter>("");
  const [sort, setSort] = React.useState<ProblemListSort>("NEWEST");
  const [page, setPage] = React.useState(1);
  const [editorOpen, setEditorOpen] = React.useState(false);
  const [editingProblem, setEditingProblem] = React.useState<ProblemResponse | null>(null);
  const [detailLoadingId, setDetailLoadingId] = React.useState<EntityId | null>(null);
  const [lifecycleTarget, setLifecycleTarget] = React.useState<ProblemLifecycleTarget | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    setPage(1);
  }, [keyword, difficulty, tag, lifecycleStatus, visibilityFilter, sort]);

  const problemsQuery = useQuery({
    queryKey: ["admin-problems", page, keyword, difficulty, tag, lifecycleStatus, visibilityFilter, sort],
    queryFn: () => api.problems({
      page,
      pageSize: PAGE_SIZE,
      keyword: keyword.trim() || undefined,
      difficulty,
      tag: tag.trim() || undefined,
      status: lifecycleStatus,
      visibility: visibilityFilter || "ALL",
      sort
    })
  });

  const lifecycleMutation = useMutation({
    mutationFn: async (target: ProblemLifecycleTarget) => {
      if (target.action === "archive") {
        await api.archiveProblem(target.problem.id);
      } else if (target.action === "restore") {
        await api.restoreProblem(target.problem.id);
      } else {
        await api.deleteProblem(target.problem.id);
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-problems"] });
    }
  });

  const records = problemsQuery.data?.records ?? [];
  const total = problemsQuery.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  function openCreate() {
    setError(null);
    setEditingProblem(null);
    setEditorOpen(true);
  }

  async function openEdit(problem: ProblemResponse) {
    setError(null);
    setEditingProblem(problem);
    setEditorOpen(true);
    setDetailLoadingId(problem.id);
    try {
      setEditingProblem(await api.problem(problem.id, { staffView: true }));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.userMessage : t("problems.loadOneFailed"));
    } finally {
      setDetailLoadingId(null);
    }
  }

  async function handleSaved() {
    await queryClient.invalidateQueries({ queryKey: ["admin-problems"] });
  }

  return (
    <div className="mx-auto flex max-w-[1540px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("nav.problems")}
        description={t("problems.drawerSubtitle")}
        actions={(
          <>
            <Button variant="outline" disabled={problemsQuery.isFetching} onClick={() => void problemsQuery.refetch()}>
              <RotateCw className="size-4" aria-hidden="true" />
              {t("common.refresh")}
            </Button>
            <Button onClick={openCreate}>
              <Plus className="size-4" aria-hidden="true" />
              {t("problems.create")}
            </Button>
          </>
        )}
      />

      <section className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <input
          className={`${inputClass} w-full sm:w-72`}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder={t("problems.searchAdminPlaceholder")}
        />
        <select className={`${selectClass} w-full sm:w-44`} value={difficulty} onChange={(event) => setDifficulty(event.target.value as Difficulty | "")}>
          <option value="">{t("problems.allDifficulties")}</option>
          {DIFFICULTIES.map((item) => <option key={item} value={item}>{t(`difficulty.${item}`)}</option>)}
        </select>
        <select className={`${selectClass} w-full sm:w-44`} value={lifecycleStatus} onChange={(event) => setLifecycleStatus(event.target.value as ProblemLifecycleStatus)}>
          <option value="ALL">{t("common.all")}</option>
          <option value="ACTIVE">{t("common.active")}</option>
          <option value="ARCHIVED">{t("common.archived")}</option>
        </select>
        <select className={`${selectClass} w-full sm:w-48`} value={visibilityFilter} onChange={(event) => setVisibilityFilter(event.target.value as ProblemVisibilityFilter)} aria-label={t("problems.visibilityLabel")}>
          <option value="">{t("problems.allVisibility")}</option>
          <option value="PUBLIC">{t("problems.visibilityPublic")}</option>
          <option value="PRIVATE">{t("problems.visibilityPrivateFilter")}</option>
        </select>
        <select className={`${selectClass} w-full sm:w-48`} value={sort} onChange={(event) => setSort(event.target.value as ProblemListSort)} aria-label={t("problems.sortLabel")}>
          <option value="NEWEST">{t("problems.sortNewest")}</option>
          <option value="OLDEST">{t("problems.sortOldest")}</option>
          <option value="DIFFICULTY_ASC">{t("problems.sortDifficultyAsc")}</option>
          <option value="DIFFICULTY_DESC">{t("problems.sortDifficultyDesc")}</option>
        </select>
        <input
          className={`${inputClass} w-full sm:w-48`}
          value={tag}
          onChange={(event) => setTag(event.target.value)}
          placeholder={t("problems.tagPlaceholder")}
        />
        <Button
          className="w-fit"
          variant="outline"
          onClick={() => {
            setKeyword("");
            setDifficulty("");
            setTag("");
            setLifecycleStatus("ALL");
            setVisibilityFilter("");
            setSort("NEWEST");
            setPage(1);
          }}
        >
          {t("problems.resetFilters")}
        </Button>
      </section>

      {error ? <ErrorPanel title={error} /> : null}

      {problemsQuery.isLoading ? (
        <LoadingPanel label={t("problems.loading")} />
      ) : problemsQuery.isError ? (
        <ErrorPanel title={t("problems.loadFailed")} action={<Button variant="outline" onClick={() => void problemsQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : records.length ? (
        <>
          <TableShell>
            <table className="w-full min-w-[980px] text-sm">
              <colgroup>
                <col className="w-[14%]" />
                <col className="w-[28%]" />
                <col className="w-[10%]" />
                <col className="w-[18%]" />
                <col className="w-[14%]" />
                <col className="w-[16%]" />
              </colgroup>
              <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                <tr>
                  <th className="px-4 py-3 text-left">{t("common.id")}</th>
                  <th className="px-4 py-3 text-left">{t("common.title")}</th>
                  <th className="px-4 py-3 text-left">{t("common.difficulty")}</th>
                  <th className="px-4 py-3 text-left">{t("common.tags")}</th>
                  <th className="px-4 py-3 text-left">{t("problems.limits")}</th>
                  <th className="px-4 py-3 text-center">{t("common.actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[var(--oj-border-soft)]">
                {records.map((problem) => (
                  <tr key={problem.id} className="align-middle">
                    <td className="px-4 py-4 font-medium tabular-nums text-[var(--oj-primary)]">#{shortId(problem.id)}</td>
                    <td className="px-4 py-4">
                      <div className="min-w-0">
                        <strong className="block truncate text-[var(--oj-ink)]">{problem.title}</strong>
                        <span className="mt-1 block text-xs tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(problem.createdAt)}</span>
                      </div>
                    </td>
                    <td className="px-4 py-4">
                      <div className="flex flex-wrap gap-1.5">
                        <Badge tone={difficultyTone(problem.difficulty)}>{t(`difficulty.${problem.difficulty}`)}</Badge>
                        {problem.archivedAt ? <Badge tone="neutral">{t("common.archived")}</Badge> : null}
                        {problem.visibility === "PRIVATE" ? <Badge tone="red">{t("problems.visibilityPrivate")}</Badge> : null}
                      </div>
                    </td>
                    <td className="px-4 py-4">
                      <div className="flex flex-wrap gap-1.5">
                        {problem.tags.length ? problem.tags.slice(0, 5).map((item) => <Badge key={item} tone="neutral">{item}</Badge>) : <span className="text-[var(--oj-ink-muted)]">{t("problems.noTags")}</span>}
                      </div>
                    </td>
                    <td className="px-4 py-4 text-xs tabular-nums text-[var(--oj-ink-muted)]">
                      <div>{problem.timeLimitMillis} ms</div>
                      {languageEffectiveLimits(problem, t).length ? (
                        <div className="mt-1 flex flex-wrap gap-x-2 gap-y-1 text-[11px] leading-5">
                          {languageEffectiveLimits(problem, t).map((item) => (
                            <span key={item.language}>{item.label} {item.timeLimitMillis} ms</span>
                          ))}
                        </div>
                      ) : null}
                      <div>{formatBytes(problem.memoryLimitKb * 1024)}</div>
                    </td>
                    <td className="px-4 py-4">
                      <div className="flex flex-nowrap justify-center gap-2">
                        <Button size="sm" variant="outline" className="min-w-[72px] [word-break:keep-all]" disabled={detailLoadingId === problem.id} onClick={() => void openEdit(problem)}>
                          <Edit className="size-4" aria-hidden="true" />
                          {detailLoadingId === problem.id ? t("common.loading") : t("common.edit")}
                        </Button>
                        {problem.archivedAt ? (
                          <>
                            <Button size="sm" variant="outline" className="min-w-[72px] [word-break:keep-all]" onClick={() => setLifecycleTarget({ action: "restore", problem })}>
                              <ArchiveRestore className="size-4" aria-hidden="true" />
                              {t("common.restore")}
                            </Button>
                            <Button size="sm" variant="outline" className="min-w-[72px] text-red-700 [word-break:keep-all] hover:bg-red-50" onClick={() => setLifecycleTarget({ action: "delete", problem })}>
                              <Trash2 className="size-4" aria-hidden="true" />
                              {t("common.delete")}
                            </Button>
                          </>
                        ) : (
                          <Button size="sm" variant="outline" className="min-w-[72px] [word-break:keep-all]" onClick={() => setLifecycleTarget({ action: "archive", problem })}>
                            <Archive className="size-4" aria-hidden="true" />
                            {t("common.archive")}
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
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
          title={t("problems.adminEmpty")}
          description={t("problems.noResultsDescription")}
          actionLabel={t("problems.create")}
          onAction={openCreate}
        />
      )}

      <ProblemEditorPanel
        open={editorOpen}
        onOpenChange={setEditorOpen}
        problem={editingProblem}
        onSaved={handleSaved}
      />

      <ConfirmDialog
        open={Boolean(lifecycleTarget)}
        onOpenChange={(open) => !open && setLifecycleTarget(null)}
        title={problemLifecycleTitle(lifecycleTarget, t)}
        description={problemLifecycleDescription(lifecycleTarget, t)}
        cancelLabel={t("common.cancel")}
        confirmLabel={problemLifecycleLabel(lifecycleTarget, t)}
        onConfirm={async () => {
          if (!lifecycleTarget) return;
          const target = lifecycleTarget;
          try {
            await lifecycleMutation.mutateAsync(target);
            toast.success(t(
              target.action === "archive"
                ? "problems.archivedMessage"
                : target.action === "restore"
                  ? "problems.restoredMessage"
                  : "problems.deletedMessage"
            ));
          } catch (caught) {
            toast.error(caught instanceof ApiError ? caught.userMessage : t("common.errorFallback"));
          } finally {
            setLifecycleTarget(null);
          }
        }}
      />
    </div>
  );
}

function problemLifecycleTitle(target: ProblemLifecycleTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  if (target.action === "archive") return t("problems.archiveConfirm");
  if (target.action === "restore") return t("problems.restoreConfirm");
  return t("problems.deleteConfirm");
}

function problemLifecycleDescription(target: ProblemLifecycleTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  const base = `${target.problem.title} (#${target.problem.id})`;
  return target.action === "delete" ? `${base}\n${t("common.deleteArchivedOnly")}` : base;
}

function problemLifecycleLabel(target: ProblemLifecycleTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  if (target.action === "archive") return t("common.archive");
  if (target.action === "restore") return t("common.restore");
  return t("common.delete");
}

function languageEffectiveLimits(problem: ProblemResponse, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return TIME_LIMIT_LANGUAGES.flatMap((language) => {
    const multiplier = normalizeMultiplier(problem.languageTimeLimitMultipliers?.[language]);
    if (multiplier <= 1) return [];
    return [{
      language,
      label: t(`problems.languages.${language}`),
      timeLimitMillis: Math.ceil(problem.timeLimitMillis * multiplier)
    }];
  });
}

function normalizeMultiplier(value?: number | null) {
  return typeof value === "number" && Number.isFinite(value) ? value : 1;
}
