import * as React from "react";
import { api, type Difficulty, type ProblemDraftGenerationJobResponse, type ProblemDraftResponse, type TestCaseDto } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ErrorPanel, Field, SidePanel, inputClass, selectClass, textareaClass } from "./Common";
import { MarkdownView } from "./MarkdownView";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { difficultyTone, formatBytes, formatDateTime, shortId } from "../lib/format";
import { readableCaughtError } from "../lib/readableError";

const DIFFICULTIES: Difficulty[] = ["EASY", "MEDIUM", "HARD", "CHALLENGE"];

type DraftTab = "preview" | "verification" | "edit" | "regenerate";
export type AiDraftAction = "approve" | "import" | "archive" | "restore" | "reject" | "delete" | "manualReview";

export function AiDraftDetailPanel({
  open,
  onOpenChange,
  draft,
  onUpdated,
  onRegenerationJobCreated,
  approved = false,
  pendingAction,
  onApprove,
  onImport,
  onManualReview,
  onReject,
  onArchive,
  onRestore,
  onDelete
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  draft: ProblemDraftResponse | null;
  onUpdated: (draft: ProblemDraftResponse) => Promise<void> | void;
  onRegenerationJobCreated: (job: ProblemDraftGenerationJobResponse) => Promise<void> | void;
  approved?: boolean;
  pendingAction?: AiDraftAction | null;
  onApprove: (draft: ProblemDraftResponse) => void;
  onImport: (draft: ProblemDraftResponse) => void;
  onManualReview: (draft: ProblemDraftResponse) => void;
  onReject: (draft: ProblemDraftResponse) => void;
  onArchive: (draft: ProblemDraftResponse) => void;
  onRestore: (draft: ProblemDraftResponse) => void;
  onDelete: (draft: ProblemDraftResponse) => void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [tab, setTab] = React.useState<DraftTab>("preview");
  const [localDraft, setLocalDraft] = React.useState<ProblemDraftResponse | null>(null);
  const [title, setTitle] = React.useState("");
  const [difficulty, setDifficulty] = React.useState<Difficulty | string>("EASY");
  const [tagText, setTagText] = React.useState("");
  const [statement, setStatement] = React.useState("");
  const [notes, setNotes] = React.useState("");
  const [standardSolutionLanguage, setStandardSolutionLanguage] = React.useState("cpp");
  const [standardSolutionCode, setStandardSolutionCode] = React.useState("");
  const [referenceSolutionLanguage, setReferenceSolutionLanguage] = React.useState("cpp");
  const [referenceSolutionCode, setReferenceSolutionCode] = React.useState("");
  const [testcaseGeneratorPython, setTestcaseGeneratorPython] = React.useState("");
  const [stressTestcaseGeneratorPython, setStressTestcaseGeneratorPython] = React.useState("");
  const [generationPlan, setGenerationPlan] = React.useState("");
  const [testCases, setTestCases] = React.useState<TestCaseDto[]>([emptyCase(true)]);
  const [timeLimitMillis, setTimeLimitMillis] = React.useState(1000);
  const [memoryLimitKb, setMemoryLimitKb] = React.useState(262144);
  const [refineNote, setRefineNote] = React.useState("");
  const [feedback, setFeedback] = React.useState("");
  const [refining, setRefining] = React.useState(false);
  const [regenerating, setRegenerating] = React.useState(false);

  const currentDraft = localDraft ?? draft;
  const imported = Boolean(currentDraft?.importedProblemId);
  const archived = Boolean(currentDraft?.archivedAt);
  const approvedDraft = approved || currentDraft?.status === "APPROVED";
  const importReady = Boolean(currentDraft && canImportDraft(currentDraft));
  const actionPending = Boolean(pendingAction);
  const showReferenceSolver = Boolean(currentDraft && shouldShowReferenceSolver(currentDraft));

  const formSnapshot = React.useMemo(() => JSON.stringify({
    title,
    difficulty,
    tagText,
    statement,
    notes,
    standardSolutionLanguage,
    standardSolutionCode,
    referenceSolutionLanguage,
    referenceSolutionCode,
    testcaseGeneratorPython,
    stressTestcaseGeneratorPython,
    generationPlan,
    timeLimitMillis,
    memoryLimitKb,
    testCases: testCases.map((item) => ({ input: item.input, expectedOutput: item.expectedOutput, sample: item.sample })),
    refineNote
  }), [title, difficulty, tagText, statement, notes, standardSolutionLanguage, standardSolutionCode, referenceSolutionLanguage, referenceSolutionCode, testcaseGeneratorPython, stressTestcaseGeneratorPython, generationPlan, timeLimitMillis, memoryLimitKb, testCases, refineNote]);

  const baselineSnapshot = React.useMemo(() => (open ? JSON.stringify(draftFormBaseline(draft)) : null), [open, draft]);
  const editDirty = baselineSnapshot !== null && formSnapshot !== baselineSnapshot;

  React.useEffect(() => {
    if (!open || !draft) return;
    setLocalDraft(null);
    setTab("preview");
    resetForm(draft);
  }, [open, draft]);

  function resetForm(nextDraft: ProblemDraftResponse) {
    setTitle(nextDraft.title);
    setDifficulty(nextDraft.difficulty || "EASY");
    setTagText((nextDraft.tags ?? []).join(", "));
    setStatement(nextDraft.statement || "");
    setNotes(nextDraft.notes || "");
    setStandardSolutionLanguage(nextDraft.standardSolutionLanguage || "cpp");
    setStandardSolutionCode(nextDraft.standardSolutionCode || "");
    setReferenceSolutionLanguage(nextDraft.referenceSolutionLanguage || nextDraft.standardSolutionLanguage || "cpp");
    setReferenceSolutionCode(nextDraft.referenceSolutionCode || "");
    setTestcaseGeneratorPython(nextDraft.testcaseGeneratorPython || "");
    setStressTestcaseGeneratorPython(nextDraft.stressTestcaseGeneratorPython || "");
    setGenerationPlan(nextDraft.generationPlan || "");
    setTestCases(nextDraft.testCases?.length ? nextDraft.testCases.map((item) => ({ ...item })) : [emptyCase(true)]);
    setTimeLimitMillis(nextDraft.timeLimitMillis || 1000);
    setMemoryLimitKb(nextDraft.memoryLimitKb || 262144);
    setRefineNote(nextDraft.refineNote || "");
    setFeedback("");
  }

  function updateCase(index: number, patch: Partial<TestCaseDto>) {
    setTestCases((items) => items.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)));
  }

  function addCase() {
    setTestCases((items) => [...items, emptyCase(false)]);
  }

  function removeCase(index: number) {
    setTestCases((items) => {
      if (items.length <= 1) return items;
      const next = items.filter((_, itemIndex) => itemIndex !== index);
      if (!next.some((item) => item.sample)) next[0] = { ...next[0], sample: true };
      return next;
    });
  }

  async function refineDraft() {
    if (!currentDraft || !editDirty) return;
    setRefining(true);
    try {
      const nextDraft = await api.refineDraft(currentDraft.id, {
        title: title.trim(),
        difficulty,
        statement,
        notes,
        standardSolutionLanguage,
        standardSolutionCode,
        referenceSolutionLanguage: referenceSolutionCode.trim() ? referenceSolutionLanguage : undefined,
        referenceSolutionCode: referenceSolutionCode.trim() || undefined,
        testcaseGeneratorPython,
        stressTestcaseGeneratorPython,
        generationPlan,
        tags: parseTags(tagText),
        testCases,
        timeLimitMillis: Number(timeLimitMillis),
        memoryLimitKb: Number(memoryLimitKb),
        refineNote: refineNote.trim() || undefined
      });
      setLocalDraft(nextDraft);
      resetForm(nextDraft);
      setTab("preview");
      await onUpdated(nextDraft);
      toast.success(t("drafts.refinedMessage"));
    } catch (caught) {
      toast.error(readableCaughtError(caught, locale, t("drafts.refineFailed")));
    } finally {
      setRefining(false);
    }
  }

  async function regenerateDraft() {
    if (!currentDraft) return;
    setRegenerating(true);
    try {
      const job = await api.createProblemDraftRegenerationJob(currentDraft.id, feedback.trim());
      setFeedback("");
      await onRegenerationJobCreated(job);
    } catch (caught) {
      toast.error(readableCaughtError(caught, locale, t("drafts.regenerateFailed")));
    } finally {
      setRegenerating(false);
    }
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      wide
      presentation="workspace"
      title={t("drafts.detailTitle")}
      description={currentDraft ? `#${currentDraft.id}` : undefined}
      footer={currentDraft ? (
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--oj-ink-muted)]">
            {currentDraft.refinedFromDraftId ? <Badge tone="neutral">{t("drafts.chainParentLabel", { id: shortId(currentDraft.refinedFromDraftId) })}</Badge> : null}
            <span className="tabular-nums">{currentDraft.promptTokens}/{currentDraft.completionTokens} tokens</span>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:justify-end">
            {archived ? (
              <>
                <Button variant="outline" disabled={actionPending} onClick={() => onRestore(currentDraft)}>
                  {pendingAction === "restore" ? t("common.loading") : t("common.restore")}
                </Button>
                <Button variant="outline" className="text-red-700 hover:bg-red-50" disabled={actionPending} onClick={() => onDelete(currentDraft)}>
                  {pendingAction === "delete" ? t("common.loading") : t("common.delete")}
                </Button>
              </>
            ) : (
              <>
                <Button variant="outline" disabled={approvedDraft || imported || actionPending} onClick={() => onApprove(currentDraft)}>
                  {pendingAction === "approve" ? t("common.loading") : t("drafts.approve")}
                </Button>
                <Button disabled={!approvedDraft || imported || actionPending || !importReady} onClick={() => onImport(currentDraft)}>
                  {pendingAction === "import" ? t("common.loading") : t("drafts.import")}
                </Button>
                <Button variant="outline" className="text-amber-700 hover:bg-amber-50" disabled={imported || actionPending} onClick={() => onReject(currentDraft)}>
                  {pendingAction === "reject" ? t("common.loading") : t("drafts.reject")}
                </Button>
                <Button variant="outline" disabled={actionPending} onClick={() => onArchive(currentDraft)}>
                  {pendingAction === "archive" ? t("common.loading") : t("common.archive")}
                </Button>
              </>
            )}
          </div>
        </div>
      ) : null}
    >
      {!currentDraft ? null : (
        <div className="min-w-0 space-y-5">
          <header className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
            <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div className="min-w-0">
                <h2 className="truncate text-lg font-semibold text-[var(--oj-ink)]">{currentDraft.title}</h2>
                <p className="mt-1 text-sm tabular-nums text-[var(--oj-ink-muted)]">{formatDateTime(currentDraft.createdAt)}</p>
              </div>
              <div className="flex shrink-0 flex-wrap gap-2">
                <Badge tone={difficultyTone(currentDraft.difficulty)}>{t(`difficulty.${currentDraft.difficulty}`, undefined, String(currentDraft.difficulty))}</Badge>
                <Badge tone={currentDraft.validationStatus === "VALID" ? "green" : "amber"}>{validationLabel(currentDraft.validationStatus, t)}</Badge>
                <Badge tone={verificationTone(currentDraft.verificationStatus)}>{verificationLabel(currentDraft.verificationStatus, t)}</Badge>
                {currentDraft.archivedAt ? <Badge tone="neutral">{t("common.archived")}</Badge> : null}
                {currentDraft.importedProblemId ? <Badge tone="green">#{shortId(currentDraft.importedProblemId)}</Badge> : <Badge tone="neutral">{t("drafts.notImported")}</Badge>}
              </div>
            </div>
          </header>

          <div className="flex flex-wrap gap-2 border-b border-[var(--oj-border-soft)] pb-3">
            <TabButton active={tab === "preview"} onClick={() => setTab("preview")}>{t("drafts.detailTabsPreview")}</TabButton>
            <TabButton active={tab === "verification"} onClick={() => setTab("verification")}>{t("drafts.detailTabsVerification")}</TabButton>
            <TabButton active={tab === "edit"} onClick={() => setTab("edit")}>{t("drafts.detailTabsEdit")}</TabButton>
            <TabButton active={tab === "regenerate"} onClick={() => setTab("regenerate")}>{t("drafts.detailTabsRegenerate")}</TabButton>
          </div>

          {tab === "preview" ? (
            <div className="space-y-5">
              {currentDraft.validationErrors?.length ? (
                <ErrorPanel title={t("drafts.validation")} description={currentDraft.validationErrors.join("; ")} />
              ) : null}
              <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <h3 className="mb-3 text-sm font-semibold text-[var(--oj-ink)]">{t("problems.statement")}</h3>
                <MarkdownView content={currentDraft.statement || ""} />
              </section>
              <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <h3 className="mb-3 text-sm font-semibold text-[var(--oj-ink)]">{t("problems.notesLabel")}</h3>
                {currentDraft.notes ? <MarkdownView content={currentDraft.notes} /> : <p className="text-sm text-[var(--oj-ink-muted)]">{t("problems.notesEmpty")}</p>}
              </section>
              <section className="grid gap-4 xl:grid-cols-2">
                <CodePreview
                  title={`${t("drafts.standardSolution")} · ${(currentDraft.standardSolutionLanguage || "cpp").toUpperCase()}`}
                  content={currentDraft.standardSolutionCode || ""}
                  language={currentDraft.standardSolutionLanguage || "cpp"}
                  emptyLabel={t("drafts.standardSolutionEmpty")}
                />
                {showReferenceSolver ? (
                  <CodePreview
                    title={`${t("drafts.referenceSolution")} · ${(currentDraft.referenceSolutionLanguage || currentDraft.standardSolutionLanguage || "cpp").toUpperCase()}`}
                    content={currentDraft.referenceSolutionCode || ""}
                    language={currentDraft.referenceSolutionLanguage || currentDraft.standardSolutionLanguage || "cpp"}
                    emptyLabel={t("drafts.referenceSolutionEmpty")}
                  />
                ) : null}
                {shouldShowStressGenerator(currentDraft) ? (
                  <CodePreview
                    title={t("drafts.stressTestcaseGeneratorPython")}
                    content={currentDraft.stressTestcaseGeneratorPython || ""}
                    language="python"
                    emptyLabel={t("drafts.stressTestcaseGeneratorEmpty")}
                  />
                ) : null}
                <CodePreview
                  title={t("drafts.testcaseGeneratorPython")}
                  content={currentDraft.testcaseGeneratorPython || ""}
                  language="python"
                  emptyLabel={t("drafts.testcaseGeneratorEmpty")}
                />
              </section>
              <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <h3 className="mb-3 text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.generationPlan")}</h3>
                {currentDraft.generationPlan ? <p className="whitespace-pre-wrap text-sm leading-6 text-[var(--oj-ink)]">{currentDraft.generationPlan}</p> : <p className="text-sm text-[var(--oj-ink-muted)]">{t("drafts.generationPlanEmpty")}</p>}
              </section>
              <CasesPreview cases={currentDraft.testCases ?? []} />
            </div>
          ) : null}

          {tab === "verification" ? (
            <VerificationReportPanel draft={currentDraft} />
          ) : null}

          {tab === "edit" ? (
            <div className="space-y-5">
              <div className="grid gap-4 md:grid-cols-2">
                <Field label={t("common.title")}>
                  <input className={inputClass} value={title} onChange={(event) => setTitle(event.target.value)} />
                </Field>
                <Field label={t("common.difficulty")}>
                  <select className={selectClass} value={difficulty} onChange={(event) => setDifficulty(event.target.value)}>
                    {DIFFICULTIES.map((item) => <option key={item} value={item}>{t(`difficulty.${item}`)}</option>)}
                  </select>
                </Field>
                <Field label={t("common.tags")}>
                  <input className={inputClass} value={tagText} onChange={(event) => setTagText(event.target.value)} placeholder={t("problems.tagsPlaceholder")} />
                </Field>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label={`${t("problems.timeLimit")} (ms)`}>
                    <input className={inputClass} type="number" min={100} step={100} value={timeLimitMillis} onChange={(event) => setTimeLimitMillis(Number(event.target.value))} />
                  </Field>
                  <Field label={`${t("problems.memoryLimit")} (KB)`}>
                    <input className={inputClass} type="number" min={16384} step={1024} value={memoryLimitKb} onChange={(event) => setMemoryLimitKb(Number(event.target.value))} />
                  </Field>
                </div>
              </div>
              <section className="grid gap-4 xl:grid-cols-2">
                <Field label={t("problems.statement")}>
                  <textarea className={`${textareaClass} min-h-[340px] font-mono`} value={statement} onChange={(event) => setStatement(event.target.value)} />
                </Field>
                <div className="min-h-[340px] rounded-xl border border-[var(--oj-border)] bg-white p-4">
                  <MarkdownView content={statement || ""} />
                </div>
              </section>
              <section className="grid gap-4 xl:grid-cols-2">
                <Field label={t("problems.notesLabel")} hint={t("problems.notesHelper")}>
                  <textarea className={`${textareaClass} min-h-56 font-mono`} value={notes} onChange={(event) => setNotes(event.target.value)} placeholder={t("problems.notesEditorPlaceholder")} />
                </Field>
                <div className="min-h-56 rounded-xl border border-[var(--oj-border)] bg-white p-4">
                  {notes ? <MarkdownView content={notes} /> : <p className="text-sm text-[var(--oj-ink-muted)]">{t("problems.notesEmpty")}</p>}
                </div>
              </section>
              <section className="grid gap-4 xl:grid-cols-[220px_minmax(0,1fr)]">
                <Field label={t("drafts.standardSolutionLanguage")}>
                  <select className={selectClass} value={standardSolutionLanguage} onChange={(event) => setStandardSolutionLanguage(event.target.value)}>
                    <option value="cpp">C++</option>
                    <option value="python">Python</option>
                    <option value="java">Java</option>
                  </select>
                </Field>
                <Field label={t("drafts.standardSolution")}>
                  <textarea className={`${textareaClass} min-h-64 font-mono`} value={standardSolutionCode} onChange={(event) => setStandardSolutionCode(event.target.value)} placeholder={t("drafts.standardSolutionPlaceholder")} />
                </Field>
              </section>
              {showReferenceSolver ? (
                <>
                  <section className="grid gap-4 xl:grid-cols-[220px_minmax(0,1fr)]">
                    <Field label={t("drafts.referenceSolutionLanguage")}>
                      <select className={selectClass} value={referenceSolutionLanguage} onChange={(event) => setReferenceSolutionLanguage(event.target.value)}>
                        <option value="cpp">C++</option>
                        <option value="python">Python</option>
                        <option value="java">Java</option>
                      </select>
                    </Field>
                    <Field label={t("drafts.referenceSolution")} hint={t("drafts.referenceSolutionHelper")}>
                      <textarea className={`${textareaClass} min-h-64 font-mono`} value={referenceSolutionCode} onChange={(event) => setReferenceSolutionCode(event.target.value)} placeholder={t("drafts.referenceSolutionPlaceholder")} />
                    </Field>
                  </section>
                  <Field label={t("drafts.stressTestcaseGeneratorPython")} hint={t("drafts.stressTestcaseGeneratorHelper")}>
                    <textarea className={`${textareaClass} min-h-64 font-mono`} value={stressTestcaseGeneratorPython} onChange={(event) => setStressTestcaseGeneratorPython(event.target.value)} placeholder={t("drafts.stressTestcaseGeneratorPlaceholder")} />
                  </Field>
                </>
              ) : null}
              <section className="grid gap-4 xl:grid-cols-2">
                <Field label={t("drafts.testcaseGeneratorPython")}>
                  <textarea className={`${textareaClass} min-h-64 font-mono`} value={testcaseGeneratorPython} onChange={(event) => setTestcaseGeneratorPython(event.target.value)} placeholder={t("drafts.testcaseGeneratorPlaceholder")} />
                </Field>
                <Field label={t("drafts.generationPlan")}>
                  <textarea className={`${textareaClass} min-h-64`} value={generationPlan} onChange={(event) => setGenerationPlan(event.target.value)} placeholder={t("drafts.generationPlanPlaceholder")} />
                </Field>
              </section>
              <section className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.testCases")}</h3>
                  <Button variant="outline" onClick={addCase}>{t("problems.addCase")}</Button>
                </div>
                {testCases.map((testCase, index) => (
                  <article key={index} className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-3">
                    <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                      <strong className="text-sm text-[var(--oj-ink)]">{t("problems.caseTitle", { index: index + 1 })}</strong>
                      <div className="flex items-center gap-3">
                        <label className="flex items-center gap-2 text-sm text-[var(--oj-ink-muted)]">
                          <input type="checkbox" checked={testCase.sample} onChange={(event) => updateCase(index, { sample: event.target.checked })} />
                          {t("problems.sample")}
                        </label>
                        <Button size="sm" variant="ghost" disabled={testCases.length <= 1} onClick={() => removeCase(index)}>{t("common.remove")}</Button>
                      </div>
                    </div>
                    <div className="grid gap-3 lg:grid-cols-2">
                      <Field label={t("problems.input")}>
                        <textarea className={`${textareaClass} min-h-28 font-mono`} value={testCase.input} onChange={(event) => updateCase(index, { input: event.target.value })} />
                      </Field>
                      <Field label={t("problems.expectedOutput")}>
                        <textarea className={`${textareaClass} min-h-28 font-mono`} value={testCase.expectedOutput} onChange={(event) => updateCase(index, { expectedOutput: event.target.value })} />
                      </Field>
                    </div>
                  </article>
                ))}
              </section>
              <Field label={t("drafts.refineNoteLabel")}>
                <textarea className={`${textareaClass} min-h-28`} value={refineNote} onChange={(event) => setRefineNote(event.target.value)} placeholder={t("drafts.refineNotePlaceholder")} />
              </Field>
              <Button disabled={refining || !editDirty} onClick={() => void refineDraft()}>{refining ? t("common.loading") : t("drafts.refineSubmit")}</Button>
              <section className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.manualReview")}</h3>
                    <p className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("drafts.manualReviewHint")}</p>
                  </div>
                  <Button
                    className="bg-emerald-700 hover:bg-emerald-800"
                    disabled={imported || archived || actionPending || currentDraft.verificationStatus === "MANUAL_VERIFIED"}
                    onClick={() => onManualReview(currentDraft)}
                  >
                    {pendingAction === "manualReview" ? t("common.loading") : t("drafts.manualReview")}
                  </Button>
                </div>
              </section>
            </div>
          ) : null}

          {tab === "regenerate" ? (
            <div className="space-y-4">
              <Field label={t("drafts.regenerateFeedbackLabel")}>
                <textarea className={`${textareaClass} min-h-[260px]`} value={feedback} onChange={(event) => setFeedback(event.target.value)} placeholder={t("drafts.regenerateFeedbackPlaceholder")} />
              </Field>
              <Button disabled={regenerating || !feedback.trim()} onClick={() => void regenerateDraft()}>{regenerating ? t("common.loading") : t("drafts.regenerateSubmit")}</Button>
            </div>
          ) : null}
        </div>
      )}
    </SidePanel>
  );
}

type VerificationEntry = {
  code?: string;
  message?: string;
  field?: string | null;
};

type VerificationBlock = {
  status?: string;
  errors?: VerificationEntry[];
  warnings?: VerificationEntry[];
};

type CrossCheckMismatchEntry = {
  name?: string;
  input?: string;
  standardOutput?: string;
  referenceOutput?: string;
};

type CrossCheckBlock = VerificationBlock & {
  caseCount?: number;
  mismatches?: CrossCheckMismatchEntry[];
};

type ComplexityBenchmarkRunEntry = {
  name?: string;
  status?: string;
  timeMillis?: number | null;
  memoryKb?: number | null;
  message?: string | null;
};

type ComplexityBlock = VerificationBlock & {
  claimedComplexity?: string | null;
  inferredComplexity?: string | null;
  benchmarkRuns?: ComplexityBenchmarkRunEntry[];
};

type RepairTaskBlock = {
  category?: string | null;
  repairScope?: string | null;
  confidence?: number | null;
  allowedFields?: string[] | null;
  forbiddenFields?: string[] | null;
  evidence?: string[] | null;
};

type ManualReviewBlock = {
  reviewerUserId?: number | string | null;
  reviewedAt?: string | null;
  note?: string | null;
};

type VerificationReportJson = {
  status?: string;
  staticReport?: VerificationBlock | null;
  sandboxReport?: VerificationBlock | null;
  crossCheckReport?: CrossCheckBlock | null;
  complexityReport?: ComplexityBlock | null;
  failureClassification?: RepairTaskBlock | null;
  manualReview?: ManualReviewBlock | null;
};

function VerificationReportPanel({ draft }: { draft: ProblemDraftResponse }) {
  const { t } = useI18n();
  const parsed = parseVerificationReport(draft.verificationReportJson);
  const parseFailed = Boolean(draft.verificationReportJson && !parsed);
  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={verificationTone(draft.verificationStatus)}>{verificationLabel(draft.verificationStatus, t)}</Badge>
          <Badge tone="neutral">{t("drafts.repairAttempts", { count: draft.repairAttemptCount ?? 0 })}</Badge>
        </div>
        {draft.lastRepairReason ? <p className="mt-3 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("drafts.lastRepairReason")}: {draft.lastRepairReason}</p> : null}
      </section>

      {parseFailed ? <ErrorPanel title={t("drafts.verificationReportInvalid")} /> : null}
      {!draft.verificationReportJson ? (
        <div className="rounded-xl border border-dashed border-[var(--oj-border)] bg-white p-4 text-sm text-[var(--oj-ink-muted)]">{t("drafts.verificationReportEmpty")}</div>
      ) : null}

      {parsed?.manualReview ? <ManualReviewPanel block={parsed.manualReview} /> : null}
      {parsed?.failureClassification ? <FailureClassificationPanel block={parsed.failureClassification} /> : null}
      {parsed?.staticReport ? <VerificationBlockPanel title={t("drafts.staticValidation")} block={parsed.staticReport} /> : null}
      {parsed?.sandboxReport ? <SandboxReportPanels block={parsed.sandboxReport} /> : null}
      {parsed?.crossCheckReport ? <CrossCheckReportPanel block={parsed.crossCheckReport} /> : null}
      {parsed?.complexityReport ? <ComplexityReportPanel block={parsed.complexityReport} /> : null}

      {draft.verificationReportJson ? (
        <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
          <h3 className="mb-3 text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.rawVerificationReport")}</h3>
          <pre className="max-h-[520px] overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 text-xs leading-5 text-white">
            {parsed ? JSON.stringify(parsed, null, 2) : draft.verificationReportJson}
          </pre>
        </section>
      ) : null}
    </div>
  );
}

function ManualReviewPanel({ block }: { block: ManualReviewBlock }) {
  const { t } = useI18n();
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.manualReviewReport")}</h3>
        <Badge tone="green">{t("verificationStatus.MANUAL_VERIFIED")}</Badge>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.manualReviewReviewer")}</p>
          <p className="mt-1 text-sm tabular-nums text-[var(--oj-ink)]">{block.reviewerUserId == null ? "--" : `#${block.reviewerUserId}`}</p>
        </div>
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.manualReviewReviewedAt")}</p>
          <p className="mt-1 text-sm tabular-nums text-[var(--oj-ink)]">{block.reviewedAt ? formatDateTime(block.reviewedAt) : "--"}</p>
        </div>
      </div>
      {block.note ? (
        <div className="mt-3">
          <p className="mb-2 text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.manualReviewNote")}</p>
          <p className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-sm leading-6 text-[var(--oj-ink)]">{block.note}</p>
        </div>
      ) : null}
    </section>
  );
}

function FailureClassificationPanel({ block }: { block: RepairTaskBlock }) {
  const { t } = useI18n();
  const allowed = block.allowedFields ?? [];
  const evidence = block.evidence ?? [];
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.failureClassification")}</h3>
        {block.category ? (
          <Badge tone={block.category === "UNKNOWN_REQUIRES_MANUAL_REVIEW" ? "amber" : "blue"} title={block.category}>
            {failureCategoryLabel(block.category, t)}
          </Badge>
        ) : null}
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.repairScope")}</p>
          <p className="mt-1 text-sm text-[var(--oj-ink)]">{block.repairScope || "--"}</p>
        </div>
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.repairConfidence")}</p>
          <p className="mt-1 text-sm text-[var(--oj-ink)]">{block.confidence == null ? "--" : `${Math.round(block.confidence * 100)}%`}</p>
        </div>
      </div>
      <div className="mt-3">
        <p className="mb-2 text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.allowedRepairFields")}</p>
        {allowed.length ? (
          <div className="flex flex-wrap gap-2">
            {allowed.map((field) => <Badge key={field} tone="neutral" title={field}>{repairFieldLabel(field, t)}</Badge>)}
          </div>
        ) : (
          <p className="text-sm text-[var(--oj-ink-muted)]">{t("drafts.noAllowedRepairFields")}</p>
        )}
      </div>
      {evidence.length ? (
        <div className="mt-4">
          <p className="mb-2 text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.failureEvidence")}</p>
          <ul className="space-y-2">
            {evidence.map((item, index) => (
              <li key={`${item}-${index}`} className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-sm leading-6 text-[var(--oj-ink)]">
                {item}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

function SandboxReportPanels({ block }: { block: VerificationBlock }) {
  const sampleBlock = filterVerificationBlock(block, isSampleVerificationEntry);
  const officialBlock = filterVerificationBlock(block, (entry) => !isSampleVerificationEntry(entry));
  return (
    <>
      <VerificationBlockPanel titleKey="drafts.sampleVerification" block={sampleBlock} />
      <VerificationBlockPanel titleKey="drafts.officialHiddenVerification" block={officialBlock} />
    </>
  );
}

function CrossCheckReportPanel({ block }: { block: CrossCheckBlock }) {
  const { t } = useI18n();
  const errors = block.errors ?? [];
  const warnings = block.warnings ?? [];
  const mismatches = block.mismatches ?? [];
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.crossCheckVerification")}</h3>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="neutral">{t("drafts.crossCheckCaseCount", { count: block.caseCount ?? 0 })}</Badge>
          <Badge tone={verificationTone(block.status)}>{verificationLabel(block.status, t)}</Badge>
        </div>
      </div>
      <VerificationEntryList title={t("drafts.verificationErrors")} entries={errors} emptyLabel={t("drafts.noVerificationErrors")} tone="red" />
      {mismatches.length ? (
        <div className="mt-4 space-y-3">
          <h4 className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.crossCheckMismatch")}</h4>
          {mismatches.map((item, index) => (
            <article key={`${item.name ?? "mismatch"}-${index}`} className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <Badge tone="red">{item.name || t("drafts.crossCheckMismatch")}</Badge>
              </div>
              <div className="grid gap-3 lg:grid-cols-3">
                <CodeSnippet title={t("problems.input")} value={item.input || ""} />
                <CodeSnippet title={t("drafts.crossCheckStandardOutput")} value={item.standardOutput || ""} />
                <CodeSnippet title={t("drafts.crossCheckReferenceOutput")} value={item.referenceOutput || ""} />
              </div>
            </article>
          ))}
        </div>
      ) : null}
      <div className="mt-4">
        <VerificationEntryList title={t("drafts.verificationWarnings")} entries={warnings} emptyLabel={t("drafts.noVerificationWarnings")} tone="amber" />
      </div>
    </section>
  );
}

function ComplexityReportPanel({ block }: { block: ComplexityBlock }) {
  const { t } = useI18n();
  const errors = block.errors ?? [];
  const warnings = block.warnings ?? [];
  const runs = block.benchmarkRuns ?? [];
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("drafts.complexityAudit")}</h3>
        <Badge tone={verificationTone(block.status)}>{verificationLabel(block.status, t)}</Badge>
      </div>
      <div className="mb-4 grid gap-3 md:grid-cols-2">
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.claimedComplexity")}</p>
          <p className="mt-1 text-sm text-[var(--oj-ink)]">{block.claimedComplexity || "--"}</p>
        </div>
        <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3">
          <p className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{t("drafts.inferredComplexity")}</p>
          <p className="mt-1 text-sm text-[var(--oj-ink)]">{complexityValueLabel(block.inferredComplexity, t)}</p>
        </div>
      </div>
      {runs.length ? (
        <div className="mb-4 max-w-full overflow-x-auto overscroll-x-contain rounded-lg border border-[var(--oj-border-soft)]">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
              <tr>
                <th className="px-3 py-2 text-left">{t("drafts.benchmarkCase")}</th>
                <th className="px-3 py-2 text-left">{t("drafts.benchmarkStatus")}</th>
                <th className="px-3 py-2 text-right">{t("drafts.benchmarkTime")}</th>
                <th className="px-3 py-2 text-right">{t("drafts.benchmarkMemory")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--oj-border-soft)]">
              {runs.map((run, index) => (
                <tr key={`${run.name ?? "case"}-${index}`}>
                  <td className="px-3 py-2 font-mono text-xs">{run.name || "--"}</td>
                  <td className="px-3 py-2">{executionStatusLabel(run.status, t)}</td>
                  <td className="px-3 py-2 text-right">{benchmarkMetricLabel(run.timeMillis, "ms", t)}</td>
                  <td className="px-3 py-2 text-right">{benchmarkMetricLabel(run.memoryKb, "KB", t)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <VerificationEntryList title={t("drafts.verificationErrors")} entries={errors} emptyLabel={t("drafts.noVerificationErrors")} tone="red" />
      <div className="mt-4">
        <VerificationEntryList title={t("drafts.verificationWarnings")} entries={warnings} emptyLabel={t("drafts.noVerificationWarnings")} tone="amber" />
      </div>
    </section>
  );
}

function CodeSnippet({ title, value }: { title: string; value: string }) {
  return (
    <div>
      <h5 className="mb-1 text-xs font-semibold text-[var(--oj-ink-muted)]">{title}</h5>
      <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 text-xs leading-5 text-white">{value || "--"}</pre>
    </div>
  );
}

function VerificationBlockPanel({ title, titleKey, block }: { title?: string; titleKey?: string; block: VerificationBlock }) {
  const { t } = useI18n();
  const errors = block.errors ?? [];
  const warnings = block.warnings ?? [];
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{titleKey ? t(titleKey) : title}</h3>
        <Badge tone={verificationTone(block.status)}>{verificationLabel(block.status, t)}</Badge>
      </div>
      <VerificationEntryList title={t("drafts.verificationErrors")} entries={errors} emptyLabel={t("drafts.noVerificationErrors")} tone="red" />
      <div className="mt-4">
        <VerificationEntryList title={t("drafts.verificationWarnings")} entries={warnings} emptyLabel={t("drafts.noVerificationWarnings")} tone="amber" />
      </div>
    </section>
  );
}

function VerificationEntryList({ title, entries, emptyLabel, tone }: { title: string; entries: VerificationEntry[]; emptyLabel: string; tone: "red" | "amber" }) {
  const { t } = useI18n();
  return (
    <div>
      <h4 className="text-xs font-semibold uppercase text-[var(--oj-ink-muted)]">{title}</h4>
      {entries.length ? (
        <ul className="mt-2 space-y-2">
          {entries.map((entry, index) => (
            <li key={`${entry.code ?? "entry"}-${index}`} className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-sm leading-6 text-[var(--oj-ink)]">
              <div className="flex flex-wrap items-center gap-2">
                {entry.code ? <Badge tone={tone} title={entry.code}>{verificationCodeLabel(entry.code, t)}</Badge> : null}
                {entry.field ? <Badge tone="neutral">{entry.field}</Badge> : null}
              </div>
              <p className="mt-2 whitespace-pre-wrap">{entry.message || "--"}</p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-2 text-sm text-[var(--oj-ink-muted)]">{emptyLabel}</p>
      )}
    </div>
  );
}

function filterVerificationBlock(block: VerificationBlock, predicate: (entry: VerificationEntry) => boolean): VerificationBlock {
  return {
    status: block.status,
    errors: (block.errors ?? []).filter(predicate),
    warnings: (block.warnings ?? []).filter(predicate)
  };
}

function isSampleVerificationEntry(entry: VerificationEntry) {
  const code = entry.code?.toUpperCase() ?? "";
  const field = entry.field ?? "";
  return code.includes("SAMPLE") || field.startsWith("testCases[");
}

function parseVerificationReport(value?: string | null): VerificationReportJson | null {
  if (!value?.trim()) return null;
  try {
    const parsed = JSON.parse(value) as VerificationReportJson;
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

function shouldShowReferenceSolver(draft: ProblemDraftResponse) {
  if (draft.referenceSolutionCode?.trim()) return true;
  if (draft.stressTestcaseGeneratorPython?.trim()) return true;
  const parsed = parseVerificationReport(draft.verificationReportJson);
  return Boolean(parsed?.crossCheckReport);
}

function shouldShowStressGenerator(draft: ProblemDraftResponse) {
  if (draft.stressTestcaseGeneratorPython?.trim()) return true;
  const parsed = parseVerificationReport(draft.verificationReportJson);
  return Boolean(parsed?.crossCheckReport);
}

function canImportDraft(draft: ProblemDraftResponse) {
  return draft.validationStatus === "VALID"
    && (draft.verificationStatus === "EXECUTION_VERIFIED" || draft.verificationStatus === "MANUAL_VERIFIED");
}

function draftFormBaseline(source: ProblemDraftResponse | null) {
  return {
    title: source?.title ?? "",
    difficulty: source?.difficulty || "EASY",
    tagText: (source?.tags ?? []).join(", "),
    statement: source?.statement || "",
    notes: source?.notes || "",
    standardSolutionLanguage: source?.standardSolutionLanguage || "cpp",
    standardSolutionCode: source?.standardSolutionCode || "",
    referenceSolutionLanguage: source?.referenceSolutionLanguage || source?.standardSolutionLanguage || "cpp",
    referenceSolutionCode: source?.referenceSolutionCode || "",
    testcaseGeneratorPython: source?.testcaseGeneratorPython || "",
    stressTestcaseGeneratorPython: source?.stressTestcaseGeneratorPython || "",
    generationPlan: source?.generationPlan || "",
    timeLimitMillis: source?.timeLimitMillis || 1000,
    memoryLimitKb: source?.memoryLimitKb || 262144,
    testCases: source?.testCases?.length
      ? source.testCases.map((item) => ({ input: item.input, expectedOutput: item.expectedOutput, sample: item.sample }))
      : [{ input: "", expectedOutput: "", sample: true }],
    refineNote: source?.refineNote || ""
  };
}

function CodePreview({ title, content, language, emptyLabel }: { title: string; content: string; language: string; emptyLabel: string }) {
  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <h3 className="mb-3 text-sm font-semibold text-[var(--oj-ink)]">{title}</h3>
      {content.trim() ? (
        <MarkdownView content={codeToMarkdown(content, language)} className="[&_pre]:max-h-[420px] [&_pre]:overflow-auto" />
      ) : (
        <p className="text-sm text-[var(--oj-ink-muted)]">{emptyLabel}</p>
      )}
    </section>
  );
}

function codeToMarkdown(code: string, language: string) {
  const normalizedCode = stripOuterCodeFence(code).replace(/\r\n/g, "\n");
  const maxBacktickRun = Math.max(0, ...Array.from(normalizedCode.matchAll(/`+/g), (match) => match[0].length));
  const fence = "`".repeat(Math.max(3, maxBacktickRun + 1));
  return `${fence}${markdownLanguage(language)}\n${normalizedCode}\n${fence}`;
}

function stripOuterCodeFence(value: string) {
  const normalized = value.trim().replace(/\r\n/g, "\n");
  const match = normalized.match(/^(`{3,}|~{3,})[^\n]*\n([\s\S]*?)\n\1\s*$/);
  return match ? match[2] : value;
}

function markdownLanguage(language: string) {
  const normalized = language.toLowerCase();
  if (normalized === "python" || normalized === "py") return "python";
  if (normalized === "java") return "java";
  if (normalized === "cpp" || normalized === "c++") return "cpp";
  return normalized.replace(/[^a-z0-9_-]/g, "");
}

function CasesPreview({ cases }: { cases: TestCaseDto[] }) {
  const { t } = useI18n();
  if (!cases.length) {
    return <div className="rounded-xl border border-dashed border-[var(--oj-border)] bg-white p-4 text-sm text-[var(--oj-ink-muted)]">{t("problems.noSamples")}</div>;
  }
  return (
    <section className="min-w-0 max-w-full overflow-hidden rounded-xl border border-[var(--oj-border)] bg-white">
      <div className="max-w-full overflow-x-auto overscroll-x-contain">
        <table className="w-full min-w-[760px] text-sm">
          <thead className="border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-xs font-semibold text-[var(--oj-ink-muted)]">
            <tr>
              <th className="w-[44%] px-4 py-3 text-left">{t("problems.input")}</th>
              <th className="w-[44%] px-4 py-3 text-left">{t("problems.output")}</th>
              <th className="w-[12%] px-4 py-3 text-center">{t("problems.sample")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[var(--oj-border-soft)]">
            {cases.map((item, index) => (
              <tr key={index}>
                <td className="px-4 py-3 align-top"><pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 text-xs leading-5 text-white">{item.input || "--"}</pre></td>
                <td className="px-4 py-3 align-top"><pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 text-xs leading-5 text-white">{item.expectedOutput || "--"}</pre></td>
                <td className="px-4 py-3 text-center"><Badge tone={item.sample ? "green" : "neutral"}>{item.sample ? t("common.yes") : t("common.no")}</Badge></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function TabButton({ active, children, onClick }: { active: boolean; children: React.ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      className={cn(
        "h-9 rounded-lg px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
        active ? "bg-[var(--oj-primary)] text-white" : "text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)]"
      )}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function parseTags(value: string) {
  return Array.from(new Set(value.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean)));
}

function validationLabel(status: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`validationStatus.${status}`, undefined, status);
}

function verificationLabel(status: string | null | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  const normalized = status || "NOT_RUN";
  return t(`verificationStatus.${normalized}`, undefined, normalized);
}

function verificationCodeLabel(code: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`drafts.verificationCodes.${code}`, undefined, code);
}

function failureCategoryLabel(category: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`drafts.failureCategories.${category}`, undefined, category);
}

function repairFieldLabel(field: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`drafts.repairFields.${field}`, undefined, field);
}

function executionStatusLabel(status: string | null | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!status) return "--";
  return t(`drafts.executionStatus.${status}`, undefined, status);
}

function complexityValueLabel(value: string | null | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (!value) return "--";
  if (value === "UNKNOWN") return t("drafts.complexityUnknown");
  return value;
}

function benchmarkMetricLabel(value: number | null | undefined, unit: string, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (value == null) return t("drafts.benchmarkMetricMissing");
  return `${value} ${unit}`;
}

function verificationTone(status: string | null | undefined): "blue" | "green" | "amber" | "red" | "neutral" {
  if (status === "EXECUTION_VERIFIED" || status === "MANUAL_VERIFIED" || status === "PASSED") return "green";
  if (status === "FAILED") return "red";
  if (status === "STATIC_FAILED") return "amber";
  return "neutral";
}

function emptyCase(sample: boolean): TestCaseDto {
  return { input: "", expectedOutput: "", sample };
}
