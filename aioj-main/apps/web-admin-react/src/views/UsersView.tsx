import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, ArchiveRestore, Ban, Bot, CheckCircle2, Edit, KeyRound, Plus, RotateCw, Trash2, Upload, UsersRound } from "lucide-react";
import { ApiError, api, type AdminUserBatchAction, type AdminUserBatchCandidate, type AdminUserBatchPreviewItem, type AdminUserLifecycle, type AdminUserResponse, type Role, type RoleResponse } from "@aioj/api-client";
import { Badge, Button, shouldToggleRowSelection } from "@aioj/ui-react";
import { ConfirmDialog, EmptyState, ErrorPanel, Field, LoadingPanel, PageHeader, PaginationRow, SidePanel, TableShell, inputClass, selectClass, textareaClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { readableCaughtError } from "../lib/readableError";

type BaseRole = Extract<Role, "STUDENT" | "TEACHER">;
type Translate = (key: string, params?: Record<string, string | number>, fallback?: string) => string;
const USER_PAGE_SIZE_OPTIONS = [20, 50, 100];

export function UsersView() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = React.useState("");
  const [role, setRole] = React.useState<Role | "">("");
  const [enabled, setEnabled] = React.useState<"" | "true" | "false">("");
  const [lifecycle, setLifecycle] = React.useState<AdminUserLifecycle>("ACTIVE");
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(50);
  const [selectedIds, setSelectedIds] = React.useState<Set<string>>(new Set());
  const [editingUser, setEditingUser] = React.useState<AdminUserResponse | null>(null);
  const [editorOpen, setEditorOpen] = React.useState(false);
  const [batchOpen, setBatchOpen] = React.useState(false);
  const [confirmTarget, setConfirmTarget] = React.useState<{ user: AdminUserResponse; enabled: boolean } | null>(null);
  const [singleConfirm, setSingleConfirm] = React.useState<{ user: AdminUserResponse; action: AdminUserBatchAction; title: string; description: string; confirmLabel: string } | null>(null);
  const [bulkConfirm, setBulkConfirm] = React.useState<{ action: AdminUserBatchAction; title: string; description: string; confirmLabel: string } | null>(null);
  const [resetTargets, setResetTargets] = React.useState<AdminUserResponse[] | null>(null);
  const listTopRef = React.useRef<HTMLDivElement | null>(null);
  const filtersReadyRef = React.useRef(false);

  const rolesQuery = useQuery({
    queryKey: ["admin-roles"],
    queryFn: () => api.roles()
  });

  const usersQuery = useQuery({
    queryKey: ["admin-users", keyword, role, enabled, lifecycle, page, pageSize],
    queryFn: () => api.users({
      page,
      pageSize,
      keyword: keyword.trim() || undefined,
      role,
      enabled: enabled === "" ? "" : enabled === "true",
      lifecycle
    })
  });

  const toggleMutation = useMutation({
    mutationFn: async ({ user, nextEnabled }: { user: AdminUserResponse; nextEnabled: boolean }) => {
      await api.updateUser(user.userId, {
        displayName: user.displayName,
        email: user.email,
        roles: normalizeRoles(user.roles),
        enabled: nextEnabled,
        passwordResetRequired: user.passwordResetRequired
      });
    },
    onSuccess: async (_data, variables) => {
      toast.success(t(variables.nextEnabled ? "adminUsers.enabledMessage" : "adminUsers.disabledMessage"));
      await queryClient.invalidateQueries({ queryKey: ["admin-users"] });
    },
    onError: (caught) => {
      toast.error(readableCaughtError(caught, locale, t("adminUsers.saveFailed")));
    }
  });

  const users = usersQuery.data?.records ?? [];
  const total = usersQuery.data?.total ?? 0;
  const selectedUsers = users.filter((user) => selectedIds.has(user.userId));

  React.useEffect(() => {
    setSelectedIds((current) => new Set([...current].filter((id) => users.some((user) => user.userId === id))));
  }, [users]);

  React.useEffect(() => {
    if (!filtersReadyRef.current) {
      filtersReadyRef.current = true;
      return;
    }
    setPage(1);
    setSelectedIds(new Set());
  }, [keyword, role, enabled, lifecycle, pageSize]);

  React.useEffect(() => {
    setSelectedIds(new Set());
  }, [page]);

  React.useEffect(() => {
    if (!usersQuery.data) return;
    const totalPages = Math.max(1, Math.ceil(usersQuery.data.total / Math.max(1, pageSize)));
    if (page > totalPages) setPage(totalPages);
  }, [page, pageSize, usersQuery.data]);

  const batchActionMutation = useMutation({
    mutationFn: ({ userIds, ...payload }: { userIds?: string[]; action: AdminUserBatchAction; password?: { mode: "FIXED" | "ACCOUNT_SUFFIX"; fixedPassword?: string; accountSuffix?: string }; passwordResetRequired?: boolean }) =>
      api.batchUserAction({ userIds: userIds ?? [...selectedIds], ...payload }),
    onSuccess: async (response) => {
      toast.success(t("adminUsers.batchActionSummary", { total: response.requested, ok: response.succeeded, failed: response.failed }));
      if (response.failed > 0) {
        const failed = response.results.filter((item) => item.status !== "OK").map((item) => `${item.account ?? item.userId}: ${item.message ?? ""}`).join("；");
        toast.error(failed || t("adminUsers.batchActionPartialFailed"));
      } else {
        setSelectedIds(new Set());
      }
      await queryClient.invalidateQueries({ queryKey: ["admin-users"] });
    },
    onError: (caught) => {
      toast.error(readableCaughtError(caught, locale, t("adminUsers.batchActionFailed")));
    }
  });

  function toggleSelect(id: string, checked: boolean) {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  function selectAllVisible(checked: boolean) {
    setSelectedIds(checked ? new Set(users.map((user) => user.userId)) : new Set());
  }

  function confirmBulk(action: AdminUserBatchAction) {
    const count = selectedIds.size;
    const labels: Record<AdminUserBatchAction, { title: string; confirmLabel: string }> = {
      ENABLE: { title: t("adminUsers.bulkEnable"), confirmLabel: t("common.enable") },
      DISABLE: { title: t("adminUsers.bulkDisable"), confirmLabel: t("common.disable") },
      ARCHIVE: { title: t("adminUsers.bulkArchive"), confirmLabel: t("adminUsers.archive") },
      RESTORE: { title: t("adminUsers.bulkRestore"), confirmLabel: t("adminUsers.restore") },
      DELETE_ARCHIVED: { title: t("adminUsers.bulkDeleteArchived"), confirmLabel: t("adminUsers.deleteArchived") },
      RESET_PASSWORD: { title: t("adminUsers.bulkResetPassword"), confirmLabel: t("adminUsers.resetPassword") }
    };
    setBulkConfirm({
      action,
      title: labels[action].title,
      description: t("adminUsers.bulkConfirmDescription", { count }),
      confirmLabel: labels[action].confirmLabel
    });
  }

  function confirmSingle(user: AdminUserResponse, action: AdminUserBatchAction) {
    const labels: Record<AdminUserBatchAction, { title: string; description: string; confirmLabel: string }> = {
      ENABLE: { title: t("adminUsers.enable"), description: t("adminUsers.enableConfirm"), confirmLabel: t("common.enable") },
      DISABLE: { title: t("common.disable"), description: t("adminUsers.disableConfirm"), confirmLabel: t("common.disable") },
      ARCHIVE: { title: t("adminUsers.archive"), description: t("adminUsers.archiveUserConfirm"), confirmLabel: t("adminUsers.archive") },
      RESTORE: { title: t("adminUsers.restore"), description: t("adminUsers.restoreUserConfirm"), confirmLabel: t("adminUsers.restore") },
      DELETE_ARCHIVED: { title: t("adminUsers.deleteArchived"), description: t("adminUsers.deleteArchivedUserConfirm"), confirmLabel: t("adminUsers.deleteArchived") },
      RESET_PASSWORD: { title: t("adminUsers.resetPassword"), description: t("adminUsers.resetPasswordCopy", { count: 1 }), confirmLabel: t("adminUsers.resetPassword") }
    };
    setSingleConfirm({
      user,
      action,
      title: labels[action].title,
      description: `${labels[action].description}\n${user.account}`,
      confirmLabel: labels[action].confirmLabel
    });
  }

  function handlePageChange(nextPage: number) {
    if (nextPage === page || usersQuery.isFetching) return;
    setSelectedIds(new Set());
    setPage(nextPage);
    window.requestAnimationFrame(() => {
      listTopRef.current?.scrollIntoView({ block: "start" });
    });
  }

  function openCreate() {
    setEditingUser(null);
    setEditorOpen(true);
  }

  function openEdit(user: AdminUserResponse) {
    setEditingUser(user);
    setEditorOpen(true);
  }

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("nav.users")}
        description={t("adminUsers.adminPermissionCopy")}
        actions={(
          <>
            <Button type="button" variant="outline" disabled={usersQuery.isFetching} onClick={() => void usersQuery.refetch()}>
              <RotateCw className="size-4" aria-hidden="true" />
              {t("common.refresh")}
            </Button>
            <Button type="button" onClick={openCreate}>
              <Plus className="size-4" aria-hidden="true" />
              {t("adminUsers.create")}
            </Button>
            <Button type="button" onClick={() => setBatchOpen(true)}>
              <UsersRound className="size-4" aria-hidden="true" />
              {t("adminUsers.batchCreate")}
            </Button>
          </>
        )}
      />

      <section className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <input
          className={`${inputClass} w-full sm:w-64`}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder={t("adminUsers.search")}
        />
        <select className={`${selectClass} w-full sm:w-44`} value={role} onChange={(event) => setRole(event.target.value as Role | "")}>
          <option value="">{t("adminUsers.allRoles")}</option>
          {(rolesQuery.data ?? []).map((item) => <option key={item.role} value={item.role}>{roleLabel(item.role, t, item.label)}</option>)}
        </select>
        <select className={`${selectClass} w-full sm:w-44`} value={enabled} onChange={(event) => setEnabled(event.target.value as typeof enabled)}>
          <option value="">{t("adminUsers.allStatuses")}</option>
          <option value="true">{t("common.enabled")}</option>
          <option value="false">{t("common.disabled")}</option>
        </select>
        <select className={`${selectClass} w-full sm:w-44`} value={lifecycle} onChange={(event) => {
          setLifecycle(event.target.value as AdminUserLifecycle);
          setSelectedIds(new Set());
        }}>
          <option value="ACTIVE">{t("adminUsers.lifecycleActive")}</option>
          <option value="ARCHIVED">{t("adminUsers.lifecycleArchived")}</option>
        </select>
      </section>

      {selectedIds.size > 0 ? (
        <section className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-blue-200 bg-blue-50 p-3">
          <p className="text-sm font-medium text-blue-950">{t("adminUsers.selectedCount", { count: selectedIds.size })}</p>
          <div className="flex flex-wrap gap-2">
            {lifecycle === "ACTIVE" ? (
              <>
                <Button type="button" size="sm" variant="outline" disabled={batchActionMutation.isPending} onClick={() => confirmBulk("ENABLE")}>{t("adminUsers.bulkEnable")}</Button>
                <Button type="button" size="sm" variant="outline" disabled={batchActionMutation.isPending} onClick={() => confirmBulk("DISABLE")}>{t("adminUsers.bulkDisable")}</Button>
                <Button type="button" size="sm" variant="outline" disabled={batchActionMutation.isPending} onClick={() => confirmBulk("ARCHIVE")}>
                  <Archive className="size-4" aria-hidden="true" />
                  {t("adminUsers.bulkArchive")}
                </Button>
              </>
            ) : (
              <>
                <Button type="button" size="sm" variant="outline" disabled={batchActionMutation.isPending} onClick={() => confirmBulk("RESTORE")}>
                  <ArchiveRestore className="size-4" aria-hidden="true" />
                  {t("adminUsers.bulkRestore")}
                </Button>
                <Button type="button" size="sm" variant="outline" className="text-red-700 hover:bg-red-50" disabled={batchActionMutation.isPending} onClick={() => confirmBulk("DELETE_ARCHIVED")}>
                  <Trash2 className="size-4" aria-hidden="true" />
                  {t("adminUsers.bulkDeleteArchived")}
                </Button>
              </>
            )}
            {lifecycle === "ACTIVE" ? (
              <Button type="button" size="sm" variant="outline" disabled={batchActionMutation.isPending} onClick={() => setResetTargets(selectedUsers)}>
                <KeyRound className="size-4" aria-hidden="true" />
                {t("adminUsers.bulkResetPassword")}
              </Button>
            ) : null}
            <Button type="button" size="sm" variant="ghost" onClick={() => setSelectedIds(new Set())}>{t("common.cancel")}</Button>
          </div>
        </section>
      ) : null}

      {usersQuery.isLoading || rolesQuery.isLoading ? (
        <LoadingPanel label={t("common.loading")} />
      ) : usersQuery.isError || rolesQuery.isError ? (
        <ErrorPanel title={t("adminUsers.loadFailed")} action={<Button variant="outline" onClick={() => void Promise.all([usersQuery.refetch(), rolesQuery.refetch()])}>{t("common.refresh")}</Button>} />
      ) : users.length ? (
        <div ref={listTopRef} className="space-y-3">
          <TableShell>
            <table className="w-full min-w-[1160px] table-fixed text-sm">
            <colgroup>
              <col className="w-12" />
              <col className="w-[20%]" />
              <col className="w-[20%]" />
              <col className="w-[24%]" />
              <col className="w-[12%]" />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-44" />
            </colgroup>
            <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
              <tr>
                <th className="px-4 py-3 text-left">
                  <input
                    aria-label={t("adminUsers.selectAllVisible")}
                    type="checkbox"
                    checked={users.length > 0 && users.every((user) => selectedIds.has(user.userId))}
                    onChange={(event) => selectAllVisible(event.target.checked)}
                  />
                </th>
                <th className="px-4 py-3 text-left">{t("common.account")}</th>
                <th className="px-4 py-3 text-left">{t("common.displayName")}</th>
                <th className="px-4 py-3 text-left">{t("common.email")}</th>
                <th className="px-4 py-3 text-left">{t("common.roles")}</th>
                <th className="px-4 py-3 text-center">{t("common.status")}</th>
                <th className="px-4 py-3 text-center">{t("common.created")}</th>
                <th className="px-4 py-3 text-center">{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--oj-border-soft)]">
              {users.map((user) => (
                <tr
                  key={user.userId}
                  className={`align-middle transition-colors ${selectedIds.has(user.userId) ? "bg-blue-50" : "hover:bg-[var(--oj-surface-muted)]"}`}
                  onClick={(event) => {
                    if (shouldToggleRowSelection(event)) {
                      toggleSelect(user.userId, !selectedIds.has(user.userId));
                    }
                  }}
                >
                  <td className="px-4 py-3">
                    <input
                      aria-label={t("adminUsers.selectUser", { account: user.account })}
                      type="checkbox"
                      checked={selectedIds.has(user.userId)}
                      onChange={(event) => toggleSelect(user.userId, event.target.checked)}
                    />
                  </td>
                  <td className="px-4 py-3 font-medium text-[var(--oj-ink)]">
                    <TruncatedText value={user.account} />
                  </td>
                  <td className="px-4 py-3 text-[var(--oj-ink)]">
                    <TruncatedText value={user.displayName} />
                  </td>
                  <td className="px-4 py-3 text-[var(--oj-ink-muted)]">
                    <TruncatedText value={user.email || "--"} />
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-1.5">
                      {user.roles.map((item) => <Badge key={item} tone={item === "ADMIN" ? "red" : item === "TEACHER" ? "blue" : "green"}>{t(`role.${item}`)}</Badge>)}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex flex-col items-center gap-1">
                      <Badge className="w-fit" tone={user.enabled ? "green" : "neutral"}>{user.enabled ? t("common.enabled") : t("common.disabled")}</Badge>
                      {user.archivedAt ? <Badge className="w-fit" tone="amber">{t("adminUsers.archived")}</Badge> : null}
                      {user.passwordResetRequired ? <Badge className="w-fit" tone="amber">{t("adminUsers.passwordResetRequired")}</Badge> : null}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-center tabular-nums text-[var(--oj-ink-muted)]">{new Date(user.createdAt).toLocaleDateString()}</td>
                  <td className="px-4 py-3">
                    <div className="flex flex-nowrap items-center justify-end gap-1.5">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        className="size-8 px-0"
                        title={t("common.edit")}
                        aria-label={t("common.edit")}
                        onClick={() => openEdit(user)}
                      >
                        <Edit className="size-4" aria-hidden="true" />
                        </Button>
                      {lifecycle === "ACTIVE" ? (
                        <>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            className="size-8 px-0"
                            title={t("adminUsers.resetPassword")}
                            aria-label={t("adminUsers.resetPassword")}
                            onClick={() => setResetTargets([user])}
                          >
                            <KeyRound className="size-4" aria-hidden="true" />
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            className="size-8 px-0 text-amber-700 hover:bg-amber-50"
                            title={t("adminUsers.archive")}
                            aria-label={t("adminUsers.archive")}
                            onClick={() => confirmSingle(user, "ARCHIVE")}
                          >
                            <Archive className="size-4" aria-hidden="true" />
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant={user.enabled ? "outline" : "primary"}
                            className={user.enabled ? "size-8 px-0 text-red-700 hover:bg-red-50" : "size-8 px-0"}
                            title={user.enabled ? t("common.disable") : t("common.enable")}
                            aria-label={user.enabled ? t("common.disable") : t("common.enable")}
                            onClick={() => setConfirmTarget({ user, enabled: !user.enabled })}
                          >
                            {user.enabled ? <Ban className="size-4" aria-hidden="true" /> : <CheckCircle2 className="size-4" aria-hidden="true" />}
                          </Button>
                        </>
                      ) : (
                        <>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            className="size-8 px-0"
                            title={t("adminUsers.restore")}
                            aria-label={t("adminUsers.restore")}
                            onClick={() => confirmSingle(user, "RESTORE")}
                          >
                            <ArchiveRestore className="size-4" aria-hidden="true" />
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            className="size-8 px-0 text-red-700 hover:bg-red-50"
                            title={t("adminUsers.deleteArchived")}
                            aria-label={t("adminUsers.deleteArchived")}
                            onClick={() => confirmSingle(user, "DELETE_ARCHIVED")}
                          >
                            <Trash2 className="size-4" aria-hidden="true" />
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
            </table>
          </TableShell>
          <PaginationRow
            page={page}
            total={total}
            pageSize={pageSize}
            onPageChange={handlePageChange}
            onPageSizeChange={(nextPageSize) => {
              setPageSize(nextPageSize);
              window.requestAnimationFrame(() => listTopRef.current?.scrollIntoView({ block: "start" }));
            }}
            pageSizeOptions={USER_PAGE_SIZE_OPTIONS}
            previousLabel={t("common.previous")}
            nextLabel={t("common.next")}
            pageSizeLabel={t("common.pageSize")}
            disabled={usersQuery.isFetching}
          />
        </div>
      ) : (
        <EmptyState title={t("adminUsers.empty")} description={t("adminUsers.loadFailed")} actionLabel={t("adminUsers.create")} />
      )}

      <UserEditorPanel
        open={editorOpen}
        onOpenChange={setEditorOpen}
        roles={rolesQuery.data ?? []}
        user={editingUser}
        onSaved={async () => {
          setEditorOpen(false);
          await queryClient.invalidateQueries({ queryKey: ["admin-users"] });
        }}
      />

      <BatchUserDialog
        open={batchOpen}
        onOpenChange={setBatchOpen}
        onDone={async () => {
          setBatchOpen(false);
          await queryClient.invalidateQueries({ queryKey: ["admin-users"] });
        }}
      />

      <ResetPasswordPanel
        open={Boolean(resetTargets)}
        onOpenChange={(open) => !open && setResetTargets(null)}
        count={resetTargets?.length ?? 0}
        accounts={(resetTargets ?? []).map((user) => user.account)}
        saving={batchActionMutation.isPending}
        onSubmit={async (payload) => {
          const targets = resetTargets ?? [];
          await batchActionMutation.mutateAsync({ userIds: targets.map((user) => user.userId), action: "RESET_PASSWORD", ...payload });
          setResetTargets(null);
        }}
      />

      <ConfirmDialog
        open={Boolean(confirmTarget)}
        onOpenChange={(open) => !open && setConfirmTarget(null)}
        title={confirmTarget?.enabled ? t("adminUsers.enableConfirm") : t("adminUsers.disableConfirm")}
        description={confirmTarget?.user.account ?? ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={confirmTarget?.enabled ? t("common.enable") : t("common.disable")}
        onConfirm={async () => {
          if (!confirmTarget) return;
          await toggleMutation.mutateAsync({ user: confirmTarget.user, nextEnabled: confirmTarget.enabled });
          setConfirmTarget(null);
        }}
      />

      <ConfirmDialog
        open={Boolean(bulkConfirm)}
        onOpenChange={(open) => !open && setBulkConfirm(null)}
        title={bulkConfirm?.title ?? ""}
        description={bulkConfirm?.description ?? ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={bulkConfirm?.confirmLabel ?? t("common.save")}
        onConfirm={async () => {
          if (!bulkConfirm) return;
          await batchActionMutation.mutateAsync({ action: bulkConfirm.action });
          setBulkConfirm(null);
        }}
      />

      <ConfirmDialog
        open={Boolean(singleConfirm)}
        onOpenChange={(open) => !open && setSingleConfirm(null)}
        title={singleConfirm?.title ?? ""}
        description={singleConfirm?.description ?? ""}
        cancelLabel={t("common.cancel")}
        confirmLabel={singleConfirm?.confirmLabel ?? t("common.save")}
        onConfirm={async () => {
          if (!singleConfirm) return;
          await batchActionMutation.mutateAsync({ userIds: [singleConfirm.user.userId], action: singleConfirm.action });
          setSingleConfirm(null);
        }}
      />
    </div>
  );
}

function TruncatedText({ value }: { value: string }) {
  return <span className="block truncate" title={value}>{value}</span>;
}

function UserEditorPanel({
  open,
  onOpenChange,
  roles,
  user,
  onSaved
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  roles: RoleResponse[];
  user: AdminUserResponse | null;
  onSaved: () => Promise<void>;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [account, setAccount] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [displayName, setDisplayName] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [baseRole, setBaseRole] = React.useState<BaseRole>("STUDENT");
  const [adminAccess, setAdminAccess] = React.useState(false);
  const [enabled, setEnabled] = React.useState(true);
  const [passwordResetRequired, setPasswordResetRequired] = React.useState(true);
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({});
  const [saving, setSaving] = React.useState(false);

  const formSnapshot = React.useMemo(() => JSON.stringify({
    account,
    password,
    displayName,
    email,
    baseRole,
    adminAccess,
    enabled,
    passwordResetRequired
  }), [account, password, displayName, email, baseRole, adminAccess, enabled, passwordResetRequired]);

  const baselineSnapshot = React.useMemo(() => {
    if (!open) return null;
    return JSON.stringify(user ? {
      account: user.account,
      password: "",
      displayName: user.displayName,
      email: user.email || "",
      baseRole: user.roles.includes("TEACHER") ? "TEACHER" : "STUDENT",
      adminAccess: user.roles.includes("ADMIN"),
      enabled: user.enabled,
      passwordResetRequired: user.passwordResetRequired
    } : {
      account: "",
      password: "",
      displayName: "",
      email: "",
      baseRole: "STUDENT",
      adminAccess: false,
      enabled: true,
      passwordResetRequired: true
    });
  }, [open, user]);

  const dirty = baselineSnapshot !== null && formSnapshot !== baselineSnapshot;

  React.useEffect(() => {
    if (!open) return;
    setFieldErrors({});
    if (user) {
      setAccount(user.account);
      setPassword("");
      setDisplayName(user.displayName);
      setEmail(user.email || "");
      setBaseRole(user.roles.includes("TEACHER") ? "TEACHER" : "STUDENT");
      setAdminAccess(user.roles.includes("ADMIN"));
      setEnabled(user.enabled);
      setPasswordResetRequired(user.passwordResetRequired);
    } else {
      setAccount("");
      setPassword("");
      setDisplayName("");
      setEmail("");
      setBaseRole("STUDENT");
      setAdminAccess(false);
      setEnabled(true);
      setPasswordResetRequired(true);
    }
  }, [open, user]);

  async function save() {
    setFieldErrors({});
    const assembledRoles: Role[] = [baseRole, ...(adminAccess ? ["ADMIN" as Role] : [])];
    setSaving(true);
    try {
      if (user) {
        await api.updateUser(user.userId, {
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          roles: assembledRoles,
          enabled,
          passwordResetRequired
        });
        toast.success(t("adminUsers.updatedMessage"));
      } else {
        await api.createUser({
          account: account.trim(),
          password,
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          roles: assembledRoles,
          enabled,
          passwordResetRequired
        });
        toast.success(t("adminUsers.createdMessage"));
      }
      await onSaved();
    } catch (caught) {
      if (caught instanceof ApiError) {
        setFieldErrors(caught.details ?? {});
        toast.error(caught.userMessage);
      } else {
        toast.error(readableCaughtError(caught, locale, t("adminUsers.saveFailed")));
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={user ? t("adminUsers.editModal") : t("adminUsers.createModal")}
      description={t("adminUsers.adminPermissionCopy")}
      footer={(
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={saving || !dirty} onClick={() => void save()}>{saving ? t("common.loading") : t("common.save")}</Button>
        </div>
      )}
    >
      <div className="space-y-4">
        {!user ? (
          <>
            <Field label={t("common.account")} error={fieldErrors.account}>
              <input className={inputClass} value={account} onChange={(event) => setAccount(event.target.value)} />
            </Field>
            <Field label={t("common.password")} error={fieldErrors.password}>
              <input className={inputClass} value={password} onChange={(event) => setPassword(event.target.value)} type="password" />
            </Field>
          </>
        ) : null}
        <Field label={t("common.displayName")} error={fieldErrors.displayName}>
          <input className={inputClass} value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
        </Field>
        <Field label={t("common.email")} error={fieldErrors.email}>
          <input className={inputClass} value={email} onChange={(event) => setEmail(event.target.value)} />
        </Field>
        <Field label={t("adminUsers.baseIdentity")} error={fieldErrors.roles}>
          <select className={selectClass} value={baseRole} onChange={(event) => setBaseRole(event.target.value as BaseRole)}>
            {roles.filter((item) => item.role !== "ADMIN").map((item) => <option key={item.role} value={item.role}>{roleLabel(item.role, t, item.label)}</option>)}
          </select>
        </Field>
        <label className="flex items-start gap-3 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <input className="mt-1" type="checkbox" checked={adminAccess} onChange={(event) => setAdminAccess(event.target.checked)} />
          <span>
            <span className="block text-sm font-medium text-[var(--oj-ink)]">{t("adminUsers.adminPermission")}</span>
            <span className="mt-1 block text-xs leading-5 text-[var(--oj-ink-muted)]">{t("adminUsers.adminPermissionCopy")}</span>
          </span>
        </label>
        <label className="flex items-center gap-3">
          <input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
          <span className="text-sm font-medium text-[var(--oj-ink)]">{t("common.enabled")}</span>
        </label>
        <label className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-3">
          <input className="mt-1" type="checkbox" checked={passwordResetRequired} onChange={(event) => setPasswordResetRequired(event.target.checked)} />
          <span>
            <span className="block text-sm font-medium text-amber-950">{t("adminUsers.passwordResetRequired")}</span>
            <span className="mt-1 block text-xs leading-5 text-amber-900">{t("adminUsers.passwordResetRequiredCopy")}</span>
          </span>
        </label>
      </div>
    </SidePanel>
  );
}

function ResetPasswordPanel({
  open,
  onOpenChange,
  count,
  accounts,
  saving,
  onSubmit
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  count: number;
  accounts: string[];
  saving: boolean;
  onSubmit: (payload: { password: { mode: "FIXED" | "ACCOUNT_SUFFIX"; fixedPassword?: string; accountSuffix?: string }; passwordResetRequired: boolean }) => Promise<void>;
}) {
  const { t } = useI18n();
  const [mode, setMode] = React.useState<"FIXED" | "ACCOUNT_SUFFIX">("ACCOUNT_SUFFIX");
  const [fixedPassword, setFixedPassword] = React.useState("");
  const [accountSuffix, setAccountSuffix] = React.useState("@Aioj2026");
  const [forceReset, setForceReset] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) return;
    setError(null);
    setForceReset(true);
  }, [open]);

  function validate() {
    if (mode === "FIXED") {
      if (fixedPassword.length < 8 || fixedPassword.length > 128) {
        return t("adminUsers.fixedPasswordInvalid");
      }
      return null;
    }
    const shortest = accounts.reduce<string | null>((current, account) => {
      if (current == null) return account;
      return account.length < current.length ? account : current;
    }, null);
    const sampleLength = (shortest ?? "").length + accountSuffix.length;
    if (sampleLength < 8 || sampleLength > 128) {
      return t("adminUsers.accountSuffixPasswordInvalid", { account: shortest ?? "", length: sampleLength });
    }
    return null;
  }

  async function submit() {
    const nextError = validate();
    if (nextError) {
      setError(nextError);
      return;
    }
    setError(null);
    await onSubmit({
      password: {
        mode,
        fixedPassword: mode === "FIXED" ? fixedPassword : undefined,
        accountSuffix: mode === "ACCOUNT_SUFFIX" ? accountSuffix : undefined
      },
      passwordResetRequired: forceReset
    });
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={t("adminUsers.resetPassword")}
      description={t("adminUsers.resetPasswordCopy", { count })}
      footer={(
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={saving || count === 0} onClick={() => void submit()}>
            <KeyRound className="size-4" aria-hidden="true" />
            {saving ? t("common.loading") : t("adminUsers.resetPassword")}
          </Button>
        </div>
      )}
    >
      <div className="space-y-4">
        {error ? <ErrorPanel title={error} /> : null}
        <Field label={t("adminUsers.passwordMode")}>
          <select className={selectClass} value={mode} onChange={(event) => setMode(event.target.value as typeof mode)}>
            <option value="FIXED">{t("adminUsers.passwordModeFixed")}</option>
            <option value="ACCOUNT_SUFFIX">{t("adminUsers.passwordModeAccountSuffix")}</option>
          </select>
        </Field>
        {mode === "FIXED" ? (
          <Field label={t("common.password")}>
            <input className={inputClass} type="password" value={fixedPassword} onChange={(event) => setFixedPassword(event.target.value)} />
          </Field>
        ) : (
          <Field label={t("adminUsers.accountSuffix")}>
            <input className={inputClass} value={accountSuffix} onChange={(event) => setAccountSuffix(event.target.value)} />
          </Field>
        )}
        <label className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-3">
          <input className="mt-1" type="checkbox" checked={forceReset} onChange={(event) => setForceReset(event.target.checked)} />
          <span>
            <span className="block text-sm font-medium text-amber-950">{t("adminUsers.passwordResetRequired")}</span>
            <span className="mt-1 block text-xs leading-5 text-amber-900">{t("adminUsers.passwordResetRequiredCopy")}</span>
          </span>
        </label>
        <p className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink-muted)]">
          {t("adminUsers.resetPasswordNoPlaintext")}
        </p>
      </div>
    </SidePanel>
  );
}

function BatchUserDialog({
  open,
  onOpenChange,
  onDone
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDone: () => Promise<void>;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [mode, setMode] = React.useState<"SEQUENCE" | "TEXT">("SEQUENCE");
  const [prefix, setPrefix] = React.useState("stu");
  const [start, setStart] = React.useState(1);
  const [count, setCount] = React.useState(30);
  const [width, setWidth] = React.useState(3);
  const [importText, setImportText] = React.useState("");
  const [rows, setRows] = React.useState<AdminUserBatchPreviewItem[]>([]);
  const [passwordMode, setPasswordMode] = React.useState<"FIXED" | "ACCOUNT_SUFFIX">("FIXED");
  const [fixedPassword, setFixedPassword] = React.useState("");
  const [accountSuffix, setAccountSuffix] = React.useState("@Aioj2026");
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({});
  const [summary, setSummary] = React.useState<string | null>(null);
  const [importNotice, setImportNotice] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) return;
    setRows([]);
    setFieldErrors({});
    setSummary(null);
    setImportNotice(null);
  }, [open]);

  const previewMutation = useMutation({
    mutationFn: async () => {
      const inputError = validateBatchInput();
      if (inputError) {
        throw new Error(inputError);
      }
      return api.previewBatchUsers({
        mode,
        prefix,
        start,
        count,
        width,
        importText,
        defaultRoles: ["STUDENT"],
        enabled: true,
        passwordResetRequired: true
      });
    },
    onSuccess: (response) => {
      setFieldErrors({});
      setRows(response.items);
      setSummary(t("adminUsers.batchPreviewSummary", { total: response.total, valid: response.valid, invalid: response.invalid }));
    },
    onError: (caught) => toast.error(batchApiErrorMessage(caught, locale, t, t("adminUsers.batchPreviewFailed")))
  });

  const aiParseMutation = useMutation({
    mutationFn: async () => {
      const response = await api.parseAccountImport(importText);
      const parsed = response.users.map<AdminUserBatchCandidate>((item) => ({
        account: item.account,
        displayName: item.displayName,
        email: item.email || undefined,
        roles: ["STUDENT"],
        enabled: true,
        passwordResetRequired: true
      }));
      const preview = await api.previewBatchUsers({ candidates: parsed, defaultRoles: ["STUDENT"], enabled: true, passwordResetRequired: true });
      return { response, preview };
    },
    onSuccess: ({ response, preview }) => {
      if (preview.invalid > 0) {
        toast.error(batchPreviewErrorSummary(preview, t, t("adminUsers.aiParseHasInvalidRows")));
      }
      setRows(preview.items);
      setSummary(response.note || t("adminUsers.aiParseDone"));
    },
    onError: (caught) => toast.error(batchApiErrorMessage(caught, locale, t, t("adminUsers.aiParseFailed")))
  });

  const clearInvalidEmailsMutation = useMutation({
    mutationFn: async () => {
      const candidates = rows.map((row) => {
        const candidate = rowToCandidate(row);
        return rowHasEmailError(row) ? { ...candidate, email: undefined } : candidate;
      });
      return api.previewBatchUsers({ candidates, defaultRoles: ["STUDENT"], enabled: true, passwordResetRequired: true });
    },
    onSuccess: (response) => {
      setRows(response.items);
      if (response.invalid > 0) {
        toast.error(batchPreviewErrorSummary(response, t, t("adminUsers.batchHasInvalidRows")));
      }
      setSummary(t("adminUsers.invalidEmailsCleared", { count: rows.filter(rowHasEmailError).length }));
    },
    onError: (caught) => toast.error(batchApiErrorMessage(caught, locale, t, t("adminUsers.batchPreviewFailed")))
  });

  const createMutation = useMutation({
    mutationFn: async () => {
      const candidates = rows.map(rowToCandidate);
      const passwordError = validateBatchPassword(candidates);
      if (passwordError) {
        toast.error(passwordError);
        throw new Error(passwordError);
      }
      const preview = await api.previewBatchUsers({ candidates, defaultRoles: ["STUDENT"], enabled: true, passwordResetRequired: true });
      setRows(preview.items);
      if (preview.invalid > 0) {
        throw new Error(batchPreviewErrorSummary(preview, t, t("adminUsers.batchHasInvalidRows")));
      }
      return api.createBatchUsers({
        users: candidates,
        password: {
          mode: passwordMode,
          fixedPassword: passwordMode === "FIXED" ? fixedPassword : undefined,
          accountSuffix: passwordMode === "ACCOUNT_SUFFIX" ? accountSuffix : undefined
        }
      });
    },
    onSuccess: async (response) => {
      setFieldErrors({});
      toast.success(t("adminUsers.batchCreateSummary", { total: response.requested, created: response.created, skipped: response.skipped }));
      await onDone();
    },
    onError: (caught) => toast.error(batchApiErrorMessage(caught, locale, t, t("adminUsers.batchCreateFailed")))
  });

  function validateBatchInput() {
    const next: Record<string, string> = {};
    if (mode === "SEQUENCE") {
      if (!prefix.trim()) {
        next.prefix = t("adminUsers.accountPrefixRequired");
      } else if (!/^[A-Za-z0-9._@-]+$/.test(prefix.trim())) {
        next.prefix = t("adminUsers.accountPrefixInvalid");
      }
      if (!Number.isInteger(count) || count < 1 || count > 200) {
        next.count = t("adminUsers.sequenceCountInvalid");
      }
      if (!Number.isInteger(width) || width < 1 || width > 12) {
        next.width = t("adminUsers.sequenceWidthInvalid");
      }
    } else if (!importText.trim()) {
      next.importText = t("adminUsers.importTextRequired");
    }
    setFieldErrors(next);
    const first = Object.values(next)[0];
    if (first) {
      toast.error(first);
    }
    return first ?? null;
  }

  function validateBatchPassword(candidates: AdminUserBatchCandidate[]) {
    const next: Record<string, string> = {};
    let message: string | null = null;
    if (passwordMode === "FIXED") {
      if (fixedPassword.length < 8 || fixedPassword.length > 128) {
        message = t("adminUsers.fixedPasswordInvalid");
        next.fixedPassword = message;
      }
    } else {
      const accounts = candidates.map((item) => item.account ?? "").filter(Boolean);
      const shortest = accounts.reduce<string | null>((current, account) => {
        if (current == null) return account;
        return account.length < current.length ? account : current;
      }, null);
      const sampleLength = (shortest ?? "").length + accountSuffix.length;
      if (sampleLength < 8 || sampleLength > 128) {
        message = t("adminUsers.accountSuffixPasswordInvalid", { account: shortest ?? "", length: sampleLength });
        next.accountSuffix = message;
      }
    }
    setFieldErrors((current) => ({ ...current, ...next }));
    return message;
  }

  function updateRow(index: number, patch: Partial<AdminUserBatchPreviewItem>) {
    setRows((current) => current.map((row, rowIndex) => rowIndex === index ? { ...row, ...patch } : row));
  }

  async function importFile(file: File | null) {
    if (!file) return;
    try {
      const imported = await readImportedText(file);
      setImportText(imported.text);
      setImportNotice(t("adminUsers.importEncodingDetected", { encoding: imported.encoding }));
      setMode("TEXT");
      setFieldErrors((current) => {
        const next = { ...current };
        delete next.importText;
        return next;
      });
    } catch (caught) {
      toast.error(readableCaughtError(caught, locale, t("adminUsers.importFileReadFailed")));
    }
  }

  const invalidEmailCount = rows.filter(rowHasEmailError).length;

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      presentation="workspace"
      workspaceSize="xl"
      title={t("adminUsers.batchCreate")}
      description={t("adminUsers.batchCreateCopy")}
      footer={(
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={!rows.length || createMutation.isPending} onClick={() => createMutation.mutate()}>
            {createMutation.isPending ? t("common.loading") : t("adminUsers.confirmBatchCreate")}
          </Button>
        </div>
      )}
    >
      <div className="grid gap-5 lg:grid-cols-[360px_minmax(0,1fr)]">
        <section className="space-y-4">
          {summary ? <p className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-900">{summary}</p> : null}
          {importNotice ? <p className="rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-xs leading-5 text-blue-900">{importNotice}</p> : null}
          <Field label={t("adminUsers.batchMode")}>
            <select className={selectClass} value={mode} onChange={(event) => setMode(event.target.value as typeof mode)}>
              <option value="SEQUENCE">{t("adminUsers.batchModeSequence")}</option>
              <option value="TEXT">{t("adminUsers.batchModeText")}</option>
            </select>
          </Field>
          {mode === "SEQUENCE" ? (
            <div className="grid grid-cols-3 gap-3">
              <Field label={t("adminUsers.accountPrefix")} error={fieldErrors.prefix}>
                <input className={inputClass} value={prefix} onChange={(event) => setPrefix(event.target.value)} />
              </Field>
              <Field label={t("adminUsers.sequenceStart")}>
                <input className={inputClass} type="number" min={0} value={start} onChange={(event) => setStart(Number(event.target.value))} />
              </Field>
              <Field label={t("adminUsers.sequenceCount")} error={fieldErrors.count}>
                <input className={inputClass} type="number" min={1} max={200} value={count} onChange={(event) => setCount(Number(event.target.value))} />
              </Field>
              <Field label={t("adminUsers.sequenceWidth")} error={fieldErrors.width}>
                <input className={inputClass} type="number" min={1} max={12} value={width} onChange={(event) => setWidth(Number(event.target.value))} />
              </Field>
            </div>
          ) : (
            <div className="space-y-3">
              <Field label={t("adminUsers.importText")} error={fieldErrors.importText}>
                <textarea
                  className={`${textareaClass} min-h-40`}
                  value={importText}
                  onChange={(event) => {
                    setImportText(event.target.value);
                    setImportNotice(null);
                  }}
                  placeholder={t("adminUsers.importTextPlaceholder")}
                />
              </Field>
              <p className="text-xs leading-5 text-[var(--oj-ink-muted)]">{t("adminUsers.aiParseReplacesPreview")}</p>
              <div className="flex flex-wrap gap-2">
                <label className="inline-flex h-10 cursor-pointer items-center gap-2 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm font-medium text-[var(--oj-ink)] hover:bg-[var(--oj-surface-muted)]">
                  <Upload className="size-4" aria-hidden="true" />
                  {t("adminUsers.importFile")}
                  <input className="sr-only" type="file" accept=".txt,.csv,.tsv,text/plain,text/csv" onChange={(event) => void importFile(event.target.files?.[0] ?? null)} />
                </label>
                <Button variant="outline" disabled={!importText.trim() || aiParseMutation.isPending} onClick={() => aiParseMutation.mutate()}>
                  <Bot className="size-4" aria-hidden="true" />
                  {aiParseMutation.isPending ? t("common.loading") : t("adminUsers.aiParse")}
                </Button>
              </div>
            </div>
          )}
          <Button className="w-full" disabled={previewMutation.isPending} onClick={() => previewMutation.mutate()}>
            {previewMutation.isPending ? t("common.loading") : t("adminUsers.generatePreview")}
          </Button>
          {invalidEmailCount > 0 ? (
            <Button className="w-full" variant="outline" disabled={clearInvalidEmailsMutation.isPending} onClick={() => clearInvalidEmailsMutation.mutate()}>
              {clearInvalidEmailsMutation.isPending ? t("common.loading") : t("adminUsers.clearInvalidEmails", { count: invalidEmailCount })}
            </Button>
          ) : null}
          <div className="space-y-3 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
            <Field label={t("adminUsers.passwordMode")}>
              <select className={selectClass} value={passwordMode} onChange={(event) => setPasswordMode(event.target.value as typeof passwordMode)}>
                <option value="FIXED">{t("adminUsers.passwordModeFixed")}</option>
                <option value="ACCOUNT_SUFFIX">{t("adminUsers.passwordModeAccountSuffix")}</option>
              </select>
            </Field>
            {passwordMode === "FIXED" ? (
              <Field label={t("common.password")} error={fieldErrors.fixedPassword}>
                <input className={inputClass} type="password" value={fixedPassword} onChange={(event) => setFixedPassword(event.target.value)} />
              </Field>
            ) : (
              <Field label={t("adminUsers.accountSuffix")} error={fieldErrors.accountSuffix}>
                <input className={inputClass} value={accountSuffix} onChange={(event) => setAccountSuffix(event.target.value)} />
              </Field>
            )}
          </div>
        </section>
        <section className="min-w-0 overflow-hidden rounded-xl border border-[var(--oj-border)] bg-white">
          {rows.length ? (
            <div className="max-h-[62dvh] overflow-auto">
              <table className="min-w-[980px] text-sm">
                <thead className="sticky top-0 z-10 border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                  <tr>
                    <th className="px-3 py-2 text-left">#</th>
                    <th className="px-3 py-2 text-left">{t("common.account")}</th>
                    <th className="px-3 py-2 text-left">{t("common.displayName")}</th>
                    <th className="px-3 py-2 text-left">{t("common.email")}</th>
                    <th className="px-3 py-2 text-left">{t("common.roles")}</th>
                    <th className="px-3 py-2 text-left">{t("common.status")}</th>
                    <th className="px-3 py-2 text-left">{t("adminUsers.validation")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--oj-border-soft)]">
                  {rows.map((row, index) => (
                    <tr key={`${row.rowNumber}-${index}`}>
                      <td className="px-3 py-2 text-[var(--oj-ink-muted)]">{row.rowNumber}</td>
                      <td className="px-3 py-2"><input className={inputClass} value={row.account ?? ""} onChange={(event) => updateRow(index, { account: event.target.value })} /></td>
                      <td className="px-3 py-2"><input className={inputClass} value={row.displayName ?? ""} onChange={(event) => updateRow(index, { displayName: event.target.value })} /></td>
                      <td className="px-3 py-2"><input className={inputClass} value={row.email ?? ""} onChange={(event) => updateRow(index, { email: event.target.value })} /></td>
                      <td className="px-3 py-2">
                        <select className={selectClass} value={row.roles.includes("TEACHER") ? "TEACHER" : "STUDENT"} onChange={(event) => updateRow(index, { roles: [event.target.value as Role] })}>
                          <option value="STUDENT">{t("role.STUDENT")}</option>
                          <option value="TEACHER">{t("role.TEACHER")}</option>
                        </select>
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex flex-col gap-1">
                          <label className="inline-flex items-center gap-2"><input type="checkbox" checked={row.enabled} onChange={(event) => updateRow(index, { enabled: event.target.checked })} />{t("common.enabled")}</label>
                          <label className="inline-flex items-center gap-2"><input type="checkbox" checked={row.passwordResetRequired} onChange={(event) => updateRow(index, { passwordResetRequired: event.target.checked })} />{t("adminUsers.passwordResetRequired")}</label>
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        {row.valid ? <Badge tone="green">{t("adminUsers.valid")}</Badge> : <div className="space-y-1">{row.errors.map((item) => <Badge key={item} tone="red">{item}</Badge>)}</div>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <EmptyState title={t("adminUsers.noPreview")} description={t("adminUsers.noPreviewCopy")} />
          )}
        </section>
      </div>
    </SidePanel>
  );
}

function rowToCandidate(row: AdminUserBatchPreviewItem): AdminUserBatchCandidate {
  return {
    account: row.account,
    displayName: row.displayName,
    email: row.email,
    roles: row.roles,
    enabled: row.enabled,
    passwordResetRequired: row.passwordResetRequired
  };
}

function rowHasEmailError(row: AdminUserBatchPreviewItem) {
  if (!row.email?.trim()) return false;
  if (row.errorCodes && row.errorCodes.length) return row.errorCodes.includes("EMAIL_INVALID");
  // Legacy fallback for responses without stable error codes.
  return row.errors.some((item) => item.includes("邮箱") || item.toLowerCase().includes("email"));
}

function batchPreviewErrorSummary(response: { invalid: number; items: AdminUserBatchPreviewItem[] }, t: Translate, intro: string) {
  const invalidRows = response.items.filter((item) => !item.valid).slice(0, 3);
  const detail = invalidRows.map((item) => t("adminUsers.batchInvalidRowDetail", {
    row: item.rowNumber,
    errors: item.errors.join("；")
  })).join("；");
  const more = response.invalid > invalidRows.length
    ? t("adminUsers.batchInvalidRowsMore", { count: response.invalid - invalidRows.length })
    : "";
  return [intro, detail, more].filter(Boolean).join(" ");
}

function batchApiErrorMessage(caught: unknown, locale: Parameters<typeof readableCaughtError>[1], t: Translate, fallback: string) {
  if (caught instanceof ApiError && caught.details) {
    const details = Object.entries(caught.details).slice(0, 3).map(([path, message]) => readableBatchFieldDetail(path, message, t));
    if (details.length) {
      return `${caught.userMessage} ${details.join("；")}`;
    }
  }
  if (caught instanceof ApiError) {
    return caught.userMessage;
  }
  return caught instanceof Error ? caught.message : readableCaughtError(caught, locale, fallback);
}

function readableBatchFieldDetail(path: string, message: string, t: Translate) {
  const candidateMatch = path.match(/^candidates\[(\d+)]\.(\w+)$/);
  if (candidateMatch) {
    return t("adminUsers.batchServerFieldError", {
      row: Number(candidateMatch[1]) + 1,
      field: batchFieldLabel(candidateMatch[2], t),
      message
    });
  }
  return t("adminUsers.batchServerRawFieldError", { field: path, message });
}

function batchFieldLabel(field: string, t: Translate) {
  const labels: Record<string, string> = {
    account: t("common.account"),
    displayName: t("common.displayName"),
    email: t("common.email"),
    roles: t("common.roles")
  };
  return labels[field] ?? field;
}

async function readImportedText(file: File) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF) {
    return { text: normalizeImportedText(decodeBytes(bytes.subarray(3), "utf-8", false)), encoding: "UTF-8 BOM" };
  }
  if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) {
    return { text: normalizeImportedText(decodeBytes(bytes.subarray(2), "utf-16le", false)), encoding: "UTF-16 LE" };
  }
  if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) {
    return { text: normalizeImportedText(decodeBytes(bytes.subarray(2), "utf-16be", false)), encoding: "UTF-16 BE" };
  }
  try {
    return { text: normalizeImportedText(decodeBytes(bytes, "utf-8", true)), encoding: "UTF-8" };
  } catch {
    return { text: normalizeImportedText(decodeBytesWithFallback(bytes, ["gb18030", "gbk"])), encoding: "GB18030/GBK" };
  }
}

function decodeBytes(bytes: Uint8Array, encoding: string, fatal: boolean) {
  return new TextDecoder(encoding, { fatal }).decode(bytes);
}

function decodeBytesWithFallback(bytes: Uint8Array, encodings: string[]) {
  let lastError: unknown;
  for (const encoding of encodings) {
    try {
      return decodeBytes(bytes, encoding, false);
    } catch (caught) {
      lastError = caught;
    }
  }
  throw lastError instanceof Error ? lastError : new Error("Unable to decode imported file.");
}

function normalizeImportedText(value: string) {
  return value.replace(/^\uFEFF/, "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

function roleLabel(role: Role, t: Translate, fallback?: string) {
  return t(`role.${role}`, undefined, fallback || role);
}

function normalizeRoles(roles: Role[]) {
  const base: BaseRole = roles.includes("TEACHER") ? "TEACHER" : "STUDENT";
  const next: Role[] = [base];
  if (roles.includes("ADMIN")) next.push("ADMIN");
  return next;
}
