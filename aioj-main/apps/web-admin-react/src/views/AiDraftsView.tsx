import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { RotateCw, Sparkles } from "lucide-react";
import { ApiError, api, type AiUsageResponse, type PageResponse, type ProblemDraftGenerationJobResponse, type ProblemDraftResponse, type ProblemVisibility } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { AiDraftDetailPanel, type AiDraftAction } from "../components/AiDraftDetailPanel";
import { ConfirmDialog, EmptyState, ErrorPanel, Field, LoadingPanel, PageHeader, PaginationRow, SidePanel, TableShell, inputClass, selectClass, textareaClass } from "../components/Common";
import { AiDraftJobsPanel } from "./AiDraftJobsView";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { difficultyTone, formatDateTime, shortId } from "../lib/format";
import { readableCaughtError } from "../lib/readableError";

const SOLUTION_LANGUAGES = ["cpp", "python", "java"];
const PAGE_SIZE = 30;
const JOBS_PAGE_SIZE = 20;

type DraftStatus = "PENDING_REVIEW" | "APPROVED";
type ValidationFilter = "" | "VALID" | "INVALID";
type SortOrder = "newest" | "oldest";
type LifecycleFilter = "ACTIVE" | "ARCHIVED" | "ALL";
type MainTab = "generate" | "jobs" | "box";
type ConfirmAction = { action: "archive" | "restore" | "delete" | "manualReview"; draft: ProblemDraftResponse };
type TFunction = ReturnType<typeof useI18n>["t"];

export function AiDraftsView() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const search = useSearch({ from: "/app/ai-drafts" });
  const [topic, setTopic] = React.useState("");
  const [cfRating, setCfRating] = React.useState("");
  const [teachingGoal, setTeachingGoal] = React.useState("");
  const [algorithm, setAlgorithm] = React.useState("");
  const [tagsText, setTagsText] = React.useState("");
  const [scenario, setScenario] = React.useState("");
  const [inputOutputSpec, setInputOutputSpec] = React.useState("");
  const [dataConstraints, setDataConstraints] = React.useState("");
  const [qualityRequirements, setQualityRequirements] = React.useState("");
  const [standardSolutionLanguage, setStandardSolutionLanguage] = React.useState("cpp");
  const [statementRequirement, setStatementRequirement] = React.useState("");
  const [testcaseRequirement, setTestcaseRequirement] = React.useState("");
  const [solutionRequirement, setSolutionRequirement] = React.useState("");
  const [targetHiddenCaseCount, setTargetHiddenCaseCount] = React.useState("");
  const [enableAutoRepair, setEnableAutoRepair] = React.useState(false);
  const [enableReferenceCheck, setEnableReferenceCheck] = React.useState(false);
  const [referenceCheckTouched, setReferenceCheckTouched] = React.useState(false);
  const [mainTab, setMainTab] = React.useState<MainTab>("generate");
  const [activeStatus, setActiveStatus] = React.useState<DraftStatus>("PENDING_REVIEW");
  const [validationFilter, setValidationFilter] = React.useState<ValidationFilter>("");
  const [lifecycleFilter, setLifecycleFilter] = React.useState<LifecycleFilter>("ALL");
  const [mineOnly, setMineOnly] = React.useState(false);
  const [sortOrder, setSortOrder] = React.useState<SortOrder>("newest");
  const [page, setPage] = React.useState(1);
  const [selectedDraft, setSelectedDraft] = React.useState<ProblemDraftResponse | null>(null);
  const [detailOpen, setDetailOpen] = React.useState(false);
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({});
  const [generateError, setGenerateError] = React.useState<string | null>(null);
  const [listError, setListError] = React.useState<string | null>(null);
  const [generating, setGenerating] = React.useState(false);
  const [createdJob, setCreatedJob] = React.useState<ProblemDraftGenerationJobResponse | null>(null);
  const [confirmAction, setConfirmAction] = React.useState<ConfirmAction | null>(null);
  const [importTarget, setImportTarget] = React.useState<ProblemDraftResponse | null>(null);
  const [importVisibility, setImportVisibility] = React.useState<ProblemVisibility>("PUBLIC");
  const [rejectTarget, setRejectTarget] = React.useState<ProblemDraftResponse | null>(null);
  const [rejectReason, setRejectReason] = React.useState("");
  const [rejecting, setRejecting] = React.useState(false);
  const filtersReadyRef = React.useRef(false);
  const openedSearchDraftRef = React.useRef<string | null>(null);

  const usageQuery = useQuery({
    queryKey: ["admin-ai-usage"],
    queryFn: () => api.usage()
  });

  const draftsQuery = useQuery({
    queryKey: ["admin-drafts", activeStatus, validationFilter, lifecycleFilter, mineOnly, sortOrder, auth.profile?.userId, page],
    queryFn: () => api.problemDrafts({
      page,
      pageSize: PAGE_SIZE,
      status: activeStatus,
      validationStatus: validationFilter || undefined,
      creatorUserId: mineOnly ? auth.profile?.userId : undefined,
      sort: sortOrder,
      lifecycleStatus: lifecycleFilter
    })
  });

  const pendingCountQuery = useQuery({
    queryKey: ["admin-drafts-count", "PENDING_REVIEW", "ALL"],
    queryFn: () => api.problemDrafts({ page: 1, pageSize: 1, status: "PENDING_REVIEW", lifecycleStatus: "ALL" })
  });

  const approvedCountQuery = useQuery({
    queryKey: ["admin-drafts-count", "APPROVED", "ALL"],
    queryFn: () => api.problemDrafts({ page: 1, pageSize: 1, status: "APPROVED", lifecycleStatus: "ALL" })
  });

  const approveMutation = useMutation({
    mutationFn: (draft: ProblemDraftResponse) => api.approveDraft(draft.id, false),
    onSuccess: async () => {
      toast.success(t("drafts.approvedMessage"));
      setSelectedDraft(null);
      setDetailOpen(false);
      await refreshDrafts();
    }
  });

  const importMutation = useMutation({
    mutationFn: ({ draft, visibility }: { draft: ProblemDraftResponse; visibility: ProblemVisibility }) =>
      api.approveDraft(draft.id, true, visibility),
    onSuccess: async (draft) => {
      toast.success(t("drafts.importedMessage"));
      setSelectedDraft(draft);
      setImportTarget(null);
      await refreshDrafts();
    },
    onError: (caught) => {
      toast.error(readableCaughtError(caught, locale, t("drafts.importFailed")));
    }
  });

  const records = draftsQuery.data?.records ?? [];
  const total = draftsQuery.data?.total ?? 0;
  const pendingCount = pendingCountQuery.data?.total ?? 0;
  const approvedCount = approvedCountQuery.data?.total ?? 0;
  const detailPendingAction: AiDraftAction | null = approveMutation.isPending
    ? "approve"
    : importMutation.isPending
      ? "import"
      : rejecting
        ? "reject"
        : null;

  React.useEffect(() => {
    if (!filtersReadyRef.current) {
      filtersReadyRef.current = true;
      return;
    }
    setPage(1);
  }, [activeStatus, validationFilter, lifecycleFilter, mineOnly, sortOrder, auth.profile?.userId]);

  React.useEffect(() => {
    if (!draftsQuery.data) return;
    const totalPages = Math.max(1, Math.ceil(draftsQuery.data.total / PAGE_SIZE));
    if (page > totalPages) setPage(totalPages);
  }, [draftsQuery.data, page]);

  const refreshDrafts = React.useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["admin-drafts"] }),
      queryClient.invalidateQueries({ queryKey: ["admin-drafts-count"] }),
      queryClient.invalidateQueries({ queryKey: ["admin-ai-usage"] })
    ]);
  }, [queryClient]);

  const selectMainTab = React.useCallback((tab: MainTab) => {
    setMainTab(tab);
    if (tab === "generate") {
      setCreatedJob(null);
    }
    void navigate({ to: "/ai-drafts", search: { tab }, replace: true });
  }, [navigate]);

  const openDraftById = React.useCallback(async (
    draftId: NonNullable<ProblemDraftGenerationJobResponse["draftId"]>,
    options: { syncUrl?: boolean } = {}
  ) => {
    const normalizedDraftId = String(draftId);
    openedSearchDraftRef.current = normalizedDraftId;
    setMainTab("box");
    setListError(null);
    setSelectedDraft(null);
    setDetailOpen(true);
    if (options.syncUrl !== false) {
      void navigate({ to: "/ai-drafts", search: { draftId: normalizedDraftId }, replace: true });
    }
    try {
      const draft = await api.problemDraft(normalizedDraftId);
      setSelectedDraft(draft);
      await refreshDrafts();
    } catch (caught) {
      setDetailOpen(false);
      setListError(readableCaughtError(caught, locale, t("drafts.loadFailed")));
    }
  }, [locale, navigate, refreshDrafts, t]);

  React.useEffect(() => {
    const draftId = search.draftId;
    if (!draftId || openedSearchDraftRef.current === draftId) return;
    void openDraftById(draftId, { syncUrl: false });
  }, [openDraftById, search.draftId]);

  React.useEffect(() => {
    if (search.draftId) return;
    if (search.tab === "generate" || search.tab === "jobs" || search.tab === "box") {
      setMainTab(search.tab);
      if (search.tab === "generate") {
        setCreatedJob(null);
      }
    }
  }, [search.draftId, search.tab]);

  React.useEffect(() => {
    if (referenceCheckTouched) return;
    const rating = cfRating.trim() ? Number(cfRating.trim()) : undefined;
    setEnableReferenceCheck(rating !== undefined && Number.isInteger(rating) && rating >= 1600);
  }, [cfRating, referenceCheckTouched]);

  async function openDetail(draft: ProblemDraftResponse) {
    setListError(null);
    setSelectedDraft(draft);
    setDetailOpen(true);
    try {
      setSelectedDraft(await api.problemDraft(draft.id));
    } catch (caught) {
      setListError(readableCaughtError(caught, locale, t("drafts.loadFailed")));
    }
  }

  async function generateDraft() {
    setGenerateError(null);
    setFieldErrors({});
    const nextFieldErrors: Record<string, string> = {};
    const cfRatingValue = cfRating.trim() ? Number(cfRating.trim()) : undefined;
    const targetHiddenCaseCountValue = targetHiddenCaseCount.trim() ? Number(targetHiddenCaseCount.trim()) : undefined;
    const tags = tagsText.split(/[,\n]/).map((item) => item.trim()).filter(Boolean);
    if (!topic.trim()) {
      nextFieldErrors.topic = t("drafts.topicRequired");
    }
    if (cfRatingValue === undefined || !Number.isInteger(cfRatingValue) || cfRatingValue < 800 || cfRatingValue > 3500) {
      nextFieldErrors.cfRating = t("drafts.cfRatingRange");
    }
    if (!SOLUTION_LANGUAGES.includes(standardSolutionLanguage)) {
      nextFieldErrors.standardSolutionLanguage = t("drafts.standardSolutionLanguageInvalid");
    }
    if (targetHiddenCaseCountValue !== undefined && (!Number.isInteger(targetHiddenCaseCountValue) || targetHiddenCaseCountValue < 0)) {
      nextFieldErrors.targetHiddenCaseCount = t("drafts.targetHiddenCaseCountInvalid");
    }
    if (Object.keys(nextFieldErrors).length > 0 || cfRatingValue === undefined) {
      setFieldErrors(nextFieldErrors);
      return;
    }
    setGenerating(true);
    setCreatedJob(null);
    try {
      const payload = {
        topic: topic.trim(),
        difficulty: difficultyFromCfRating(cfRatingValue),
        cfRating: cfRatingValue,
        teachingGoal: teachingGoal.trim() || undefined,
        algorithm: algorithm.trim() || undefined,
        tags: tags.length > 0 ? tags : undefined,
        scenario: scenario.trim() || undefined,
        inputOutputSpec: inputOutputSpec.trim() || undefined,
        dataConstraints: dataConstraints.trim() || undefined,
        qualityRequirements: qualityRequirements.trim() || undefined,
        standardSolutionLanguage,
        problemInfoRequirement: undefined,
        statementRequirement: statementRequirement.trim() || undefined,
        testcaseRequirement: testcaseRequirement.trim() || undefined,
        targetHiddenCaseCount: targetHiddenCaseCountValue,
        solutionRequirement: solutionRequirement.trim() || undefined,
        explanationRequirement: undefined,
        enableAutoRepair,
        enableReferenceCheck
      };
      const job = await api.createProblemDraftGenerationJob(payload);
      setCreatedJob(job);
      resetGenerateForm();
      queryClient.setQueryData<PageResponse<ProblemDraftGenerationJobResponse>>(
        ["admin-ai-draft-generation-jobs", 1, ""],
        (current) => upsertGenerationJobPage(current, job)
      );
      selectMainTab("jobs");
      await queryClient.invalidateQueries({ queryKey: ["admin-ai-draft-generation-jobs"] });
    } catch (caught) {
      if (caught instanceof ApiError) {
        setFieldErrors(caught.details ?? {});
        setGenerateError(caught.userMessage);
      } else {
        setGenerateError(readableCaughtError(caught, locale, t("drafts.generateFailed")));
      }
    } finally {
      setGenerating(false);
    }
  }

  function handleDetailOpenChange(open: boolean) {
    setDetailOpen(open);
    if (open || !search.draftId) return;
    openedSearchDraftRef.current = null;
    void refreshDrafts();
    void navigate({ to: "/ai-drafts", search: { tab: "box" }, replace: true });
  }

  function resetGenerateForm() {
    setTopic("");
    setCfRating("");
    setTeachingGoal("");
    setAlgorithm("");
    setTagsText("");
    setScenario("");
    setInputOutputSpec("");
    setDataConstraints("");
    setQualityRequirements("");
    setStandardSolutionLanguage("cpp");
    setStatementRequirement("");
    setTestcaseRequirement("");
    setSolutionRequirement("");
    setTargetHiddenCaseCount("");
    setEnableAutoRepair(false);
    setEnableReferenceCheck(false);
    setReferenceCheckTouched(false);
  }

  async function rejectDraft() {
    if (!rejectTarget) return;
    const rejectedDraftId = rejectTarget.id;
    setRejecting(true);
    try {
      await api.rejectDraft(rejectedDraftId, rejectReason.trim() || undefined);
      toast.success(t("drafts.rejectedMessage"));
      setRejectTarget(null);
      setRejectReason("");
      if (selectedDraft?.id === rejectedDraftId) {
        setSelectedDraft(null);
        setDetailOpen(false);
      }
      await refreshDrafts();
    } catch (caught) {
      toast.error(readableCaughtError(caught, locale, t("drafts.rejectFailed")));
    } finally {
      setRejecting(false);
    }
  }

  async function applyLifecycleAction(action: Exclude<ConfirmAction["action"], "import">, draft: ProblemDraftResponse) {
    try {
      const nextDraft = action === "archive"
        ? await api.archiveDraft(draft.id)
        : action === "restore"
          ? await api.restoreDraft(draft.id)
          : action === "manualReview"
            ? await api.manualReviewDraft(draft.id)
            : null;
      if (action === "delete") {
        await api.deleteDraft(draft.id);
      }
      if (action === "delete" && selectedDraft?.id === draft.id) {
        setSelectedDraft(null);
        setDetailOpen(false);
      } else if (nextDraft && selectedDraft?.id === draft.id) {
        setSelectedDraft(nextDraft);
      }
      if (action !== "delete") {
        toast.success(t(
          action === "archive"
            ? "drafts.archivedMessage"
            : action === "restore"
              ? "drafts.restoredMessage"
              : "drafts.manualReviewPassedMessage"
        ));
      } else {
        toast.success(t("drafts.deletedMessage"));
      }
      await refreshDrafts();
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.userMessage : t("common.errorFallback"));
    }
  }

  async function handleConfirmAction() {
    if (!confirmAction) return;
    await applyLifecycleAction(confirmAction.action, confirmAction.draft);
    setConfirmAction(null);
  }

  function openImportPanel(draft: ProblemDraftResponse) {
    setImportVisibility("PUBLIC");
    setImportTarget(draft);
  }

  async function confirmImport() {
    if (!importTarget) return;
    if (!isImportReady(importTarget)) {
      toast.error(t("drafts.importRequiresVerification"));
      return;
    }
    await importMutation.mutateAsync({ draft: importTarget, visibility: importVisibility });
  }

  async function handleUpdated(draft: ProblemDraftResponse) {
    setSelectedDraft(draft);
    await refreshDrafts();
  }

  async function handleRegenerationJobCreated(job: ProblemDraftGenerationJobResponse) {
    setCreatedJob(job);
    setSelectedDraft(null);
    setDetailOpen(false);
    queryClient.setQueryData<PageResponse<ProblemDraftGenerationJobResponse>>(
      ["admin-ai-draft-generation-jobs", 1, ""],
      (current) => upsertGenerationJobPage(current, job)
    );
    selectMainTab("jobs");
    await queryClient.invalidateQueries({ queryKey: ["admin-ai-draft-generation-jobs"] });
  }

  return (
    <div className="mx-auto flex max-w-[1540px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("nav.aiDrafts")}
        description={t("drafts.generateCopy")}
        actions={<QuotaPill quota={usageQuery.data} error={usageQuery.isError} />}
      />

      <div className="flex flex-wrap gap-2 border-b border-[var(--oj-border-soft)] pb-3">
        <StatusTab active={mainTab === "generate"} onClick={() => selectMainTab("generate")}>
          {t("drafts.generateTab")}
        </StatusTab>
        <StatusTab active={mainTab === "jobs"} onClick={() => selectMainTab("jobs")}>
          {t("drafts.jobsTab")}
        </StatusTab>
        <StatusTab active={mainTab === "box"} onClick={() => selectMainTab("box")}>
          {t("drafts.boxTab")}
        </StatusTab>
      </div>

      {mainTab === "generate" ? (
        <section className="space-y-5">
          <header className="flex items-start gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-[var(--oj-primary)] text-white">
              <Sparkles className="size-5" aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-base font-semibold text-[var(--oj-ink)]">{t("drafts.generate")}</h2>
              <p className="mt-1 max-w-4xl text-sm leading-6 text-[var(--oj-ink-muted)]">{t("drafts.generateTip")}</p>
            </div>
          </header>

          {generateError ? <ErrorPanel title={generateError} /> : null}

          <section className="rounded-xl border border-[var(--oj-border)] bg-white p-5">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.basicInfo")}</h3>
            <div className="mt-4 grid gap-4 lg:grid-cols-[minmax(0,1fr)_180px_220px]">
              <Field label={t("drafts.topic")} error={fieldErrors.topic} hint={t("problems.charCount", { count: topic.length, max: 100 })}>
                <input className={inputClass} value={topic} maxLength={100} onChange={(event) => setTopic(event.target.value)} placeholder={t("drafts.topicPlaceholder")} />
              </Field>
              <Field label={t("drafts.cfRating")} error={fieldErrors.cfRating}>
                <input className={inputClass} value={cfRating} inputMode="numeric" onChange={(event) => setCfRating(event.target.value)} placeholder={t("drafts.cfRatingPlaceholder")} />
              </Field>
              <Field label={t("drafts.standardSolutionLanguage")} error={fieldErrors.standardSolutionLanguage}>
                <select className={selectClass} value={standardSolutionLanguage} onChange={(event) => setStandardSolutionLanguage(event.target.value)}>
                  <option value="cpp">C++</option>
                  <option value="python">Python</option>
                  <option value="java">Java</option>
                </select>
              </Field>
            </div>
            <div className="mt-4 grid gap-4 xl:grid-cols-3">
              <Field label={t("drafts.algorithm")} error={fieldErrors.algorithm}>
                <input className={inputClass} value={algorithm} maxLength={200} onChange={(event) => setAlgorithm(event.target.value)} placeholder={t("drafts.algorithmPlaceholder")} />
              </Field>
              <Field label={t("drafts.tags")} error={fieldErrors.tags}>
                <input className={inputClass} value={tagsText} maxLength={300} onChange={(event) => setTagsText(event.target.value)} placeholder={t("drafts.tagsPlaceholder")} />
              </Field>
              <Field label={t("drafts.scenario")} error={fieldErrors.scenario}>
                <input className={inputClass} value={scenario} maxLength={500} onChange={(event) => setScenario(event.target.value)} placeholder={t("drafts.scenarioPlaceholder")} />
              </Field>
            </div>
            <div className="mt-4">
              <Field label={t("drafts.teachingGoal")} error={fieldErrors.teachingGoal} hint={t("problems.charCount", { count: teachingGoal.length, max: 500 })}>
                <textarea className={`${textareaClass} min-h-28`} value={teachingGoal} maxLength={500} onChange={(event) => setTeachingGoal(event.target.value)} placeholder={t("drafts.teachingGoalPlaceholder")} />
              </Field>
            </div>
            <div className="mt-4 grid gap-4 xl:grid-cols-2">
              <Field label={t("drafts.qualityRequirements")} error={fieldErrors.qualityRequirements}>
                <textarea className={`${textareaClass} min-h-28`} value={qualityRequirements} maxLength={1000} onChange={(event) => setQualityRequirements(event.target.value)} placeholder={t("drafts.qualityRequirementsPlaceholder")} />
              </Field>
              <Field label={t("drafts.statementRequirement")}>
                <textarea className={`${textareaClass} min-h-28`} value={statementRequirement} maxLength={1000} onChange={(event) => setStatementRequirement(event.target.value)} placeholder={t("drafts.statementRequirementPlaceholder")} />
              </Field>
            </div>
          </section>

          <section className="rounded-xl border border-[var(--oj-border)] bg-white p-5">
            <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.advancedOptions")}</h3>
            <details className="mt-4 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
              <summary className="cursor-pointer text-sm font-medium text-[var(--oj-ink)]">{t("drafts.highReliabilityRequirements")}</summary>
              <div className="mt-4 grid gap-4 xl:grid-cols-2">
                <Field label={t("drafts.inputOutputSpec")} error={fieldErrors.inputOutputSpec}>
                  <textarea className={`${textareaClass} min-h-24`} value={inputOutputSpec} maxLength={1000} onChange={(event) => setInputOutputSpec(event.target.value)} placeholder={t("drafts.inputOutputSpecPlaceholder")} />
                </Field>
                <Field label={t("drafts.dataConstraints")} error={fieldErrors.dataConstraints}>
                  <textarea className={`${textareaClass} min-h-24`} value={dataConstraints} maxLength={1000} onChange={(event) => setDataConstraints(event.target.value)} placeholder={t("drafts.dataConstraintsPlaceholder")} />
                </Field>
                <Field label={t("drafts.testcaseRequirement")} error={fieldErrors.testcaseRequirement}>
                  <textarea className={`${textareaClass} min-h-24`} value={testcaseRequirement} maxLength={1000} onChange={(event) => setTestcaseRequirement(event.target.value)} placeholder={t("drafts.testcaseRequirementPlaceholder")} />
                </Field>
                <Field label={t("drafts.solutionRequirement")} error={fieldErrors.solutionRequirement}>
                  <textarea className={`${textareaClass} min-h-24`} value={solutionRequirement} maxLength={1000} onChange={(event) => setSolutionRequirement(event.target.value)} placeholder={t("drafts.solutionRequirementPlaceholder")} />
                </Field>
              </div>
            </details>
            <div className="mt-4 grid gap-4 lg:grid-cols-[minmax(0,240px)_minmax(0,1fr)_minmax(0,1fr)]">
              <Field label={t("drafts.targetHiddenCaseCount")} error={fieldErrors.targetHiddenCaseCount}>
                <input className={inputClass} value={targetHiddenCaseCount} inputMode="numeric" onChange={(event) => setTargetHiddenCaseCount(event.target.value)} placeholder={t("drafts.targetHiddenCaseCountPlaceholder")} />
              </Field>
              <label className="flex min-h-10 items-center gap-3 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)]">
                <input type="checkbox" checked={enableAutoRepair} onChange={(event) => setEnableAutoRepair(event.target.checked)} />
                <span className="font-medium">{t("drafts.enableAutoRepair")}</span>
              </label>
              <label className="flex min-h-10 items-center gap-3 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)]">
                <input
                  type="checkbox"
                  checked={enableReferenceCheck}
                  onChange={(event) => {
                    setReferenceCheckTouched(true);
                    setEnableReferenceCheck(event.target.checked);
                  }}
                />
                <span>
                  <span className="block font-medium">{t("drafts.enableReferenceCheck")}</span>
                  <span className="block text-xs leading-5 text-[var(--oj-ink-muted)]">
                    {cfRating.trim() && Number(cfRating.trim()) >= 1600 && !enableReferenceCheck
                      ? t("drafts.referenceCheckDisabledHighRating")
                      : t("drafts.referenceCheckAutoHint")}
                  </span>
                </span>
              </label>
            </div>
          </section>

          {createdJob ? (
            <ErrorPanel
              tone="success"
              title={createdJobTitle(createdJob, t)}
              description={createdJobDescription(createdJob, t)}
              action={(
                <Button size="sm" onClick={() => selectMainTab("jobs")}>
                  {t("drafts.viewGenerationJobs")}
                </Button>
              )}
            />
          ) : null}

          <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 sm:flex-row sm:items-center sm:justify-between">
            {generating ? (
              <div className="min-w-0">
                <strong className="block text-sm text-[var(--oj-ink)]">{t("drafts.jobCreating")}</strong>
                <span className="mt-1 block text-sm leading-6 text-[var(--oj-ink-muted)]">{t("drafts.jobCreatingHint")}</span>
              </div>
            ) : (
              <>
                <p className="text-sm text-[var(--oj-ink-muted)]">{t("drafts.generateSubmitHint")}</p>
                <Button className="w-full sm:w-auto" onClick={() => void generateDraft()}>{t("drafts.generate")}</Button>
              </>
            )}
            {generating ? <Button className="w-full sm:w-auto" disabled>{t("common.loading")}</Button> : null}
          </div>
        </section>
      ) : null}

      {mainTab === "jobs" ? (
        <section className="space-y-4">
          {createdJob ? (
            <ErrorPanel
              tone="success"
              title={createdJobTitle(createdJob, t)}
              description={createdJobDescription(createdJob, t)}
            />
          ) : null}
          <AiDraftJobsPanel onOpenDraft={(draftId) => void openDraftById(draftId)} />
        </section>
      ) : null}

      {mainTab === "box" ? (
        <section className="space-y-4">
          <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white p-4 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex flex-wrap gap-2">
              <StatusTab active={activeStatus === "PENDING_REVIEW"} onClick={() => setActiveStatus("PENDING_REVIEW")}>
                {t("drafts.pendingWithCount", { count: pendingCount })}
              </StatusTab>
              <StatusTab active={activeStatus === "APPROVED"} onClick={() => setActiveStatus("APPROVED")}>
                {t("drafts.approvedWithCount", { count: approvedCount })}
              </StatusTab>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <select className={`${selectClass} w-36`} value={validationFilter} onChange={(event) => setValidationFilter(event.target.value as ValidationFilter)}>
                <option value="">{t("drafts.filterAll")}</option>
                <option value="VALID">{t("drafts.filterValid")}</option>
                <option value="INVALID">{t("drafts.filterInvalid")}</option>
              </select>
              <select className={`${selectClass} w-36`} value={lifecycleFilter} onChange={(event) => setLifecycleFilter(event.target.value as LifecycleFilter)}>
                <option value="ALL">{t("common.all")}</option>
                <option value="ACTIVE">{t("common.active")}</option>
                <option value="ARCHIVED">{t("common.archived")}</option>
              </select>
              <select className={`${selectClass} w-40`} value={sortOrder} onChange={(event) => setSortOrder(event.target.value as SortOrder)}>
                <option value="newest">{t("drafts.sortNewest")}</option>
                <option value="oldest">{t("drafts.sortOldest")}</option>
              </select>
              <label className="flex h-10 items-center gap-2 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)]">
                <input type="checkbox" checked={mineOnly} onChange={(event) => setMineOnly(event.target.checked)} />
                {t("drafts.filterMine")}
              </label>
              <Button variant="outline" disabled={draftsQuery.isFetching} onClick={() => void draftsQuery.refetch()}>
                <RotateCw className="size-4" aria-hidden="true" />
                {t("common.refresh")}
              </Button>
            </div>
          </div>

          {listError ? <ErrorPanel title={listError} /> : null}

          {draftsQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : draftsQuery.isError ? (
            <ErrorPanel title={t("drafts.loadFailed")} action={<Button variant="outline" onClick={() => void draftsQuery.refetch()}>{t("common.refresh")}</Button>} />
          ) : records.length ? (
            <div className="space-y-3">
              <TableShell>
                <table className="w-full min-w-[940px] text-sm">
                <colgroup>
                  <col className="w-[35%]" />
                  <col className="w-[12%]" />
                  <col className="w-[25%]" />
                  <col className="w-[14%]" />
                  <col className="w-[14%]" />
                </colgroup>
                <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
                  <tr>
                    <th className="px-4 py-3 text-left">{t("common.title")}</th>
                    <th className="px-4 py-3 text-left">{t("common.difficulty")}</th>
                    <th className="px-4 py-3 text-left">{t("common.tags")}</th>
                    <th className="px-4 py-3 text-left">{t("drafts.validation")}</th>
                    <th className="px-4 py-3 text-left">{t("drafts.import")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[var(--oj-border-soft)]">
                  {records.map((draft) => (
                    <tr key={draft.id} className="cursor-pointer align-middle hover:bg-[var(--oj-surface-muted)]" onClick={() => void openDetail(draft)}>
                      <td className="px-4 py-4">
                        <strong className="block truncate text-[var(--oj-ink)]">{draft.title}</strong>
                        <span className="mt-1 block text-xs tabular-nums text-[var(--oj-ink-muted)]">#{shortId(draft.id)} · {formatDateTime(draft.createdAt)}</span>
                      </td>
                      <td className="px-4 py-4">
                        <Badge tone={difficultyTone(draft.difficulty)}>{t(`difficulty.${draft.difficulty}`, undefined, String(draft.difficulty))}</Badge>
                      </td>
                      <td className="px-4 py-4">
                        <div className="flex flex-wrap gap-1.5">
                          {draft.tags.length ? draft.tags.slice(0, 5).map((item) => <Badge key={item} tone="neutral">{item}</Badge>) : <span className="text-[var(--oj-ink-muted)]">{t("problems.noTags")}</span>}
                        </div>
                      </td>
                      <td className="px-4 py-4">
                        <div className="flex flex-wrap gap-1.5">
                          <Badge tone={draft.validationStatus === "VALID" ? "green" : "amber"}>{validationLabel(draft.validationStatus, t)}</Badge>
                          <Badge tone={verificationTone(draft.verificationStatus)}>{verificationLabel(draft.verificationStatus, t)}</Badge>
                        </div>
                        {draft.archivedAt ? <Badge tone="neutral">{t("common.archived")}</Badge> : null}
                        {draft.validationErrors?.length ? <span className="mt-1 block truncate text-xs text-amber-700">{draft.validationErrors[0]}</span> : null}
                      </td>
                      <td className="px-4 py-4">
                        {draft.importedProblemId ? <Badge tone="green">#{shortId(draft.importedProblemId)}</Badge> : <Badge tone="neutral">{t("drafts.notImported")}</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
                </table>
              </TableShell>
              <PaginationRow
                page={page}
                total={total}
                pageSize={PAGE_SIZE}
                onPageChange={setPage}
                previousLabel={t("common.previous")}
                nextLabel={t("common.next")}
              />
            </div>
          ) : (
            <EmptyState title={t("drafts.emptyTitle")} description={t("drafts.emptyDescription")} actionLabel={t("drafts.generateNow")} onAction={() => selectMainTab("generate")} />
          )}
        </section>
      ) : null}

      <AiDraftDetailPanel
        open={detailOpen}
        onOpenChange={handleDetailOpenChange}
        draft={selectedDraft}
        onUpdated={handleUpdated}
        onRegenerationJobCreated={(job) => void handleRegenerationJobCreated(job)}
        approved={activeStatus === "APPROVED" || selectedDraft?.status === "APPROVED"}
        pendingAction={detailPendingAction}
        onApprove={(draft) => {
          if (draft.status === "APPROVED" || activeStatus === "APPROVED") return;
          void approveMutation.mutateAsync(draft);
        }}
        onImport={(draft) => {
          if (draft.status !== "APPROVED") {
            toast.error(t("drafts.importRequiresApproval"));
            return;
          }
          if (!isImportReady(draft)) {
            toast.error(t("drafts.importRequiresVerification"));
            return;
          }
          openImportPanel(draft);
        }}
        onManualReview={(draft) => {
          if (draft.importedProblemId) {
            toast.error(t("errors.draft.alreadyImported"));
            return;
          }
          setConfirmAction({ action: "manualReview", draft });
        }}
        onArchive={(draft) => setConfirmAction({ action: "archive", draft })}
        onRestore={(draft) => setConfirmAction({ action: "restore", draft })}
        onReject={(draft) => {
          setRejectTarget(draft);
          setRejectReason("");
        }}
        onDelete={(draft) => setConfirmAction({ action: "delete", draft })}
      />

      <SidePanel
        open={Boolean(rejectTarget)}
        onOpenChange={(open) => {
          if (!open) setRejectTarget(null);
        }}
        title={t("drafts.reject")}
        description={rejectTarget ? `${rejectTarget.title} · ${t("drafts.rejectIrreversibleWarning")}` : undefined}
        footer={(
          <div className="flex justify-end gap-2">
            <Button variant="outline" disabled={rejecting} onClick={() => setRejectTarget(null)}>{t("common.cancel")}</Button>
            <Button disabled={rejecting} className="bg-amber-700 hover:bg-amber-800" onClick={() => void rejectDraft()}>{rejecting ? t("common.loading") : t("drafts.reject")}</Button>
          </div>
        )}
      >
        <Field label={t("drafts.rejectReasonLabel")}>
          <textarea className={`${textareaClass} min-h-44`} value={rejectReason} onChange={(event) => setRejectReason(event.target.value)} placeholder={t("drafts.rejectReasonPlaceholder")} />
        </Field>
      </SidePanel>

      <SidePanel
        open={Boolean(importTarget)}
        onOpenChange={(open) => !open && setImportTarget(null)}
        title={t("drafts.importConfirm")}
        description={importTarget?.title ?? ""}
        footer={(
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setImportTarget(null)}>{t("common.cancel")}</Button>
            <Button disabled={importMutation.isPending} onClick={() => void confirmImport()}>{importMutation.isPending ? t("common.loading") : t("drafts.import")}</Button>
          </div>
        )}
      >
        <Field label={t("problems.visibilityLabel")} hint={t("drafts.importVisibilityHelper")}>
          <select className={selectClass} value={importVisibility} onChange={(event) => setImportVisibility(event.target.value as ProblemVisibility)}>
            <option value="PUBLIC">{t("problems.visibilityPublic")}</option>
            <option value="PRIVATE">{t("problems.visibilityPrivate")}</option>
          </select>
        </Field>
      </SidePanel>

      <ConfirmDialog
        open={Boolean(confirmAction)}
        onOpenChange={(open) => !open && setConfirmAction(null)}
        title={draftConfirmTitle(confirmAction, t)}
        description={draftConfirmDescription(confirmAction, t)}
        cancelLabel={t("common.cancel")}
        confirmLabel={draftConfirmLabel(confirmAction, t)}
        onConfirm={handleConfirmAction}
      />
    </div>
  );
}

function draftConfirmTitle(action: ConfirmAction | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!action) return "";
  if (action.action === "archive") return t("drafts.archiveConfirm");
  if (action.action === "restore") return t("drafts.restoreConfirm");
  if (action.action === "manualReview") return t("drafts.manualReviewConfirm");
  return t("drafts.deleteConfirm");
}

function draftConfirmDescription(action: ConfirmAction | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!action) return "";
  if (action.action === "delete") {
    return `${action.draft.title}\n${t("drafts.deleteConfirmDescription")}`;
  }
  if (action.action === "manualReview") {
    return `${action.draft.title}\n${t("drafts.manualReviewConfirmDescription")}`;
  }
  return action.draft.title;
}

function draftConfirmLabel(action: ConfirmAction | null, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!action) return "";
  if (action.action === "archive") return t("common.archive");
  if (action.action === "restore") return t("common.restore");
  if (action.action === "manualReview") return t("drafts.manualReview");
  return t("common.delete");
}

function upsertGenerationJobPage(
  page: PageResponse<ProblemDraftGenerationJobResponse> | undefined,
  job: ProblemDraftGenerationJobResponse
): PageResponse<ProblemDraftGenerationJobResponse> {
  const records = page?.records ?? [];
  const existed = records.some((item) => item.id === job.id);
  return {
    records: [job, ...records.filter((item) => item.id !== job.id)]
      .sort((left, right) => jobUpdatedTime(right) - jobUpdatedTime(left))
      .slice(0, page?.pageSize ?? JOBS_PAGE_SIZE),
    total: page ? page.total + (existed ? 0 : 1) : 1,
    page: page?.page ?? 1,
    pageSize: page?.pageSize ?? JOBS_PAGE_SIZE
  };
}

function jobUpdatedTime(job: ProblemDraftGenerationJobResponse) {
  const updatedAt = Date.parse(job.updatedAt || "");
  if (Number.isFinite(updatedAt)) return updatedAt;
  const createdAt = Date.parse(job.createdAt || "");
  return Number.isFinite(createdAt) ? createdAt : 0;
}

function isRegenerationJob(job: ProblemDraftGenerationJobResponse) {
  return job.jobType === "REGENERATE" || Boolean(job.sourceDraftId);
}

function createdJobTitle(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  return isRegenerationJob(job) ? t("drafts.rewriteJobCreated") : t("drafts.jobCreated");
}

function createdJobDescription(job: ProblemDraftGenerationJobResponse, t: TFunction) {
  if (isRegenerationJob(job) && job.sourceDraftId) {
    return t("drafts.rewriteJobCreatedDescription", { id: shortId(job.id), sourceId: shortId(job.sourceDraftId) });
  }
  return t("drafts.jobCreatedDescription", { id: shortId(job.id) });
}

function QuotaPill({ quota, error }: { quota?: AiUsageResponse; error: boolean }) {
  const { t } = useI18n();
  if (error) return <Badge tone="neutral">{t("drafts.quotaUnavailable")}</Badge>;
  if (!quota) return <Badge tone="neutral">{t("common.loading")}</Badge>;
  const normalizedQuota = normalizeAiUsage(quota);
  return (
    <div className="flex flex-wrap gap-2">
      <Badge tone="blue">{t("drafts.quotaRecent", { hours: normalizedQuota.recentWindowHours, used: normalizedQuota.usedRecent, total: normalizedQuota.rollingLimit })}</Badge>
      <Badge tone="neutral">{t("drafts.quotaMonth", { used: normalizedQuota.usedThisMonth, total: normalizedQuota.monthlyLimit })}</Badge>
    </div>
  );
}

function normalizeAiUsage(usage: AiUsageResponse & Partial<{ usedToday: number; dailyLimit: number }>) {
  return {
    usedRecent: usage.usedRecent ?? usage.usedToday ?? 0,
    rollingLimit: usage.rollingLimit ?? usage.dailyLimit ?? 50,
    recentWindowHours: usage.recentWindowHours ?? 2,
    usedThisMonth: usage.usedThisMonth,
    monthlyLimit: usage.monthlyLimit
  };
}

function StatusTab({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      className={cn(
        "h-10 rounded-lg px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
        active ? "bg-[var(--oj-primary)] text-white" : "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)]"
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function isImportReady(draft: ProblemDraftResponse) {
  return draft.validationStatus === "VALID"
    && (draft.verificationStatus === "EXECUTION_VERIFIED" || draft.verificationStatus === "MANUAL_VERIFIED");
}

function validationLabel(status: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`validationStatus.${status}`, undefined, status);
}

function verificationLabel(status: string | null | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  const normalized = status || "NOT_RUN";
  return t(`verificationStatus.${normalized}`, undefined, normalized);
}

function verificationTone(status: string | null | undefined): "blue" | "green" | "amber" | "red" | "neutral" {
  if (status === "EXECUTION_VERIFIED" || status === "MANUAL_VERIFIED" || status === "PASSED") return "green";
  if (status === "FAILED") return "red";
  if (status === "STATIC_FAILED") return "amber";
  return "neutral";
}

function difficultyFromCfRating(cfRating: number) {
  if (cfRating <= 1200) return "EASY";
  if (cfRating <= 1700) return "MEDIUM";
  if (cfRating <= 2300) return "HARD";
  return "CHALLENGE";
}
