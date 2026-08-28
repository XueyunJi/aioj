import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Ban, CheckCircle2, ChevronDown, Download, Eye, EyeOff, Pencil, RotateCcw, Save, ShieldCheck, Target, Trash2, XCircle } from "lucide-react";
import { ApiError, api, type AiLearningProfileEvidenceResponse, type AiLearningProfileResponse, type AiMemoryCandidateActionPayload, type AiMemoryCandidateResponse, type AiMemoryCategory, type AiMemoryReviewDetailResponse, type AiMemoryReviewListItemResponse, type BinaryFileResponse, type EntityId } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ConfirmDialog, EmptyState, ErrorPanel, Field, LoadingPanel, PageSection, inputClass, textareaClass } from "../components/Common";
import { useAuth } from "../lib/auth";
import { formatDateTime } from "../lib/format";
import { useI18n } from "../lib/i18n";

const MEMORY_CATEGORY_VALUES = new Set<AiMemoryCategory>(["memory", "habit", "rule", "preference", "weakness", "teaching_style"]);
const CANDIDATE_REVIEWABLE_STATUSES = new Set(["CANDIDATE", "NEEDS_CONFIRMATION", "AWAITING_CLARIFICATION"]);
const CANDIDATE_PROCESSING_STATUSES = new Set(["MERGE_QUEUED"]);
const CANDIDATE_VISIBLE_STATUSES = new Set([...CANDIDATE_REVIEWABLE_STATUSES, ...CANDIDATE_PROCESSING_STATUSES]);
const CANDIDATE_TERMINAL_STATUSES = new Set(["ACTIVE", "MERGED", "REJECTED", "RESOLVED", "SUPERSEDED", "DISABLED"]);
const PROFILE_GROUPS = ["weakness", "mastery", "algorithm", "habit", "preference", "other"] as const;
const PROFILE_TABS = ["account", "learning", "candidates", "archive"] as const;

const DEFAULT_MEMORY_TYPE: Record<AiMemoryCategory, string> = {
  memory: "manual_note",
  habit: "habit",
  rule: "rule",
  preference: "guidance_preference",
  weakness: "weakness",
  teaching_style: "teaching_style"
};

type LearningProfileGroup = typeof PROFILE_GROUPS[number];
type ProfileTab = typeof PROFILE_TABS[number];
type PendingProfileAction = { type: "disable" | "delete"; profile: AiLearningProfileResponse } | null;
type NoticeTone = "success" | "warning" | "error";
type NoticeState = { tone: NoticeTone; message: string };
type CandidateOptimisticSnapshot = {
  id: EntityId;
  candidates?: AiMemoryCandidateResponse[];
  detail?: AiMemoryReviewDetailResponse;
};

const PASSWORD_MIN_LENGTH = 8;

const NOTICE_STYLES: Record<NoticeTone, { className: string; icon: React.ComponentType<{ className?: string; "aria-hidden"?: boolean }> }> = {
  success: {
    className: "border-emerald-200 bg-emerald-50 text-emerald-950",
    icon: CheckCircle2
  },
  warning: {
    className: "border-amber-200 bg-amber-50 text-amber-950",
    icon: AlertTriangle
  },
  error: {
    className: "border-red-200 bg-red-50 text-red-950",
    icon: XCircle
  }
};

function candidateCategory(candidate: AiMemoryCandidateResponse | AiMemoryReviewListItemResponse): AiMemoryCategory {
  switch (candidate.category) {
    case "RULE":
      return "rule";
    case "PREFERENCE":
      return "preference";
    case "HABIT":
      return "habit";
    case "WEAKNESS":
      return "weakness";
    default:
      return "memory";
  }
}

function defaultMemoryType(category: AiMemoryCategory, candidate?: AiMemoryCandidateResponse | AiMemoryReviewListItemResponse | null) {
  if (candidate?.category === "PROFILE") return "name_preference";
  if (candidate?.category === "GOAL") return "learning_direction";
  return DEFAULT_MEMORY_TYPE[category] ?? "manual_note";
}

function normalizeCategory(value: string): AiMemoryCategory {
  return MEMORY_CATEGORY_VALUES.has(value as AiMemoryCategory) ? value as AiMemoryCategory : "memory";
}

function isResolutionCandidate(candidate: AiMemoryCandidateResponse) {
  return candidate.candidateKind === "WEAKNESS_RESOLUTION";
}

function queuedCandidate<T extends AiMemoryCandidateResponse | AiMemoryReviewListItemResponse>(candidate: T): T {
  return {
    ...candidate,
    status: "MERGE_QUEUED",
    needsConfirmation: false,
    updatedAt: new Date().toISOString()
  };
}

function candidateStatusTone(candidate: AiMemoryCandidateResponse | AiMemoryReviewListItemResponse): "blue" | "amber" | "neutral" {
  if (isCandidateProcessingStatus(candidate.status)) return "blue";
  if (candidate.needsConfirmation) return "amber";
  return "neutral";
}

function candidateStatusLabel(candidate: AiMemoryCandidateResponse | AiMemoryReviewListItemResponse, t: ReturnType<typeof useI18n>["t"]) {
  if (isCandidateProcessingStatus(candidate.status)) return t("profile.memoryCandidateMergeQueued");
  if (candidate.status === "MERGED") return t("profile.memoryCandidateMerged");
  if (candidate.status === "ACTIVE") return t("profile.memoryCandidateActive");
  if (candidate.status === "REJECTED") return t("profile.memoryCandidateRejected");
  if (candidate.status === "AWAITING_CLARIFICATION") return t("profile.memoryCandidateAwaitingClarification");
  if (candidate.status === "NEEDS_CONFIRMATION") return t("profile.memoryCandidateNeedsConfirmation");
  return t("profile.memoryCandidateStatus");
}

function isCandidateReviewableStatus(status?: string | null) {
  return CANDIDATE_REVIEWABLE_STATUSES.has((status ?? "").toUpperCase());
}

function isCandidateProcessingStatus(status?: string | null) {
  return CANDIDATE_PROCESSING_STATUSES.has((status ?? "").toUpperCase());
}

function isCandidateTerminalStatus(status?: string | null) {
  const normalized = (status ?? "").toUpperCase();
  return CANDIDATE_TERMINAL_STATUSES.has(normalized) || (!CANDIDATE_VISIBLE_STATUSES.has(normalized) && Boolean(normalized));
}

function firstApiFieldDetail(error: ApiError) {
  return (
    error.fieldError("newPassword") ||
    error.fieldError("currentPassword") ||
    Object.values(error.details ?? {}).find((value) => Boolean(value?.trim())) ||
    null
  );
}

function errorNotice(error: unknown, fallback: string): NoticeState {
  if (error instanceof ApiError) {
    return {
      tone: "error",
      message: firstApiFieldDetail(error) || error.userMessage || fallback
    };
  }
  if (error instanceof Error && error.message.trim()) {
    return { tone: "error", message: error.message };
  }
  return { tone: "error", message: fallback };
}

function normalizedState(profile: AiLearningProfileResponse) {
  return (profile.state || "CANDIDATE").toUpperCase();
}

function learningProfileGroup(profile: AiLearningProfileResponse): LearningProfileGroup {
  const state = normalizedState(profile);
  const category = profile.category.toLowerCase();
  const key = profile.key.toLowerCase();
  if (state === "RESOLVED") return "mastery";
  if (category.includes("weakness")) return "weakness";
  if (category.includes("habit") || key.includes("habit") || key.includes("coding")) return "habit";
  if (category.includes("preference") || category.includes("teaching") || key.includes("preference") || key.includes("style")) return "preference";
  if (category.includes("algorithm") || category.includes("mastery")) return "algorithm";
  if (state === "CANDIDATE" || state === "ACTIVE") return "weakness";
  return "other";
}

function profileStateTone(state: string): "blue" | "green" | "amber" | "red" | "neutral" {
  switch (state.toUpperCase()) {
    case "ACTIVE":
      return "green";
    case "CANDIDATE":
      return "amber";
    case "RESOLVED":
      return "blue";
    case "DISABLED":
      return "red";
    default:
      return "neutral";
  }
}

function displaySafeEvidence(value?: string | null) {
  if (!value) return "";
  const withoutFences = value.replace(/```[\s\S]*?```/g, "");
  const kept: string[] = [];
  let skippingRawOutput = false;
  for (const rawLine of withoutFences.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    const lower = line.toLowerCase();
    if (/^(stdout|stderr|stdoutexcerpt|stderrexcerpt)\s*[:=]/i.test(line)) {
      skippingRawOutput = true;
      continue;
    }
    if (skippingRawOutput) {
      if (!line || /^[A-Za-z][A-Za-z0-9 _-]{0,40}:/.test(line)) {
        skippingRawOutput = false;
      } else {
        continue;
      }
    }
    if (/(sk-[a-z0-9_-]{4,}|bearer\s+[a-z0-9._-]{8,}|(token|secret|password|api[_-]?key)\s*[:=])/i.test(line)) {
      continue;
    }
    if (/^(#include|using namespace|int main|public class|public static void main|def main|import sys|if __name__|cin >>|cout <<)/i.test(line)) {
      continue;
    }
    if (line) kept.push(line);
  }
  return kept.join("\n");
}

export function ProfileView() {
  const { t } = useI18n();
  const auth = useAuth();
  const queryClient = useQueryClient();

  const [displayName, setDisplayName] = React.useState(auth.profile?.displayName ?? "");
  const [email, setEmail] = React.useState(auth.profile?.email ?? "");
  const [currentPassword, setCurrentPassword] = React.useState("");
  const [newPassword, setNewPassword] = React.useState("");
  const [confirmPassword, setConfirmPassword] = React.useState("");
  const [notice, setNotice] = React.useState<NoticeState | null>(null);
  const [activeTab, setActiveTab] = React.useState<ProfileTab>("account");
  const [selectedCandidateId, setSelectedCandidateId] = React.useState<EntityId | null>(null);
  const [editingCandidate, setEditingCandidate] = React.useState<AiMemoryCandidateResponse | AiMemoryReviewListItemResponse | null>(null);
  const [editCategory, setEditCategory] = React.useState<AiMemoryCategory>("memory");
  const [editTitle, setEditTitle] = React.useState("");
  const [editMemoryType, setEditMemoryType] = React.useState("manual_note");
  const [editContent, setEditContent] = React.useState("");
  const [expandedProfileId, setExpandedProfileId] = React.useState<string | null>(null);
  const [editingProfile, setEditingProfile] = React.useState<AiLearningProfileResponse | null>(null);
  const [profileLabel, setProfileLabel] = React.useState("");
  const [pendingProfileAction, setPendingProfileAction] = React.useState<PendingProfileAction>(null);

  React.useEffect(() => {
    setDisplayName(auth.profile?.displayName ?? "");
    setEmail(auth.profile?.email ?? "");
  }, [auth.profile?.displayName, auth.profile?.email]);

  const candidatesQuery = useQuery({
    queryKey: ["ai", "memory-candidates"],
    queryFn: () => api.aiMemoryCandidates()
  });

  const learningProfilesQuery = useQuery({
    queryKey: ["ai", "learning-profile"],
    queryFn: () => api.aiLearningProfiles()
  });

  const evidenceQuery = useQuery({
    queryKey: ["ai", "learning-profile-evidence", expandedProfileId],
    queryFn: () => api.aiLearningProfileEvidence(expandedProfileId!),
    enabled: Boolean(expandedProfileId)
  });

  const candidateDetailQuery = useQuery({
    queryKey: ["ai", "memory-candidate-detail", selectedCandidateId],
    queryFn: () => api.aiMemoryCandidateDetail(selectedCandidateId!),
    enabled: Boolean(selectedCandidateId)
  });

  const memoryCandidates = React.useMemo(
    () => (candidatesQuery.data ?? []).filter((candidate) => CANDIDATE_VISIBLE_STATUSES.has(candidate.status)),
    [candidatesQuery.data]
  );

  const groupedProfiles = React.useMemo(() => {
    const groups = new Map<LearningProfileGroup, AiLearningProfileResponse[]>();
    PROFILE_GROUPS.forEach((group) => groups.set(group, []));
    for (const profile of learningProfilesQuery.data ?? []) {
      groups.get(learningProfileGroup(profile))?.push(profile);
    }
    return groups;
  }, [learningProfilesQuery.data]);

  const profileStats = React.useMemo(() => {
    const profiles = learningProfilesQuery.data ?? [];
    return {
      total: profiles.length,
      activeWeaknesses: profiles.filter((item) => learningProfileGroup(item) === "weakness" && normalizedState(item) === "ACTIVE").length,
      candidates: profiles.filter((item) => normalizedState(item) === "CANDIDATE").length,
      mastered: profiles.filter((item) => normalizedState(item) === "RESOLVED").length,
      evidence: profiles.reduce((sum, item) => sum + (item.evidenceCount ?? 0), 0)
    };
  }, [learningProfilesQuery.data]);

  const invalidateCandidates = React.useCallback(
    () => queryClient.invalidateQueries({ queryKey: ["ai", "memory-candidates"] }),
    [queryClient]
  );

  const invalidateLearningProfiles = React.useCallback(
    () => Promise.all([
      queryClient.invalidateQueries({ queryKey: ["ai", "learning-profile"] }),
      queryClient.invalidateQueries({ queryKey: ["ai", "learning-profile-evidence"] })
    ]),
    [queryClient]
  );

  const invalidateLearningArea = React.useCallback(
    () => Promise.all([invalidateCandidates(), invalidateLearningProfiles()]),
    [invalidateCandidates, invalidateLearningProfiles]
  );

  const showNotice = React.useCallback((tone: NoticeTone, message: string) => {
    setNotice({ tone, message });
  }, []);

  const markCandidateMergeQueued = React.useCallback(async (id: EntityId): Promise<CandidateOptimisticSnapshot> => {
    const candidateKey = ["ai", "memory-candidates"] as const;
    const detailKey = ["ai", "memory-candidate-detail", id] as const;
    await Promise.all([
      queryClient.cancelQueries({ queryKey: candidateKey }),
      queryClient.cancelQueries({ queryKey: detailKey })
    ]);
    const snapshot: CandidateOptimisticSnapshot = {
      id,
      candidates: queryClient.getQueryData<AiMemoryCandidateResponse[]>(candidateKey),
      detail: queryClient.getQueryData<AiMemoryReviewDetailResponse>(detailKey)
    };
    queryClient.setQueryData<AiMemoryCandidateResponse[]>(candidateKey, (current) =>
      current?.map((candidate) => candidate.id === id ? queuedCandidate(candidate) : candidate)
    );
    queryClient.setQueryData<AiMemoryReviewDetailResponse>(detailKey, (current) =>
      current?.candidate.id === id ? { ...current, candidate: queuedCandidate(current.candidate) } : current
    );
    return snapshot;
  }, [queryClient]);

  const rollbackCandidateMergeQueued = React.useCallback((snapshot?: CandidateOptimisticSnapshot) => {
    if (!snapshot) return;
    queryClient.setQueryData(["ai", "memory-candidates"], snapshot.candidates);
    queryClient.setQueryData(["ai", "memory-candidate-detail", snapshot.id], snapshot.detail);
  }, [queryClient]);

  const profileMutation = useMutation({
    mutationFn: () => auth.updateProfile({ displayName: displayName.trim(), email: email.trim() || undefined }),
    onSuccess: () => showNotice("success", t("profile.profileUpdated")),
    onError: (error) => setNotice(errorNotice(error, t("profile.profileUpdateFailed")))
  });

  const passwordMutation = useMutation({
    mutationFn: () => auth.changePassword({ currentPassword, newPassword }),
    onSuccess: () => {
      showNotice("success", t("profile.passwordUpdated"));
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.passwordUpdateFailed")))
  });

  const acceptCandidate = useMutation({
    mutationFn: (id: EntityId) => api.acceptAiMemoryCandidate(id),
    onMutate: markCandidateMergeQueued,
    onSuccess: async (_data, id) => {
      showNotice("success", t("profile.memoryCandidateMergeQueued"));
      await invalidateLearningArea();
      await queryClient.invalidateQueries({ queryKey: ["ai", "memory-candidate-detail", id] });
    },
    onError: (error, _id, snapshot) => {
      rollbackCandidateMergeQueued(snapshot);
      setNotice(errorNotice(error, t("profile.memoryCandidateActionFailed")));
    }
  });

  const rejectCandidate = useMutation({
    mutationFn: (id: EntityId) => api.rejectAiMemoryCandidate(id, "user_rejected"),
    onSuccess: async () => {
      showNotice("success", t("profile.memoryCandidateRejected"));
      setSelectedCandidateId(null);
      await invalidateLearningArea();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.memoryCandidateActionFailed")))
  });

  const acceptCandidateWithEdit = useMutation({
    mutationFn: ({ id, payload }: { id: EntityId; payload: AiMemoryCandidateActionPayload }) =>
      api.acceptAiMemoryCandidateWithEdit(id, payload),
    onMutate: async ({ id }) => {
      const snapshot = await markCandidateMergeQueued(id);
      closeEditor();
      return snapshot;
    },
    onSuccess: async (_data, { id }) => {
      showNotice("success", t("profile.memoryCandidateMergeQueued"));
      await invalidateLearningArea();
      await queryClient.invalidateQueries({ queryKey: ["ai", "memory-candidate-detail", id] });
    },
    onError: (error, _input, snapshot) => {
      rollbackCandidateMergeQueued(snapshot);
      setNotice(errorNotice(error, t("profile.memoryCandidateActionFailed")));
    }
  });

  const setProfileActive = useMutation({
    mutationFn: (profile: AiLearningProfileResponse) => api.updateAiLearningProfile(profile.id, {
      state: "ACTIVE",
      note: "student confirmed learning profile"
    }),
    onSuccess: async () => {
      showNotice("success", t("profile.learningProfileMarkedWeak"));
      await invalidateLearningProfiles();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningProfileActionFailed")))
  });

  const saveLearningProfileLabel = useMutation({
    mutationFn: () => {
      if (!editingProfile) throw new Error("No learning profile selected");
      return api.updateAiLearningProfile(editingProfile.id, {
        label: profileLabel.trim(),
        note: "student edited learning profile label"
      });
    },
    onSuccess: async () => {
      showNotice("success", t("profile.learningProfileSaved"));
      closeProfileEditor();
      await invalidateLearningProfiles();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningProfileActionFailed")))
  });

  const markLearningProfileMastered = useMutation({
    mutationFn: (profile: AiLearningProfileResponse) => api.markAiLearningProfileMastered(profile.id),
    onSuccess: async () => {
      showNotice("success", t("profile.learningProfileMastered"));
      await invalidateLearningProfiles();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningProfileActionFailed")))
  });

  const disableLearningProfile = useMutation({
    mutationFn: (profile: AiLearningProfileResponse) => api.disableAiLearningProfile(profile.id),
    onSuccess: async (_, profile) => {
      showNotice("success", t("profile.learningProfileDisabled"));
      if (expandedProfileId === profile.id) setExpandedProfileId(null);
      await invalidateLearningProfiles();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningProfileActionFailed")))
  });

  const deleteLearningProfile = useMutation({
    mutationFn: (profile: AiLearningProfileResponse) => api.deleteAiLearningProfile(profile.id),
    onSuccess: async (_, profile) => {
      showNotice("success", t("profile.learningProfileDeleted"));
      if (expandedProfileId === profile.id) setExpandedProfileId(null);
      await invalidateLearningProfiles();
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningProfileActionFailed")))
  });

  const downloadLearningArchive = useMutation({
    mutationFn: () => api.downloadAiMemoryArchiveMarkdown(),
    onSuccess: (file) => {
      downloadBinaryFile(file);
      showNotice("success", t("profile.learningArchiveExported"));
    },
    onError: (error) => setNotice(errorNotice(error, t("profile.learningArchiveExportFailed")))
  });

  const selectedCandidateStatus = candidateDetailQuery.data?.candidate.status;

  React.useEffect(() => {
    if (!selectedCandidateId || !isCandidateProcessingStatus(selectedCandidateStatus)) return undefined;
    let cancelled = false;
    const detailKey = ["ai", "memory-candidate-detail", selectedCandidateId] as const;
    const candidateKey = ["ai", "memory-candidates"] as const;
    const pollCandidate = async () => {
      try {
        const [detail, candidates] = await Promise.all([
          api.aiMemoryCandidateDetail(selectedCandidateId),
          api.aiMemoryCandidates()
        ]);
        if (cancelled) return;
        queryClient.setQueryData(detailKey, detail);
        queryClient.setQueryData(candidateKey, candidates);
      } catch {
        if (!cancelled) {
          void candidateDetailQuery.refetch();
          void invalidateCandidates();
        }
      }
    };
    void pollCandidate();
    const timer = window.setInterval(() => {
      void pollCandidate();
    }, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [candidateDetailQuery, invalidateCandidates, queryClient, selectedCandidateId, selectedCandidateStatus]);

  React.useEffect(() => {
    if (!selectedCandidateId || !isCandidateTerminalStatus(selectedCandidateStatus)) return;
    setSelectedCandidateId(null);
    showNotice("success", t("profile.memoryCandidateProcessed"));
    void invalidateLearningArea();
  }, [invalidateLearningArea, selectedCandidateId, selectedCandidateStatus, showNotice, t]);

  React.useEffect(() => {
    if (!selectedCandidateId && !editingCandidate) return undefined;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (editingCandidate) {
        closeEditor();
      } else {
        setSelectedCandidateId(null);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [editingCandidate, selectedCandidateId]);

  if (auth.loading && !auth.profile) return <LoadingPanel label={t("profile.loading")} />;
  if (!auth.profile) return <ErrorPanel title={t("profile.loadFailed")} />;

  function openEditor(candidate: AiMemoryCandidateResponse | AiMemoryReviewListItemResponse) {
    const category = candidateCategory(candidate);
    setEditingCandidate(candidate);
    setEditCategory(category);
    setEditTitle(candidate.memoryKey || "");
    setEditMemoryType(defaultMemoryType(category, candidate));
    setEditContent(candidate.canonicalText);
  }

  function closeEditor() {
    setEditingCandidate(null);
    setEditCategory("memory");
    setEditTitle("");
    setEditMemoryType("manual_note");
    setEditContent("");
  }

  function openProfileEditor(profile: AiLearningProfileResponse) {
    setEditingProfile(profile);
    setProfileLabel(profile.label || "");
  }

  function closeProfileEditor() {
    setEditingProfile(null);
    setProfileLabel("");
  }

  function handleProfileSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!displayName.trim()) {
      showNotice("warning", t("profile.displayNameRequired"));
      return;
    }
    profileMutation.mutate();
  }

  function handlePasswordSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!currentPassword) {
      showNotice("warning", t("profile.currentPasswordRequired"));
      return;
    }
    if (!newPassword) {
      showNotice("warning", t("profile.passwordTooShort"));
      return;
    }
    if (newPassword.length < PASSWORD_MIN_LENGTH) {
      showNotice("warning", t("profile.passwordTooShort"));
      return;
    }
    if (newPassword !== confirmPassword) {
      showNotice("warning", t("profile.passwordMismatch"));
      return;
    }
    if (newPassword === currentPassword) {
      showNotice("warning", t("profile.passwordSameAsCurrent"));
      return;
    }
    passwordMutation.mutate();
  }

  function handleCandidateEditSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editingCandidate) return;
    if (!editContent.trim()) {
      showNotice("warning", t("profile.memoryRequired"));
      return;
    }
    acceptCandidateWithEdit.mutate({
      id: editingCandidate.id,
      payload: {
        category: editCategory,
        title: editTitle.trim() || undefined,
        memoryType: editMemoryType.trim() || defaultMemoryType(editCategory, editingCandidate),
        canonicalText: editContent.trim(),
        reason: "user_edited_candidate"
      }
    });
  }

  function handleProfileEditSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!profileLabel.trim()) {
      showNotice("warning", t("profile.learningProfileLabelRequired"));
      return;
    }
    saveLearningProfileLabel.mutate();
  }

  async function confirmPendingProfileAction() {
    if (!pendingProfileAction) return;
    if (pendingProfileAction.type === "disable") {
      await disableLearningProfile.mutateAsync(pendingProfileAction.profile);
    } else {
      await deleteLearningProfile.mutateAsync(pendingProfileAction.profile);
    }
  }

  return (
    <div className="mx-auto flex max-w-[1500px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageSection
        eyebrow={t("profile.eyebrow")}
        title={t("profile.title")}
        description={t("profile.description")}
      />

      {notice ? (
        <div className={cn("flex items-start gap-2 rounded-2xl border p-4 text-sm", NOTICE_STYLES[notice.tone].className)}>
          {React.createElement(NOTICE_STYLES[notice.tone].icon, { className: "mt-0.5 size-4 shrink-0", "aria-hidden": true })}
          <p>{notice.message}</p>
        </div>
      ) : null}

      <div className="flex flex-wrap gap-2 rounded-2xl border border-[var(--oj-border-soft)] bg-white p-2" role="tablist" aria-label={t("profile.tabsLabel")}>
        {PROFILE_TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            className={cn(
              "rounded-xl px-4 py-2 text-sm font-medium text-[var(--oj-ink-muted)] transition-colors hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)]",
              activeTab === tab && "bg-[var(--oj-primary)] text-white hover:bg-[var(--oj-primary)] hover:text-white"
            )}
            onClick={() => setActiveTab(tab)}
          >
            {t(`profile.tab.${tab}`)}
          </button>
        ))}
      </div>

      {activeTab === "account" ? (
      <section className="grid gap-4 xl:grid-cols-[1.1fr_0.9fr]">
        <form className="rounded-2xl border border-[var(--oj-border)] bg-white p-5" onSubmit={handleProfileSubmit}>
          <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("profile.currentAccount")}</h2>
              <p className="mt-1 text-sm text-[var(--oj-ink-muted)]">{t("profile.updateProfileCopy")}</p>
            </div>
            {auth.profile.passwordResetRequired ? <Badge tone="amber">{t("auth.forcePasswordTitle")}</Badge> : null}
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <Field label={t("common.account")}>
              <ReadonlyValue value={auth.profile.account} />
            </Field>
            <Field label={t("profile.userId")}>
              <ReadonlyValue value={String(auth.profile.userId)} />
            </Field>
            <Field label={t("common.displayName")}>
              <input className={inputClass} value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
            </Field>
            <Field label={t("common.email")}>
              <input className={inputClass} value={email} onChange={(event) => setEmail(event.target.value)} />
            </Field>
          </div>
          <div className="mt-5 flex justify-end">
            <Button disabled={profileMutation.isPending}>
              <Save className="size-4" aria-hidden="true" />
              {t("profile.saveProfile")}
            </Button>
          </div>
        </form>

        <form className="rounded-2xl border border-[var(--oj-border)] bg-white p-5" onSubmit={handlePasswordSubmit}>
          <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("profile.changePassword")}</h2>
          <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("profile.passwordRule")}</p>
          <div className="mt-5 space-y-4">
            <Field label={t("profile.currentPassword")}>
              <input className={inputClass} type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
            </Field>
            <Field label={t("profile.newPassword")}>
              <input className={inputClass} type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} />
            </Field>
            <Field label={t("profile.confirmPassword")}>
              <input className={inputClass} type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} />
            </Field>
          </div>
          <div className="mt-5 flex justify-end">
            <Button disabled={passwordMutation.isPending}>
              <Save className="size-4" aria-hidden="true" />
              {t("profile.updatePassword")}
            </Button>
          </div>
        </form>
      </section>
      ) : null}

      {activeTab === "learning" ? (
      <section className="space-y-4">
        <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] pb-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-[var(--oj-ink)]">{t("profile.learningProfileTitle")}</h2>
            <p className="mt-1 max-w-[76ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{t("profile.learningProfileCopy")}</p>
          </div>
          <div className="flex flex-wrap items-center gap-2 lg:justify-end">
            <Badge tone={profileStats.total ? "blue" : "neutral"}>{t("profile.learningProfileCount", { count: profileStats.total })}</Badge>
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <ProfileStat label={t("profile.learningProfileStatActiveWeakness")} value={profileStats.activeWeaknesses} tone="amber" />
          <ProfileStat label={t("profile.learningProfileStatCandidates")} value={profileStats.candidates} tone="blue" />
          <ProfileStat label={t("profile.learningProfileStatMastered")} value={profileStats.mastered} tone="green" />
          <ProfileStat label={t("profile.learningProfileStatEvidence")} value={profileStats.evidence} tone="neutral" />
        </div>

        {learningProfilesQuery.isLoading ? <LoadingPanel label={t("profile.loading")} /> : null}
        {learningProfilesQuery.isError ? <ErrorPanel title={t("profile.learningProfileLoadFailed")} /> : null}
        {!learningProfilesQuery.isLoading && !learningProfilesQuery.isError && !profileStats.total ? (
          <EmptyState title={t("profile.learningProfileEmptyTitle")} description={t("profile.learningProfileEmptyCopy")} />
        ) : null}
        {!learningProfilesQuery.isLoading && !learningProfilesQuery.isError && profileStats.total ? (
          <div className="grid gap-5">
            {PROFILE_GROUPS.map((group) => {
              const profiles = groupedProfiles.get(group) ?? [];
              if (!profiles.length) return null;
              return (
                <section key={group} className="space-y-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-base font-semibold text-[var(--oj-ink)]">{t(`profile.learningProfileGroup.${group}`)}</h3>
                    <Badge tone="neutral">{t("profile.learningProfileGroupCount", { count: profiles.length })}</Badge>
                  </div>
                  <div className="grid gap-3">
                    {profiles.map((profile) => (
                      <LearningProfileCard
                        key={profile.id}
                        profile={profile}
                        expanded={expandedProfileId === profile.id}
                        editing={editingProfile?.id === profile.id}
                        profileLabel={profileLabel}
                        profileLabelPending={saveLearningProfileLabel.isPending}
                        evidence={expandedProfileId === profile.id ? evidenceQuery.data ?? profile.evidence ?? [] : profile.evidence ?? []}
                        evidenceLoading={expandedProfileId === profile.id && evidenceQuery.isLoading}
                        evidenceError={expandedProfileId === profile.id && evidenceQuery.isError}
                        busy={setProfileActive.isPending || markLearningProfileMastered.isPending || disableLearningProfile.isPending || deleteLearningProfile.isPending}
                        onToggleEvidence={() => setExpandedProfileId((current) => current === profile.id ? null : profile.id)}
                        onEdit={() => openProfileEditor(profile)}
                        onCancelEdit={closeProfileEditor}
                        onChangeLabel={setProfileLabel}
                        onSubmitEdit={handleProfileEditSubmit}
                        onSetActive={() => setProfileActive.mutate(profile)}
                        onMarkMastered={() => markLearningProfileMastered.mutate(profile)}
                        onDisable={() => setPendingProfileAction({ type: "disable", profile })}
                        onDelete={() => setPendingProfileAction({ type: "delete", profile })}
                      />
                    ))}
                  </div>
                </section>
              );
            })}
          </div>
        ) : null}
      </section>
      ) : null}

      {activeTab === "candidates" ? (
      <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
        <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] pb-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-[var(--oj-ink)]">{t("profile.memoryCandidatesTitle")}</h2>
            <p className="mt-1 max-w-[72ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{t("profile.memoryCandidatesCopy")}</p>
          </div>
          <Badge tone={memoryCandidates.length ? "amber" : "neutral"}>{t("profile.memoryCandidatesCount", { count: memoryCandidates.length })}</Badge>
        </div>

        <div className="mt-4 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-950">
          <div className="flex gap-3">
            <ShieldCheck className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
            <div>
              <h3 className="font-semibold">{t("profile.memoryPrivacyTitle")}</h3>
              <p className="mt-1">{t("profile.memoryPrivacyCopy")}</p>
            </div>
          </div>
        </div>

        <div className="mt-4 space-y-3">
          {candidatesQuery.isLoading ? <div className="py-4"><LoadingPanel label={t("profile.loading")} /></div> : null}
          {candidatesQuery.isError ? <div className="py-4"><ErrorPanel title={t("profile.memoryCandidateLoadFailed")} /></div> : null}
          {!candidatesQuery.isLoading && !candidatesQuery.isError && memoryCandidates.length ? memoryCandidates.map((candidate) => (
            <article key={candidate.id} className="rounded-2xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={candidateStatusTone(candidate)}>{candidateStatusLabel(candidate, t)}</Badge>
                    <Badge tone="neutral">{candidate.category}</Badge>
                    <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("profile.memoryCandidateScore", { score: candidate.writeScore.toFixed(2) })}</span>
                  </div>
                  <h3 className="mt-3 text-base font-semibold text-[var(--oj-ink)]">{candidate.memoryKey || t("profile.memoryCandidateStatus")}</h3>
                  <p className="mt-2 max-w-[86ch] whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink-muted)]">{displaySafeEvidence(candidate.canonicalText) || t("profile.learningProfileEvidenceRedacted")}</p>
                  {(candidate.qualityFlags.length || candidate.ambiguityFlags.length) ? (
                    <div className="mt-3 flex flex-wrap gap-2 text-xs text-[var(--oj-ink-muted)]">
                      {candidate.qualityFlags.length ? <span>{t("profile.memoryCandidateFlags", { flags: candidate.qualityFlags.join(", ") })}</span> : null}
                      {candidate.ambiguityFlags.length ? <span>{t("profile.memoryCandidateFlags", { flags: candidate.ambiguityFlags.join(", ") })}</span> : null}
                    </div>
                  ) : null}
                </div>
                <div className="flex shrink-0 flex-wrap gap-2 lg:justify-end">
                  <Button type="button" size="sm" onClick={() => setSelectedCandidateId(candidate.id)}>
                    <Eye className="size-4" aria-hidden="true" />
                    {t("profile.memoryCandidateView")}
                  </Button>
                </div>
              </div>
            </article>
          )) : null}
          {!candidatesQuery.isLoading && !candidatesQuery.isError && !memoryCandidates.length ? (
            <div className="py-4"><EmptyState title={t("profile.noAiMemoryTitle")} description={t("profile.memoryCandidatesEmpty")} /></div>
          ) : null}
        </div>
      </section>
      ) : null}

      {activeTab === "archive" ? (
        <section className="rounded-2xl border border-[var(--oj-border)] bg-white p-5">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div className="min-w-0">
              <h2 className="text-lg font-semibold text-[var(--oj-ink)]">{t("profile.learningArchiveTitle")}</h2>
              <p className="mt-1 max-w-[72ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{t("profile.learningArchiveCopy")}</p>
            </div>
            <Button
              type="button"
              variant="outline"
              title={t("profile.learningArchiveDownloadCopy")}
              disabled={downloadLearningArchive.isPending}
              onClick={() => downloadLearningArchive.mutate()}
            >
              <Download className="size-4" aria-hidden="true" />
              {t("profile.learningArchiveDownload")}
            </Button>
          </div>
          <div className="mt-5 rounded-2xl border border-emerald-200 bg-emerald-50 p-4 text-sm leading-6 text-emerald-950">
            <div className="flex gap-3">
              <ShieldCheck className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
              <p>{t("profile.learningArchiveSafetyCopy")}</p>
            </div>
          </div>
        </section>
      ) : null}

      {selectedCandidateId ? (
        <CandidateDetailDialog
          detail={candidateDetailQuery.data}
          loading={candidateDetailQuery.isLoading}
          error={candidateDetailQuery.isError}
          accepting={acceptCandidate.isPending}
          rejecting={rejectCandidate.isPending}
          onClose={() => setSelectedCandidateId(null)}
          onAccept={(id) => acceptCandidate.mutate(id)}
          onReject={(id) => rejectCandidate.mutate(id)}
          onEdit={openEditor}
          t={t}
        />
      ) : null}

      {editingCandidate ? (
        <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 px-4 py-6" role="presentation" onClick={closeEditor}>
          <form
            className="flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl border border-blue-200 bg-blue-50 shadow-2xl"
            onClick={(event) => event.stopPropagation()}
            onSubmit={handleCandidateEditSubmit}
          >
            <div className="flex items-start justify-between gap-3 border-b border-blue-200 px-5 py-4">
              <div>
                <h2 className="text-base font-semibold text-blue-950">{t("profile.memoryCandidateEditTitle")}</h2>
                <p className="mt-1 text-sm leading-6 text-blue-900">{t("profile.memoryCandidateEditCopy")}</p>
              </div>
              <Button type="button" size="sm" variant="ghost" onClick={closeEditor}>
                <XCircle className="size-4" aria-hidden="true" />
                {t("common.close")}
              </Button>
            </div>
            <div className="min-h-0 overflow-y-auto px-5 py-4 pr-7 [scrollbar-gutter:stable]">
              <div className="grid gap-4 md:grid-cols-3">
                <Field label={t("profile.memoryCategory")}>
                  <select className={inputClass} value={editCategory} onChange={(event) => {
                    const next = normalizeCategory(event.target.value);
                    setEditCategory(next);
                    setEditMemoryType(defaultMemoryType(next, editingCandidate));
                  }}>
                    {Array.from(MEMORY_CATEGORY_VALUES).map((category) => (
                      <option key={category} value={category}>{t(`profile.memoryCategoryValue.${category}`)}</option>
                    ))}
                  </select>
                </Field>
                <Field label={t("profile.memoryTitle")}>
                  <input className={inputClass} value={editTitle} onChange={(event) => setEditTitle(event.target.value)} />
                </Field>
                <Field label={t("profile.memoryType")}>
                  <input className={inputClass} value={editMemoryType} onChange={(event) => setEditMemoryType(event.target.value)} />
                </Field>
              </div>
              <div className="mt-4">
                <Field label={t("profile.memoryContent")}>
                  <textarea className={textareaClass} rows={5} value={editContent} onChange={(event) => setEditContent(event.target.value)} />
                </Field>
              </div>
              <div className="mt-5 flex flex-wrap justify-end gap-2">
                <Button type="button" variant="outline" onClick={closeEditor}>{t("common.cancel")}</Button>
                <Button disabled={!editContent.trim() || acceptCandidateWithEdit.isPending}>
                  <Save className="size-4" aria-hidden="true" />
                  {t("profile.memoryCandidateEditSave")}
                </Button>
              </div>
            </div>
          </form>
        </div>
      ) : null}

      <ConfirmDialog
        open={Boolean(pendingProfileAction)}
        onOpenChange={(open) => {
          if (!open) setPendingProfileAction(null);
        }}
        title={pendingProfileAction?.type === "delete" ? t("profile.learningProfileDeleteTitle") : t("profile.learningProfileDisableTitle")}
        description={pendingProfileAction?.type === "delete" ? t("profile.learningProfileDeleteConfirm") : t("profile.learningProfileDisableConfirm")}
        cancelLabel={t("common.cancel")}
        confirmLabel={pendingProfileAction?.type === "delete" ? t("common.delete") : t("profile.learningProfileDisable")}
        onConfirm={confirmPendingProfileAction}
      />
    </div>
  );
}

function ReadonlyValue({ value }: { value: string }) {
  return (
    <div className="min-h-10 rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-sm text-[var(--oj-ink)]">
      <span className="block truncate" title={value}>{value}</span>
    </div>
  );
}

function ProfileStat({
  label,
  value,
  tone
}: {
  label: string;
  value: number;
  tone: "blue" | "green" | "amber" | "red" | "neutral";
}) {
  return (
    <div className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
      <p className="text-xs font-medium text-[var(--oj-ink-muted)]">{label}</p>
      <div className="mt-2 flex items-end justify-between gap-2">
        <span className="text-2xl font-semibold tabular-nums text-[var(--oj-ink)]">{value}</span>
        <Badge tone={tone}>{value}</Badge>
      </div>
    </div>
  );
}

function CandidateDetailDialog({
  detail,
  loading,
  error,
  accepting,
  rejecting,
  onClose,
  onAccept,
  onReject,
  onEdit,
  t
}: {
  detail?: AiMemoryReviewDetailResponse;
  loading: boolean;
  error: boolean;
  accepting: boolean;
  rejecting: boolean;
  onClose: () => void;
  onAccept: (id: EntityId) => void;
  onReject: (id: EntityId) => void;
  onEdit: (candidate: AiMemoryReviewListItemResponse) => void;
  t: ReturnType<typeof useI18n>["t"];
}) {
  const candidate = detail?.candidate;
  const reviewable = candidate ? isCandidateReviewableStatus(candidate.status) : false;
  const processing = candidate ? isCandidateProcessingStatus(candidate.status) : false;
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 px-4 py-6" role="presentation" onClick={onClose}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="memory-candidate-detail-title"
        className="flex max-h-[90vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white shadow-2xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] px-5 py-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0">
            <p className="text-xs font-semibold text-[var(--oj-primary)]">{t("profile.memoryCandidateDetailEyebrow")}</p>
            <h2 id="memory-candidate-detail-title" className="mt-1 text-lg font-semibold text-[var(--oj-ink)]">
              {candidate?.memoryKey || t("profile.memoryCandidateDetailTitle")}
            </h2>
            <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("profile.memoryCandidateDetailCopy")}</p>
          </div>
          <Button type="button" size="sm" variant="outline" onClick={onClose}>
            <XCircle className="size-4" aria-hidden="true" />
            {t("common.close")}
          </Button>
        </div>

        <div className="min-h-0 overflow-y-auto px-5 py-4 pr-7 [scrollbar-gutter:stable]">
          {loading ? <LoadingPanel label={t("profile.loading")} /> : null}
          {error ? <ErrorPanel title={t("profile.memoryCandidateDetailFailed")} /> : null}
          {!loading && !error && detail && candidate ? (
            <div className="space-y-4">
              <div className="rounded-2xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={candidateStatusTone(candidate)}>{candidateStatusLabel(candidate, t)}</Badge>
                  <Badge tone="neutral">{candidate.category}</Badge>
                  <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("profile.memoryCandidateScore", { score: candidate.writeScore.toFixed(2) })}</span>
                </div>
                <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink)]">
                  {displaySafeEvidence(candidate.canonicalText) || t("profile.learningProfileEvidenceRedacted")}
                </p>
                {(candidate.qualityFlags.length || candidate.ambiguityFlags.length) ? (
                  <div className="mt-3 flex flex-wrap gap-2 text-xs text-[var(--oj-ink-muted)]">
                    {candidate.qualityFlags.map((flag) => <Badge key={`q-${flag}`} tone="neutral">{flag}</Badge>)}
                    {candidate.ambiguityFlags.map((flag) => <Badge key={`a-${flag}`} tone="amber">{flag}</Badge>)}
                  </div>
                ) : null}
              </div>

              <div className="grid gap-4 xl:grid-cols-3">
                <SafeDetailColumn title={t("profile.memoryCandidateEvidenceTitle")} empty={t("profile.memoryCandidateEvidenceEmpty")}>
                  {detail.evidence.map((item) => (
                    <SafeSummaryCard key={item.id} title={`${item.evidenceType} / ${Number(item.confidence ?? 0).toFixed(3)}`} body={item.evidenceText} />
                  ))}
                </SafeDetailColumn>
                <SafeDetailColumn title={t("profile.memoryCandidateRelatedMemories")} empty={t("profile.memoryCandidateNoRelatedMemories")}>
                  {detail.relatedMemories.map((item) => (
                    <SafeSummaryCard key={item.id} title={`#${item.id} ${item.memoryType}`} body={item.content} />
                  ))}
                </SafeDetailColumn>
                <SafeDetailColumn title={t("profile.memoryCandidateRelatedProfiles")} empty={t("profile.memoryCandidateNoRelatedProfiles")}>
                  {detail.relatedProfiles.map((item) => (
                    <SafeSummaryCard key={item.id} title={`${item.profileKey} / ${item.state}`} body={item.label} />
                  ))}
                </SafeDetailColumn>
              </div>

              <div className="flex flex-wrap justify-end gap-2 border-t border-[var(--oj-border-soft)] pt-4">
                <Button type="button" disabled={accepting || !reviewable} onClick={() => onAccept(candidate.id)}>
                  <CheckCircle2 className="size-4" aria-hidden="true" />
                  {processing ? t("profile.memoryCandidateMergeQueued") : reviewable ? t("profile.memoryCandidateAccept") : t("profile.memoryCandidateProcessed")}
                </Button>
                <Button type="button" variant="outline" disabled={!reviewable} onClick={() => onEdit(candidate)}>
                  <Pencil className="size-4" aria-hidden="true" />
                  {t("profile.memoryCandidateEditAccept")}
                </Button>
                <Button type="button" variant="ghost" className="text-red-700 hover:bg-red-50" disabled={rejecting || !reviewable} onClick={() => onReject(candidate.id)}>
                  <XCircle className="size-4" aria-hidden="true" />
                  {t("profile.memoryCandidateReject")}
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}

function SafeDetailColumn({ title, empty, children }: { title: string; empty: string; children: React.ReactNode[] }) {
  const items = React.Children.toArray(children).filter(Boolean);
  return (
    <section className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
      <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{title}</h3>
      <div className="mt-3 space-y-3">
        {items.length ? items : <p className="text-sm text-[var(--oj-ink-muted)]">{empty}</p>}
      </div>
    </section>
  );
}

function SafeSummaryCard({ title, body }: { title: string; body?: string | null }) {
  return (
    <article className="rounded-xl bg-[var(--oj-surface-muted)] p-3">
      <h4 className="break-words text-sm font-medium text-[var(--oj-ink)]">{title}</h4>
      <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-[var(--oj-ink-muted)]">
        {displaySafeEvidence(body) || "—"}
      </p>
    </article>
  );
}

function LearningProfileCard({
  profile,
  expanded,
  editing,
  profileLabel,
  profileLabelPending,
  evidence,
  evidenceLoading,
  evidenceError,
  busy,
  onToggleEvidence,
  onEdit,
  onCancelEdit,
  onChangeLabel,
  onSubmitEdit,
  onSetActive,
  onMarkMastered,
  onDisable,
  onDelete
}: {
  profile: AiLearningProfileResponse;
  expanded: boolean;
  editing: boolean;
  profileLabel: string;
  profileLabelPending: boolean;
  evidence: AiLearningProfileEvidenceResponse[];
  evidenceLoading: boolean;
  evidenceError: boolean;
  busy: boolean;
  onToggleEvidence: () => void;
  onEdit: () => void;
  onCancelEdit: () => void;
  onChangeLabel: (value: string) => void;
  onSubmitEdit: (event: React.FormEvent<HTMLFormElement>) => void;
  onSetActive: () => void;
  onMarkMastered: () => void;
  onDisable: () => void;
  onDelete: () => void;
}) {
  const { t } = useI18n();
  const state = normalizedState(profile);
  const canSetActive = state === "CANDIDATE" || state === "RESOLVED" || state === "SUPERSEDED";
  const canMarkMastered = state !== "RESOLVED";

  return (
    <article className="rounded-2xl border border-[var(--oj-border-soft)] bg-white p-4">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={profileStateTone(state)}>{t(`profile.learningProfileState.${state}`, undefined, state)}</Badge>
            <Badge tone="neutral">{profile.category}</Badge>
            {profile.confidence != null ? <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{t("profile.learningProfileConfidence", { value: profile.confidence.toFixed(2) })}</span> : null}
            <span className="text-xs text-[var(--oj-ink-muted)]">{t("profile.learningProfileEvidenceCount", { count: profile.evidenceCount ?? 0 })}</span>
          </div>
          <h4 className="mt-3 text-base font-semibold text-[var(--oj-ink)]">{profile.label || profile.key}</h4>
          <p className="mt-1 break-words text-xs text-[var(--oj-ink-soft)]">{profile.key}</p>
          <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{t("profile.learningProfileLastEvidence", { time: formatDateTime(profile.lastEvidenceAt) })}</p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2 lg:justify-end">
          <Button type="button" size="sm" variant="outline" onClick={onToggleEvidence}>
            {expanded ? <EyeOff className="size-4" aria-hidden="true" /> : <Eye className="size-4" aria-hidden="true" />}
            {expanded ? t("profile.learningProfileHideEvidence") : t("profile.learningProfileShowEvidence")}
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={onEdit}>
            <Pencil className="size-4" aria-hidden="true" />
            {t("profile.learningProfileEdit")}
          </Button>
          {canSetActive ? (
            <Button type="button" size="sm" disabled={busy} onClick={onSetActive}>
              {state === "CANDIDATE" ? <CheckCircle2 className="size-4" aria-hidden="true" /> : <RotateCcw className="size-4" aria-hidden="true" />}
              {state === "CANDIDATE" ? t("profile.learningProfileConfirm") : t("profile.learningProfileMarkStillWeak")}
            </Button>
          ) : null}
          {canMarkMastered ? (
            <Button type="button" size="sm" variant="outline" disabled={busy} onClick={onMarkMastered}>
              <Target className="size-4" aria-hidden="true" />
              {t("profile.learningProfileMarkMastered")}
            </Button>
          ) : null}
          <Button type="button" size="sm" variant="ghost" className="text-amber-700 hover:bg-amber-50" disabled={busy} onClick={onDisable}>
            <Ban className="size-4" aria-hidden="true" />
            {t("profile.learningProfileDisable")}
          </Button>
          <Button type="button" size="sm" variant="ghost" className="text-red-700 hover:bg-red-50" disabled={busy} onClick={onDelete}>
            <Trash2 className="size-4" aria-hidden="true" />
            {t("common.delete")}
          </Button>
        </div>
      </div>

      {editing ? (
        <form className="mt-4 rounded-xl border border-blue-200 bg-blue-50 p-4" onSubmit={onSubmitEdit}>
          <Field label={t("profile.learningProfileLabel")}>
            <input className={inputClass} value={profileLabel} onChange={(event) => onChangeLabel(event.target.value)} />
          </Field>
          <div className="mt-4 flex flex-wrap justify-end gap-2">
            <Button type="button" variant="outline" onClick={onCancelEdit}>{t("common.cancel")}</Button>
            <Button disabled={!profileLabel.trim() || profileLabelPending}>
              <Save className="size-4" aria-hidden="true" />
              {t("common.save")}
            </Button>
          </div>
        </form>
      ) : null}

      {expanded ? (
        <div className="mt-4 border-t border-[var(--oj-border-soft)] pt-4">
          <div className="mb-3 flex items-center gap-2">
            <ChevronDown className="size-4 text-[var(--oj-ink-muted)]" aria-hidden="true" />
            <h5 className="text-sm font-semibold text-[var(--oj-ink)]">{t("profile.learningProfileEvidenceTitle")}</h5>
          </div>
          {evidenceLoading ? <LoadingPanel label={t("profile.loading")} /> : null}
          {evidenceError ? <ErrorPanel title={t("profile.learningProfileEvidenceFailed")} /> : null}
          {!evidenceLoading && !evidenceError && evidence.length ? (
            <div className="space-y-3">
              {evidence.map((item) => (
                <div key={item.id} className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="neutral">{item.evidenceType}</Badge>
                    <span className="text-xs text-[var(--oj-ink-muted)]">{item.sourceType}{item.sourceId ? ` #${item.sourceId}` : ""}</span>
                    {item.confidence != null ? <span className="text-xs tabular-nums text-[var(--oj-ink-muted)]">{item.confidence.toFixed(2)}</span> : null}
                    <span className="text-xs text-[var(--oj-ink-soft)]">{formatDateTime(item.createdAt)}</span>
                  </div>
                  <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-[var(--oj-ink-muted)]">{displaySafeEvidence(item.summary) || t("profile.learningProfileEvidenceRedacted")}</p>
                </div>
              ))}
            </div>
          ) : null}
          {!evidenceLoading && !evidenceError && !evidence.length ? (
            <p className="text-sm text-[var(--oj-ink-muted)]">{t("profile.learningProfileEvidenceEmpty")}</p>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}

function downloadBinaryFile(file: BinaryFileResponse) {
  const url = URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.fileName || "aioj-learning-archive.md";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
