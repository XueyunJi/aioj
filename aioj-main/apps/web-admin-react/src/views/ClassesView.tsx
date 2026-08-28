import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Archive, ArchiveRestore, CheckCircle2, Edit, GraduationCap, Plus, RotateCw, Search, Trash2, UserPlus, X } from "lucide-react";
import {
  type AdminUserResponse,
  ApiError,
  api,
  type EntityId,
  type LearningGroupMemberBatchAddResponse,
  type LearningGroupMemberBatchAddStatus,
  type LearningGroupMemberResponse,
  type LearningGroupMemberRole,
  type LearningGroupResponse,
  type LearningGroupStatus
} from "@aioj/api-client";
import { Badge, Button, Card, CardBody, cn } from "@aioj/ui-react";
import {
  ConfirmDialog,
  EmptyState,
  ErrorPanel,
  Field,
  LoadingPanel,
  PageHeader,
  SidePanel,
  TableShell,
  inputClass,
  selectClass,
  textareaClass
} from "../components/Common";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { readableCaughtError } from "../lib/readableError";

type GroupEditorTarget = { item: LearningGroupResponse | null };

type MemberTarget = { classId: EntityId; title: string };

type RemovalTarget =
  | { kind: "class-member"; classId: EntityId; userId: EntityId; label: string }
  | { kind: "class-archive"; classId: EntityId; name: string }
  | { kind: "class-restore"; classId: EntityId; name: string }
  | { kind: "class-delete"; classId: EntityId; name: string };

export function ClassesView() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = React.useState("");
  const [status, setStatus] = React.useState<LearningGroupStatus | "">("");
  const [selectedClassId, setSelectedClassId] = React.useState<EntityId | null>(null);
  const [groupEditor, setGroupEditor] = React.useState<GroupEditorTarget | null>(null);
  const [memberTarget, setMemberTarget] = React.useState<MemberTarget | null>(null);
  const [removalTarget, setRemovalTarget] = React.useState<RemovalTarget | null>(null);

  const classesQuery = useQuery({
    queryKey: ["learning-classes", keyword.trim(), status],
    queryFn: () => api.classes({ keyword: keyword.trim() || undefined, status })
  });

  const classes = classesQuery.data ?? [];
  const selectedClass = classes.find((item) => item.id === selectedClassId) ?? classes[0] ?? null;
  const activeClassId = selectedClass?.id ?? null;

  React.useEffect(() => {
    if (!classes.length) {
      setSelectedClassId(null);
      return;
    }
    if (!selectedClassId || !classes.some((item) => item.id === selectedClassId)) {
      setSelectedClassId(classes[0].id);
    }
  }, [classes, selectedClassId]);

  const membersQuery = useQuery({
    queryKey: ["learning-class-members", activeClassId],
    queryFn: () => api.classMembers(activeClassId!),
    enabled: Boolean(activeClassId)
  });

  const lifecycleMutation = useMutation({
    mutationFn: async (target: Extract<RemovalTarget, { kind: "class-archive" | "class-restore" | "class-delete" }>) => {
      if (target.kind === "class-archive") {
        await api.archiveClass(target.classId);
      } else if (target.kind === "class-restore") {
        await api.restoreClass(target.classId);
      } else {
        await api.deleteClass(target.classId);
      }
    },
    onSuccess: async (_data, target) => {
      toast.success(t(
        target.kind === "class-archive"
          ? "classes.archivedMessage"
          : target.kind === "class-restore"
            ? "classes.restoredMessage"
            : "classes.deletedMessage"
      ));
      await invalidateAll(queryClient);
    },
    onError: (caught) => {
      toast.error(readableClassActionError(caught, t, locale));
    }
  });

  const removeMemberMutation = useMutation({
    mutationFn: async (target: Extract<RemovalTarget, { kind: "class-member" }>) => {
      await api.removeClassMember(target.classId, target.userId);
    },
    onSuccess: async () => {
      toast.success(t("classes.memberRemovedMessage"));
      await invalidateAll(queryClient);
    },
    onError: (caught) => {
      toast.error(readableClassActionError(caught, t, locale));
    }
  });

  return (
    <div className="mx-auto flex w-full min-w-0 max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("classes.title")}
        description={t("classes.description")}
        actions={(
          <>
            <Button variant="outline" disabled={classesQuery.isFetching} onClick={() => void classesQuery.refetch()}>
              <RotateCw className="size-4" aria-hidden="true" />
              {t("common.refresh")}
            </Button>
            <Button onClick={() => setGroupEditor({ item: null })}>
              <Plus className="size-4" aria-hidden="true" />
              {t("classes.createClass")}
            </Button>
          </>
        )}
      />

      <section className="flex flex-wrap items-center gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <input
          className={`${inputClass} w-full sm:w-72`}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder={t("classes.searchPlaceholder")}
        />
        <select className={`${selectClass} w-full sm:w-44`} value={status} onChange={(event) => setStatus(event.target.value as typeof status)}>
          <option value="">{t("classes.allStatuses")}</option>
          <option value="ACTIVE">{t("classes.status.ACTIVE")}</option>
          <option value="ARCHIVED">{t("classes.status.ARCHIVED")}</option>
        </select>
      </section>

      {classesQuery.isLoading ? (
        <LoadingPanel label={t("classes.loading")} />
      ) : classesQuery.isError ? (
        <ErrorPanel title={t("classes.loadFailed")} action={<Button variant="outline" onClick={() => void classesQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : classes.length ? (
        <section className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-4 xl:grid-cols-[360px_minmax(0,1fr)]">
          <Card className="min-w-0 rounded-xl shadow-none">
            <CardBody className="space-y-3">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("classes.classList")}</h2>
                  <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("classes.classCount", { count: classes.length })}</p>
                </div>
                <Badge tone="blue">{auth.isAdmin ? t("role.ADMIN") : t("role.TEACHER")}</Badge>
              </div>
              <div className="space-y-2">
                {classes.map((item) => {
                  const selected = item.id === activeClassId;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      className={cn(
                        "w-full rounded-xl border p-3 text-left transition-colors",
                        selected ? "border-[var(--oj-primary)] bg-blue-50" : "border-[var(--oj-border-soft)] bg-white hover:bg-[var(--oj-surface-muted)]"
                      )}
                      onClick={() => setSelectedClassId(item.id)}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="truncate text-sm font-semibold text-[var(--oj-ink)]">{item.name}</p>
                          <p className="mt-1 truncate text-xs text-[var(--oj-ink-muted)]">{item.ownerDisplayName || t("classes.ownerUnknown")}</p>
                        </div>
                        <StatusChip status={item.status} />
                      </div>
                      <div className="mt-3 flex gap-2 text-xs text-[var(--oj-ink-muted)]">
                        <span>{t("classes.membersCount", { count: item.memberCount })}</span>
                        <span>{t("classes.studentsCount", { count: item.studentCount })}</span>
                      </div>
                    </button>
                  );
                })}
              </div>
            </CardBody>
          </Card>

          {selectedClass ? (
            <div className="min-w-0 space-y-4">
              <ClassOverview
                item={selectedClass}
                onEdit={() => setGroupEditor({ item: selectedClass })}
                onArchive={() => setRemovalTarget({ kind: "class-archive", classId: selectedClass.id, name: selectedClass.name })}
                onRestore={() => setRemovalTarget({ kind: "class-restore", classId: selectedClass.id, name: selectedClass.name })}
                onDelete={() => setRemovalTarget({ kind: "class-delete", classId: selectedClass.id, name: selectedClass.name })}
              />

              <MembersSection
                title={t("classes.classMembers")}
                members={membersQuery.data ?? []}
                loading={membersQuery.isLoading}
                error={membersQuery.isError}
                onRefresh={() => void membersQuery.refetch()}
                onAdd={() => setMemberTarget({ classId: selectedClass.id, title: selectedClass.name })}
                onRemove={(member) => setRemovalTarget({ kind: "class-member", classId: selectedClass.id, userId: member.userId, label: member.displayName || member.account })}
              />
            </div>
          ) : null}
        </section>
      ) : (
        <EmptyState
          title={t("classes.emptyTitle")}
          description={t("classes.emptyCopy")}
          actionLabel={t("classes.createClass")}
          onAction={() => setGroupEditor({ item: null })}
        />
      )}

      <GroupEditorPanel
        target={groupEditor}
        onOpenChange={(open) => !open && setGroupEditor(null)}
        onSaved={async (group) => {
          await invalidateAll(queryClient);
          setSelectedClassId(group.id);
          setGroupEditor(null);
        }}
      />

      <MemberAddPanel
        target={memberTarget}
        existingMembers={membersQuery.data ?? []}
        onOpenChange={(open) => !open && setMemberTarget(null)}
        onSaved={async () => {
          await invalidateAll(queryClient);
        }}
      />

      <ConfirmDialog
        open={Boolean(removalTarget)}
        onOpenChange={(open) => !open && setRemovalTarget(null)}
        title={confirmTitle(removalTarget, t)}
        description={confirmDescription(removalTarget, t)}
        cancelLabel={t("common.cancel")}
        confirmLabel={confirmLabel(removalTarget, t)}
        onConfirm={async () => {
          const target = removalTarget;
          if (!target) return;
          setRemovalTarget(null);
          if (target.kind === "class-member") {
            removeMemberMutation.mutate(target);
          } else {
            lifecycleMutation.mutate(target);
          }
        }}
      />
    </div>
  );
}

function ClassOverview({
  item,
  onEdit,
  onArchive,
  onRestore,
  onDelete
}: {
  item: LearningGroupResponse;
  onEdit: () => void;
  onArchive: () => void;
  onRestore: () => void;
  onDelete: () => void;
}) {
  const { t } = useI18n();
  const archived = item.status === "ARCHIVED";
  return (
    <Card className="min-w-0 rounded-xl shadow-none">
      <CardBody className="min-w-0">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="blue"><GraduationCap className="size-4" aria-hidden="true" /></Badge>
              <StatusChip status={item.status} />
            </div>
            <h2 className="mt-3 text-xl font-semibold text-[var(--oj-ink)]">{item.name}</h2>
            <p className="mt-2 max-w-[76ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{item.description || t("classes.noDescription")}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={onEdit}>
              <Edit className="size-4" aria-hidden="true" />
              {t("common.edit")}
            </Button>
            {archived ? (
              <>
                <Button variant="outline" onClick={onRestore}>
                  <ArchiveRestore className="size-4" aria-hidden="true" />
                  {t("classes.restore")}
                </Button>
                <Button variant="outline" className="text-red-700 hover:bg-red-50" onClick={onDelete}>
                  <Trash2 className="size-4" aria-hidden="true" />
                  {t("common.delete")}
                </Button>
              </>
            ) : (
              <Button variant="outline" onClick={onArchive}>
                <Archive className="size-4" aria-hidden="true" />
                {t("classes.archive")}
              </Button>
            )}
          </div>
        </div>
        <div className="mt-5 grid gap-3 sm:grid-cols-3">
          <Metric label={t("classes.owner")} value={item.ownerDisplayName || t("classes.ownerUnknown")} />
          <Metric label={t("classes.members")} value={String(item.memberCount)} />
          <Metric label={t("classes.students")} value={String(item.studentCount)} />
        </div>
      </CardBody>
    </Card>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
      <div className="text-xs text-[var(--oj-ink-muted)]">{label}</div>
      <div className="mt-1 truncate text-sm font-semibold text-[var(--oj-ink)]">{value}</div>
    </div>
  );
}

function MembersSection({
  title,
  members,
  loading,
  error,
  onRefresh,
  onAdd,
  onRemove
}: {
  title: string;
  members: LearningGroupMemberResponse[];
  loading: boolean;
  error: boolean;
  onRefresh: () => void;
  onAdd: () => void;
  onRemove: (member: LearningGroupMemberResponse) => void;
}) {
  const { t } = useI18n();
  const body = loading ? (
    <LoadingPanel label={t("common.loading")} />
  ) : error ? (
    <ErrorPanel title={t("classes.membersLoadFailed")} action={<Button variant="outline" onClick={onRefresh}>{t("common.refresh")}</Button>} />
  ) : members.length ? (
    <TableShell>
      <table className="min-w-[680px] w-full text-sm">
        <colgroup>
          <col className="w-[22%]" />
          <col className="w-[22%]" />
          <col className="w-[28%]" />
          <col className="w-[16%]" />
          <col className="w-[12%]" />
        </colgroup>
        <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
          <tr>
            <th className="px-4 py-3 text-left">{t("common.account")}</th>
            <th className="px-4 py-3 text-left">{t("common.displayName")}</th>
            <th className="px-4 py-3 text-left">{t("common.email")}</th>
            <th className="px-4 py-3 text-left">{t("common.roles")}</th>
            <th className="px-4 py-3 text-center">{t("common.actions")}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[var(--oj-border-soft)]">
          {members.map((member) => (
            <tr key={member.userId}>
              <td className="px-4 py-3 font-medium text-[var(--oj-ink)]">
                <TruncatedText value={member.account} />
              </td>
              <td className="px-4 py-3 text-[var(--oj-ink)]">
                <TruncatedText value={member.displayName} />
              </td>
              <td className="px-4 py-3 text-[var(--oj-ink-muted)]">
                <TruncatedText value={member.email || "--"} />
              </td>
              <td className="px-4 py-3">
                <Badge tone={memberRoleTone(member.role)}>{t(`classes.memberRole.${member.role}`)}</Badge>
              </td>
              <td className="px-4 py-3">
                <div className="flex justify-center">
                  <Button size="sm" variant="outline" disabled={member.role === "OWNER"} onClick={() => onRemove(member)}>
                    {t("common.remove")}
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableShell>
  ) : (
    <EmptyState title={t("classes.noMembersTitle")} description={t("classes.noMembersCopy")} />
  );

  return (
    <Card className="min-w-0 rounded-xl shadow-none">
      <CardBody className="min-w-0 space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-[var(--oj-ink)]">{title}</h2>
            <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("classes.membersCopy")}</p>
          </div>
          <Button variant="outline" onClick={onAdd}>
            <UserPlus className="size-4" aria-hidden="true" />
            {t("classes.addMember")}
          </Button>
        </div>
        {body}
      </CardBody>
    </Card>
  );
}

function TruncatedText({ value }: { value: string }) {
  return <span className="block truncate" title={value}>{value}</span>;
}

function GroupEditorPanel({
  target,
  onOpenChange,
  onSaved
}: {
  target: GroupEditorTarget | null;
  onOpenChange: (open: boolean) => void;
  onSaved: (group: LearningGroupResponse) => Promise<void>;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const [name, setName] = React.useState("");
  const [description, setDescription] = React.useState("");
  const [saving, setSaving] = React.useState(false);
  const open = Boolean(target);

  const baselineSnapshot = React.useMemo(() => {
    if (!open) return null;
    return JSON.stringify({ name: target?.item?.name ?? "", description: target?.item?.description ?? "" });
  }, [open, target]);

  const dirty = baselineSnapshot !== null && JSON.stringify({ name, description }) !== baselineSnapshot;

  React.useEffect(() => {
    if (!target) return;
    setName(target.item?.name ?? "");
    setDescription(target.item?.description ?? "");
  }, [target]);

  async function save() {
    if (!target || !dirty) return;
    if (!name.trim()) {
      toast.error(t("classes.nameRequired"));
      return;
    }
    setSaving(true);
    try {
      const saved = target.item
        ? await api.updateClass(target.item.id, { name: name.trim(), description: description.trim() || undefined })
        : await api.createClass({ name: name.trim(), description: description.trim() || undefined });
      toast.success(t(target.item ? "classes.updatedMessage" : "classes.createdMessage"));
      await onSaved(saved);
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("classes.saveFailed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={target?.item ? t("classes.editClass") : t("classes.createClass")}
      description={t("classes.classEditorCopy")}
      footer={(
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={saving || !dirty} onClick={() => void save()}>{saving ? t("common.loading") : t("common.save")}</Button>
        </div>
      )}
    >
      <div className="space-y-4">
        <Field label={t("classes.name")}>
          <input className={inputClass} value={name} onChange={(event) => setName(event.target.value)} placeholder={t("classes.namePlaceholder")} />
        </Field>
        <Field label={t("classes.descriptionLabel")}>
          <textarea className={`${textareaClass} min-h-32`} value={description} onChange={(event) => setDescription(event.target.value)} placeholder={t("classes.descriptionPlaceholder")} />
        </Field>
      </div>
    </SidePanel>
  );
}

function MemberAddPanel({
  target,
  existingMembers,
  onOpenChange,
  onSaved
}: {
  target: MemberTarget | null;
  existingMembers: LearningGroupMemberResponse[];
  onOpenChange: (open: boolean) => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const [keyword, setKeyword] = React.useState("");
  const [selectedUsers, setSelectedUsers] = React.useState<AdminUserResponse[]>([]);
  const [role, setRole] = React.useState<LearningGroupMemberRole>("STUDENT");
  const [result, setResult] = React.useState<LearningGroupMemberBatchAddResponse | null>(null);
  const [saving, setSaving] = React.useState(false);
  const open = Boolean(target);
  const trimmedKeyword = keyword.trim();

  const existingUserIds = React.useMemo(() => new Set(existingMembers.map((member) => member.userId)), [existingMembers]);
  const selectedUserIds = React.useMemo(() => new Set(selectedUsers.map((user) => user.userId)), [selectedUsers]);

  const usersQuery = useQuery({
    queryKey: ["learning-class-member-candidates", trimmedKeyword],
    queryFn: () => api.users({ page: 1, pageSize: 20, keyword: trimmedKeyword, enabled: true, lifecycle: "ACTIVE" }),
    enabled: open && trimmedKeyword.length > 0
  });

  const candidates = usersQuery.data?.records ?? [];

  React.useEffect(() => {
    if (!target) return;
    setKeyword("");
    setSelectedUsers([]);
    setRole("STUDENT");
    setResult(null);
  }, [target]);

  function toggleUser(user: AdminUserResponse) {
    if (existingUserIds.has(user.userId)) return;
    setResult(null);
    setSelectedUsers((current) => {
      if (current.some((item) => item.userId === user.userId)) {
        return current.filter((item) => item.userId !== user.userId);
      }
      return [...current, user];
    });
  }

  async function save() {
    if (!target) return;
    setResult(null);
    if (!selectedUsers.length) {
      toast.error(t("classes.memberSelectionRequired"));
      return;
    }
    setSaving(true);
    try {
      const response = await api.addClassMembers(target.classId, { userIds: selectedUsers.map((user) => user.userId), role });
      setResult(response);
      if (response.failed > 0) {
        toast.error(t("classes.membersAddPartial", { succeeded: response.succeeded, failed: response.failed }));
      } else {
        toast.success(t("classes.membersAddedMessage", { count: response.succeeded }));
      }
      setSelectedUsers([]);
      await onSaved();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("classes.memberSaveFailed"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      title={t("classes.addMember")}
      description={target ? t("classes.addMemberCopy", { name: target.title }) : undefined}
      footer={(
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button disabled={saving} onClick={() => void save()}>
            {saving ? t("common.loading") : t("classes.addSelectedMembers")}
          </Button>
        </div>
      )}
    >
      <div className="space-y-4">
        <Field label={t("classes.memberRoleLabel")}>
          <select className={selectClass} value={role} onChange={(event) => setRole(event.target.value as LearningGroupMemberRole)}>
            <option value="STUDENT">{t("classes.memberRole.STUDENT")}</option>
            <option value="ASSISTANT">{t("classes.memberRole.ASSISTANT")}</option>
            <option value="TEACHER">{t("classes.memberRole.TEACHER")}</option>
          </select>
        </Field>
        <Field label={t("classes.memberSearch")} hint={t("classes.memberSearchHint")}>
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--oj-ink-muted)]" aria-hidden="true" />
            <input
              className={`${inputClass} pl-9`}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder={t("classes.memberSearchPlaceholder")}
            />
          </div>
        </Field>
        {selectedUsers.length ? (
          <div className="rounded-xl border border-blue-100 bg-blue-50 p-3">
            <div className="text-xs font-semibold text-blue-900">{t("classes.selectedMembers", { count: selectedUsers.length })}</div>
            <div className="mt-2 flex flex-wrap gap-2">
              {selectedUsers.map((user) => (
                <button
                  key={user.userId}
                  type="button"
                  className="inline-flex max-w-full items-center gap-1 rounded-full border border-blue-200 bg-white px-2.5 py-1 text-xs font-medium text-blue-800"
                  onClick={() => toggleUser(user)}
                  title={`${user.account} ${user.displayName}`}
                >
                  <span className="truncate">{user.account}</span>
                  <X className="size-3" aria-hidden="true" />
                </button>
              ))}
            </div>
          </div>
        ) : null}
        <div className="rounded-xl border border-[var(--oj-border-soft)]">
          <div className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-xs font-semibold text-[var(--oj-ink-muted)]">
            {t("classes.memberCandidates")}
          </div>
          <div className="max-h-80 divide-y divide-[var(--oj-border-soft)] overflow-y-auto">
            {!trimmedKeyword ? (
              <div className="px-3 py-6 text-center text-sm text-[var(--oj-ink-muted)]">{t("classes.memberSearchPrompt")}</div>
            ) : usersQuery.isLoading ? (
              <div className="px-3 py-6 text-center text-sm text-[var(--oj-ink-muted)]">{t("common.loading")}</div>
            ) : usersQuery.isError ? (
              <div className="px-3 py-6 text-center text-sm text-red-700">{t("classes.memberSearchFailed")}</div>
            ) : candidates.length ? (
              candidates.map((user) => {
                const alreadyMember = existingUserIds.has(user.userId);
                const selected = selectedUserIds.has(user.userId);
                return (
                  <button
                    key={user.userId}
                    type="button"
                    disabled={alreadyMember}
                    className={cn(
                      "flex w-full items-center gap-3 px-3 py-3 text-left transition-colors",
                      alreadyMember ? "cursor-not-allowed bg-[var(--oj-surface-muted)] opacity-70" : "hover:bg-blue-50",
                      selected ? "bg-blue-50" : ""
                    )}
                    onClick={() => toggleUser(user)}
                  >
                    <span className={cn(
                      "flex size-5 shrink-0 items-center justify-center rounded-md border",
                      selected ? "border-[var(--oj-primary)] bg-[var(--oj-primary)] text-white" : "border-[var(--oj-border)] bg-white text-transparent"
                    )}>
                      <CheckCircle2 className="size-3.5" aria-hidden="true" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-semibold text-[var(--oj-ink)]">{user.account}</span>
                      <span className="block truncate text-xs text-[var(--oj-ink-muted)]">
                        {(user.displayName || user.email || user.userId)}
                      </span>
                    </span>
                    {alreadyMember ? <Badge tone="neutral">{t("classes.alreadyMember")}</Badge> : null}
                  </button>
                );
              })
            ) : (
              <div className="px-3 py-6 text-center text-sm text-[var(--oj-ink-muted)]">{t("classes.memberSearchEmpty")}</div>
            )}
          </div>
        </div>
        {result ? (
          <BatchResultPanel result={result} />
        ) : null}
      </div>
    </SidePanel>
  );
}

function BatchResultPanel({ result }: { result: LearningGroupMemberBatchAddResponse }) {
  const { t } = useI18n();
  const failed = result.results.filter((item) => item.status === "FAILED");
  const visible = failed.length ? failed : result.results.slice(0, 8);
  return (
    <div className={cn(
      "rounded-xl border p-3",
      result.failed ? "border-amber-200 bg-amber-50" : "border-green-200 bg-green-50"
    )}>
      <div className="text-sm font-semibold text-[var(--oj-ink)]">
        {t("classes.batchAddSummary", { succeeded: result.succeeded, failed: result.failed, total: result.requested })}
      </div>
      <div className="mt-2 space-y-1.5">
        {visible.map((item, index) => (
          <div key={`${item.userId ?? "unknown"}-${index}`} className="flex items-start justify-between gap-3 rounded-lg bg-white/70 px-2.5 py-2 text-xs">
            <span className="min-w-0">
              <span className="block truncate font-medium text-[var(--oj-ink)]">
                {item.account || item.displayName || item.userId || "--"}
              </span>
              {item.message ? <span className="mt-0.5 block break-words text-[var(--oj-ink-muted)]">{item.message}</span> : null}
            </span>
            <Badge tone={batchStatusTone(item.status)}>{t(`classes.batchStatus.${item.status}`)}</Badge>
          </div>
        ))}
      </div>
    </div>
  );
}

function batchStatusTone(status: LearningGroupMemberBatchAddStatus) {
  if (status === "FAILED") return "red";
  if (status === "UPDATED") return "blue";
  if (status === "UNCHANGED") return "neutral";
  return "green";
}

function StatusChip({ status }: { status: LearningGroupStatus }) {
  const { t } = useI18n();
  return <Badge tone={status === "ACTIVE" ? "green" : "neutral"}>{t(`classes.status.${status}`)}</Badge>;
}

function memberRoleTone(role: LearningGroupMemberRole) {
  if (role === "OWNER") return "red";
  if (role === "TEACHER") return "blue";
  if (role === "ASSISTANT") return "amber";
  return "green";
}

function confirmTitle(target: RemovalTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  if (target.kind === "class-member") return t("classes.removeMemberConfirm");
  if (target.kind === "class-archive") return t("classes.archiveConfirm");
  if (target.kind === "class-restore") return t("classes.restoreConfirm");
  return t("classes.deleteConfirm");
}

function confirmDescription(target: RemovalTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  if (target.kind === "class-member") return target.label;
  if (target.kind === "class-delete") return `${target.name}\n${t("classes.deleteConfirmDescription")}`;
  return target.name;
}

function confirmLabel(target: RemovalTarget | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!target) return "";
  if (target.kind === "class-member") return t("common.remove");
  if (target.kind === "class-archive") return t("classes.archive");
  if (target.kind === "class-restore") return t("classes.restore");
  return t("common.delete");
}

function readableClassActionError(caught: unknown, t: (key: string) => string, locale: "zh-CN" | "en-US") {
  return readableCaughtError(caught, locale, t("classes.actionFailed"));
}

async function invalidateAll(queryClient: ReturnType<typeof useQueryClient>) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["learning-classes"] }),
    queryClient.invalidateQueries({ queryKey: ["learning-class-members"] })
  ]);
}
