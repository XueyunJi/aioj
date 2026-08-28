import * as React from "react";
import { ApiError, api, type Difficulty, type ProblemLanguageTimeLimitMultipliers, type ProblemPayload, type ProblemResponse, type ProblemVisibility, type TestCaseDto } from "@aioj/api-client";
import { Badge, Button, cn } from "@aioj/ui-react";
import { ErrorPanel, Field, SidePanel, inputClass, selectClass, textareaClass } from "./Common";
import { MarkdownView } from "./MarkdownView";
import { TestcasePackagePanel } from "./TestcasePackagePanel";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { difficultyTone, formatBytes } from "../lib/format";
import { readableCaughtError } from "../lib/readableError";

const DIFFICULTIES: Difficulty[] = ["EASY", "MEDIUM", "HARD", "CHALLENGE"];
const STANDARD_SOLUTION_LANGUAGES = ["cpp", "python", "java"] as const;
const STATEMENT_MAX = 20000;
const TITLE_MAX = 100;

type TabKey = "basic" | "statement" | "solution" | "generator" | "package";
type StandardSolutionLanguage = typeof STANDARD_SOLUTION_LANGUAGES[number];
type StandardSolutionMap = Record<StandardSolutionLanguage, string>;
type TimeLimitMultiplierMap = Record<StandardSolutionLanguage, number>;

export function ProblemEditorPanel({
  open,
  onOpenChange,
  problem,
  onSaved
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  problem: ProblemResponse | null;
  onSaved: (problem: ProblemResponse) => Promise<void> | void;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const [tab, setTab] = React.useState<TabKey>("basic");
  const [savedProblem, setSavedProblem] = React.useState<ProblemResponse | null>(null);
  const [title, setTitle] = React.useState("");
  const [difficulty, setDifficulty] = React.useState<Difficulty>("EASY");
  const [visibility, setVisibility] = React.useState<ProblemVisibility>("PUBLIC");
  const [tagText, setTagText] = React.useState("");
  const [timeLimitMillis, setTimeLimitMillis] = React.useState(1000);
  const [timeLimitMultipliers, setTimeLimitMultipliers] = React.useState<TimeLimitMultiplierMap>(defaultTimeLimitMultipliers());
  const [memoryLimitMb, setMemoryLimitMb] = React.useState(256);
  const [statement, setStatement] = React.useState("");
  const [notes, setNotes] = React.useState("");
  const [standardSolutionLanguage, setStandardSolutionLanguage] = React.useState<StandardSolutionLanguage>("cpp");
  const [standardSolutions, setStandardSolutions] = React.useState<StandardSolutionMap>(emptyStandardSolutions());
  const [solutionLoading, setSolutionLoading] = React.useState(false);
  const [testcaseGeneratorPython, setTestcaseGeneratorPython] = React.useState("");
  const [testcaseGeneratorLoading, setTestcaseGeneratorLoading] = React.useState(false);
  const [samples, setSamples] = React.useState<TestCaseDto[]>([emptySample()]);
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<string | null>(null);
  const [saving, setSaving] = React.useState(false);

  const currentProblem = savedProblem ?? problem;
  const isEditing = Boolean(currentProblem?.id);
  const tags = parseTags(tagText);

  const formSnapshot = React.useMemo(() => JSON.stringify({
    title,
    difficulty,
    visibility,
    tagText,
    timeLimitMillis,
    timeLimitMultipliers,
    memoryLimitMb,
    statement,
    notes,
    standardSolutions,
    testcaseGeneratorPython,
    samples
  }), [title, difficulty, visibility, tagText, timeLimitMillis, timeLimitMultipliers, memoryLimitMb, statement, notes, standardSolutions, testcaseGeneratorPython, samples]);

  const [baselineSnapshot, setBaselineSnapshot] = React.useState<string | null>(null);
  const dirty = baselineSnapshot !== null && formSnapshot !== baselineSnapshot;

  React.useEffect(() => {
    if (!open || solutionLoading || testcaseGeneratorLoading) return;
    setBaselineSnapshot(formSnapshot);
    // Capture the baseline once the panel is open and async loads have settled.
  }, [open, problem, solutionLoading, testcaseGeneratorLoading]);

  React.useEffect(() => {
    if (!open) return;
    const source = problem;
    setSavedProblem(null);
    setTab("basic");
    setFieldErrors({});
    setError(null);
    setSolutionLoading(false);
    if (source) {
      setTitle(source.title);
      setDifficulty(normalizeDifficulty(source.difficulty));
      setVisibility(source.visibility ?? "PUBLIC");
      setTagText(source.tags.join(", "));
      setTimeLimitMillis(source.timeLimitMillis);
      setTimeLimitMultipliers(normalizeTimeLimitMultipliers(source.languageTimeLimitMultipliers));
      setMemoryLimitMb(Math.max(16, Math.round(source.memoryLimitKb / 1024)));
      setStatement(source.statement);
      setNotes(source.notes ?? "");
      setStandardSolutionLanguage("cpp");
      setStandardSolutions(emptyStandardSolutions());
      setTestcaseGeneratorPython("");
      setSamples(source.samples.length ? source.samples.map((item) => ({ ...item, sample: true })) : [emptySample()]);
      let canceled = false;
      setSolutionLoading(true);
      void api.problemStandardSolutions(source.id).then((solutions) => {
        if (canceled) return;
        const next = emptyStandardSolutions();
        for (const solution of solutions ?? []) {
          const language = normalizeStandardSolutionLanguage(solution.language);
          if (language) next[language] = solution.content || "";
        }
        setStandardSolutions(next);
        const firstLanguage = STANDARD_SOLUTION_LANGUAGES.find((language) => next[language].trim());
        setStandardSolutionLanguage(firstLanguage ?? "cpp");
      }).catch((caught) => {
        if (canceled) return;
        setError(readableCaughtError(caught, locale, t("problems.standardSolutionLoadFailed")));
      }).finally(() => {
        if (!canceled) setSolutionLoading(false);
      });
      setTestcaseGeneratorLoading(true);
      void api.problemTestcaseGenerator(source.id).then((generator) => {
        if (canceled) return;
        setTestcaseGeneratorPython(generator?.content || "");
      }).catch((caught) => {
        if (canceled) return;
        setError(readableCaughtError(caught, locale, t("problems.testcaseGeneratorLoadFailed")));
      }).finally(() => {
        if (!canceled) setTestcaseGeneratorLoading(false);
      });
      return () => {
        canceled = true;
      };
    }
    setStandardSolutionLanguage("cpp");
    setStandardSolutions(emptyStandardSolutions());
    setSolutionLoading(false);
    setTestcaseGeneratorPython("");
    setTestcaseGeneratorLoading(false);
    setTitle("");
    setDifficulty("EASY");
    setVisibility("PUBLIC");
    setTagText("");
    setTimeLimitMillis(1000);
    setTimeLimitMultipliers(defaultTimeLimitMultipliers());
    setMemoryLimitMb(256);
    setStatement("");
    setNotes("");
    setSamples([emptySample()]);
  }, [open, problem]);

  function updateSample(index: number, patch: Partial<TestCaseDto>) {
    setSamples((items) => items.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch, sample: true } : item)));
  }

  function removeSample(index: number) {
    setSamples((items) => items.filter((_, itemIndex) => itemIndex !== index));
  }

  function addSample() {
    setSamples((items) => [...items, emptySample()]);
  }

  function updateStandardSolution(language: StandardSolutionLanguage, code: string) {
    setStandardSolutions((current) => ({ ...current, [language]: code }));
  }

  function updateTimeLimitMultiplier(language: StandardSolutionLanguage, value: number) {
    setTimeLimitMultipliers((current) => ({ ...current, [language]: value }));
  }

  async function save() {
    if (!dirty) return;
    setError(null);
    setFieldErrors({});
    const localErrors = validate();
    if (Object.keys(localErrors).length) {
      setFieldErrors(localErrors);
      setTab(localErrors.title || localErrors.timeLimitMillis || localErrors.timeLimitMultipliers || localErrors.memoryLimitKb ? "basic" : "statement");
      return;
    }

    const payload: ProblemPayload = {
      title: title.trim(),
      difficulty,
      statement: statement.trim(),
      notes: notes.trim() || undefined,
      tags,
      timeLimitMillis: Number(timeLimitMillis),
      languageTimeLimitMultipliers: timeLimitMultipliers,
      memoryLimitKb: Math.round(Number(memoryLimitMb) * 1024),
      standardSolutions: STANDARD_SOLUTION_LANGUAGES.map((language) => ({
        language,
        code: standardSolutions[language]
      })),
      testcaseGeneratorPython,
      visibility,
      testCases: samples.map((item) => ({
        input: item.input,
        expectedOutput: item.expectedOutput,
        sample: true
      }))
    };

    setSaving(true);
    try {
      const saved = currentProblem?.id ? await api.updateProblem(currentProblem.id, payload) : await api.createProblem(payload);
      setSavedProblem(saved);
      setBaselineSnapshot(formSnapshot);
      await onSaved(saved);
      toast.success(t("problems.savedMessage"));
    } catch (caught) {
      if (caught instanceof ApiError) {
        setFieldErrors(caught.details ?? {});
        toast.error(caught.userMessage);
      } else {
        toast.error(readableCaughtError(caught, locale, t("problems.saveFailed")));
      }
    } finally {
      setSaving(false);
    }
  }

  function validate() {
    const next: Record<string, string> = {};
    if (!title.trim()) next.title = t("problems.titleRequired");
    if (title.trim().length > TITLE_MAX) next.title = t("problems.titleTooLong");
    if (!statement.trim()) next.statement = t("problems.statementRequired");
    if (statement.length > STATEMENT_MAX) next.statement = t("problems.statementTooLong");
    if (Number(timeLimitMillis) < 100) next.timeLimitMillis = t("problems.timeLimitInvalid");
    if (STANDARD_SOLUTION_LANGUAGES.some((language) => !isValidMultiplier(timeLimitMultipliers[language]))) {
      next.timeLimitMultipliers = t("problems.timeLimitMultiplierInvalid");
    }
    if (Number(memoryLimitMb) < 16) next.memoryLimitKb = t("problems.memoryLimitInvalid");
    if (!samples.length) next.testCases = t("problems.sampleRequired");
    return next;
  }

  return (
    <SidePanel
      open={open}
      onOpenChange={onOpenChange}
      wide
      presentation="workspace"
      title={isEditing ? t("problems.editModal") : t("problems.createModal")}
      description={currentProblem?.id ? `#${currentProblem.id}` : t("problems.drawerSubtitle")}
      footer={tab === "package" ? undefined : (
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--oj-ink-muted)]">
            <Badge tone={difficultyTone(difficulty)}>{t(`difficulty.${difficulty}`)}</Badge>
            <span className="tabular-nums">{timeLimitMillis} ms</span>
            <span className="tabular-nums">{formatBytes(memoryLimitMb * 1024 * 1024)}</span>
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="outline" disabled={saving} onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
            <Button disabled={saving || !dirty} onClick={() => void save()}>{saving ? t("common.loading") : t("common.save")}</Button>
          </div>
        </div>
      )}
    >
      <div className="space-y-5">
        {error ? <ErrorPanel title={error} /> : null}
        <div className="flex flex-wrap gap-2 border-b border-[var(--oj-border-soft)] pb-3">
          <TabButton active={tab === "basic"} onClick={() => setTab("basic")}>{t("problems.editorBasic")}</TabButton>
          <TabButton active={tab === "statement"} onClick={() => setTab("statement")}>{t("problems.editorStatement")}</TabButton>
          <TabButton active={tab === "solution"} onClick={() => setTab("solution")}>{t("problems.editorSolution")}</TabButton>
          <TabButton active={tab === "generator"} onClick={() => setTab("generator")}>{t("problems.editorTestcaseGenerator")}</TabButton>
          <TabButton active={tab === "package"} onClick={() => setTab("package")}>{t("problems.editorPackage")}</TabButton>
        </div>

        {tab === "basic" ? (
          <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
            <div className="space-y-4">
              <Field label={t("problems.titleLabel")} error={fieldErrors.title}>
                <input className={inputClass} value={title} maxLength={TITLE_MAX + 20} onChange={(event) => setTitle(event.target.value)} placeholder={t("problems.titlePlaceholder")} />
              </Field>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={t("common.difficulty")} error={fieldErrors.difficulty}>
                  <select className={selectClass} value={difficulty} onChange={(event) => setDifficulty(event.target.value as Difficulty)}>
                    {DIFFICULTIES.map((item) => <option key={item} value={item}>{t(`difficulty.${item}`)}</option>)}
                  </select>
                </Field>
                <Field label={t("common.tags")} error={fieldErrors.tags} hint={t("problems.tagsHelper")}>
                  <input className={inputClass} value={tagText} onChange={(event) => setTagText(event.target.value)} placeholder={t("problems.tagsInputPlaceholder")} />
                </Field>
              </div>
              <Field label={t("problems.visibilityLabel")} hint={t("problems.visibilityHelper")}>
                <select className={selectClass} value={visibility} onChange={(event) => setVisibility(event.target.value as ProblemVisibility)}>
                  <option value="PUBLIC">{t("problems.visibilityPublic")}</option>
                  <option value="PRIVATE">{t("problems.visibilityPrivate")}</option>
                </select>
              </Field>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label={`${t("problems.timeLimit")} (ms)`} error={fieldErrors.timeLimitMillis} hint={t("problems.timeLimitHelper")}>
                  <input className={inputClass} value={timeLimitMillis} min={100} step={100} type="number" onChange={(event) => setTimeLimitMillis(Number(event.target.value))} />
                </Field>
                <Field label={`${t("problems.memoryLimit")} (MB)`} error={fieldErrors.memoryLimitKb} hint={t("problems.memoryLimitHelper")}>
                  <input className={inputClass} value={memoryLimitMb} min={16} step={16} type="number" onChange={(event) => setMemoryLimitMb(Number(event.target.value))} />
                </Field>
              </div>
              <Field label={t("problems.languageTimeLimitMultipliers")} error={fieldErrors.timeLimitMultipliers} hint={t("problems.languageTimeLimitMultiplierHelper")}>
                <div className="grid gap-3 sm:grid-cols-3">
                  {STANDARD_SOLUTION_LANGUAGES.map((language) => (
                    <label key={language} className="space-y-1 text-sm">
                      <span className="text-xs font-medium text-[var(--oj-ink-muted)]">{languageLabel(language, t)}</span>
                      <input
                        className={inputClass}
                        value={timeLimitMultipliers[language]}
                        min={1}
                        max={10}
                        step={0.1}
                        type="number"
                        onChange={(event) => updateTimeLimitMultiplier(language, Number(event.target.value))}
                      />
                    </label>
                  ))}
                </div>
              </Field>
            </div>
            <aside className="rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4">
              <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.basicPreview")}</h3>
              <dl className="mt-4 space-y-3 text-sm">
                <PreviewRow label={t("problems.previewDifficulty")} value={t(`difficulty.${difficulty}`)} />
                <PreviewRow label={t("problems.previewTime")} value={`${timeLimitMillis} ms`} />
                <div>
                  <dt className="text-xs text-[var(--oj-ink-muted)]">{t("problems.effectiveTimeLimit")}</dt>
                  <dd className="mt-2 flex flex-wrap gap-1.5">
                    {STANDARD_SOLUTION_LANGUAGES.map((language) => (
                      <Badge key={language} tone="neutral">
                        {languageLabel(language, t)} {effectiveTimeLimitMillis(timeLimitMillis, timeLimitMultipliers[language])} ms
                      </Badge>
                    ))}
                  </dd>
                </div>
                <PreviewRow label={t("problems.previewMemory")} value={formatBytes(memoryLimitMb * 1024 * 1024)} />
                <div>
                  <dt className="text-xs text-[var(--oj-ink-muted)]">{t("problems.previewTags")}</dt>
                  <dd className="mt-2 flex flex-wrap gap-1.5">
                    {tags.length ? tags.map((tag) => <Badge key={tag} tone="neutral">{tag}</Badge>) : <span className="text-sm text-[var(--oj-ink-muted)]">{t("problems.noTags")}</span>}
                  </dd>
                </div>
              </dl>
            </aside>
          </div>
        ) : null}

        {tab === "statement" ? (
          <div className="space-y-5">
            <section className="grid gap-4 xl:grid-cols-2">
              <Field label={t("problems.statementEditor")} error={fieldErrors.statement} hint={t("problems.charCount", { count: statement.length, max: STATEMENT_MAX })}>
                <textarea className={`${textareaClass} min-h-[360px] font-mono`} value={statement} onChange={(event) => setStatement(event.target.value)} placeholder={t("problems.statementPlaceholder")} />
              </Field>
              <div className="min-h-[360px] rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <MarkdownView content={statement || `_${t("problems.statementPlaceholder")}_`} />
              </div>
            </section>
            <section className="grid gap-4 xl:grid-cols-2">
              <Field label={t("problems.notesLabel")}>
                <textarea className={`${textareaClass} min-h-[180px]`} value={notes} onChange={(event) => setNotes(event.target.value)} placeholder={t("problems.notesEditorPlaceholder")} />
              </Field>
              <div className="min-h-[180px] rounded-xl border border-[var(--oj-border)] bg-white p-4">
                <MarkdownView content={notes || `_${t("problems.notesEmpty")}_`} />
              </div>
            </section>
            <section className="space-y-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.testCases")}</h3>
                  {fieldErrors.testCases ? <p className="mt-1 text-sm text-[var(--oj-danger)]">{fieldErrors.testCases}</p> : null}
                </div>
                <Button variant="outline" onClick={addSample}>{t("problems.addCase")}</Button>
              </div>
              <div className="space-y-3">
                {samples.map((sample, index) => (
                  <article key={index} className="rounded-xl border border-[var(--oj-border-soft)] bg-white p-3">
                    <div className="mb-3 flex items-center justify-between gap-2">
                      <strong className="text-sm text-[var(--oj-ink)]">{t("problems.caseTitle", { index: index + 1 })}</strong>
                      <Button size="sm" variant="ghost" disabled={samples.length <= 1} onClick={() => removeSample(index)}>{t("common.remove")}</Button>
                    </div>
                    <div className="grid gap-3 lg:grid-cols-2">
                      <Field label={t("problems.input")}>
                        <textarea className={`${textareaClass} min-h-32 font-mono`} value={sample.input} onChange={(event) => updateSample(index, { input: event.target.value })} />
                      </Field>
                      <Field label={t("problems.expectedOutput")}>
                        <textarea className={`${textareaClass} min-h-32 font-mono`} value={sample.expectedOutput} onChange={(event) => updateSample(index, { expectedOutput: event.target.value })} />
                      </Field>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          </div>
        ) : null}

        {tab === "solution" ? (
          <div className="space-y-5">
            {solutionLoading ? <p className="text-sm text-[var(--oj-ink-muted)]">{t("common.loading")}</p> : null}
            <section className="grid gap-4 xl:grid-cols-[220px_minmax(0,1fr)]">
              <Field label={t("problems.standardSolutionLanguage")}>
                <select className={selectClass} value={standardSolutionLanguage} onChange={(event) => setStandardSolutionLanguage(event.target.value as StandardSolutionLanguage)}>
                  {STANDARD_SOLUTION_LANGUAGES.map((language) => (
                    <option key={language} value={language}>{languageLabel(language, t)}</option>
                  ))}
                </select>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {STANDARD_SOLUTION_LANGUAGES.map((language) => (
                    <Badge key={language} tone={standardSolutions[language].trim() ? "green" : "neutral"}>
                      {languageLabel(language, t)} · {standardSolutions[language].trim() ? t("problems.standardSolutionFilled") : t("problems.standardSolutionEmpty")}
                    </Badge>
                  ))}
                </div>
              </Field>
              <Field label={t("problems.standardSolutionCode")} hint={t("problems.standardSolutionHelper")}>
                <textarea
                  className={`${textareaClass} min-h-[460px] font-mono`}
                  value={standardSolutions[standardSolutionLanguage]}
                  onChange={(event) => updateStandardSolution(standardSolutionLanguage, event.target.value)}
                  placeholder={t("problems.standardSolutionPlaceholder")}
                />
              </Field>
            </section>
          </div>
        ) : null}

        {tab === "generator" ? (
          <div className="space-y-5">
            {testcaseGeneratorLoading ? <p className="text-sm text-[var(--oj-ink-muted)]">{t("common.loading")}</p> : null}
            <Field label={t("problems.testcaseGeneratorPython")} hint={t("problems.testcaseGeneratorHelper")}>
              <textarea
                className={`${textareaClass} min-h-[520px] font-mono`}
                value={testcaseGeneratorPython}
                onChange={(event) => setTestcaseGeneratorPython(event.target.value)}
                placeholder={t("problems.testcaseGeneratorPlaceholder")}
              />
            </Field>
          </div>
        ) : null}

        {tab === "package" ? (
          currentProblem?.id ? (
            <TestcasePackagePanel problemId={currentProblem.id} />
          ) : (
            <div className="rounded-xl border border-dashed border-[var(--oj-border)] bg-[var(--oj-surface-muted)] p-5">
              <h3 className="text-sm font-semibold text-[var(--oj-ink)]">{t("problems.unsavedPackageTitle")}</h3>
              <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("problems.unsavedPackageCopy")}</p>
            </div>
          )
        ) : null}
      </div>
    </SidePanel>
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

function PreviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-[var(--oj-ink-muted)]">{label}</dt>
      <dd className="mt-1 text-sm font-medium tabular-nums text-[var(--oj-ink)]">{value}</dd>
    </div>
  );
}

function parseTags(value: string) {
  return Array.from(new Set(value.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean)));
}

function normalizeDifficulty(value: string): Difficulty {
  return DIFFICULTIES.includes(value as Difficulty) ? (value as Difficulty) : "EASY";
}

function emptyStandardSolutions(): StandardSolutionMap {
  return { cpp: "", python: "", java: "" };
}

function defaultTimeLimitMultipliers(): TimeLimitMultiplierMap {
  return { cpp: 1, python: 1, java: 1 };
}

function normalizeTimeLimitMultipliers(value?: ProblemLanguageTimeLimitMultipliers | null): TimeLimitMultiplierMap {
  return {
    cpp: normalizeMultiplierValue(value?.cpp),
    python: normalizeMultiplierValue(value?.python),
    java: normalizeMultiplierValue(value?.java)
  };
}

function normalizeMultiplierValue(value?: number | null) {
  return typeof value === "number" && Number.isFinite(value) ? value : 1;
}

function isValidMultiplier(value: number) {
  return Number.isFinite(value) && value >= 1 && value <= 10;
}

function effectiveTimeLimitMillis(base: number, multiplier: number) {
  return Math.ceil(Math.max(0, Number(base) || 0) * normalizeMultiplierValue(multiplier));
}

function normalizeStandardSolutionLanguage(value?: string | null): StandardSolutionLanguage | null {
  const normalized = (value || "").trim().toLowerCase();
  if (normalized === "cpp" || normalized === "c++" || normalized === "cpp17" || normalized === "c++17") return "cpp";
  if (normalized === "python" || normalized === "python3" || normalized === "py") return "python";
  if (normalized === "java") return "java";
  return null;
}

function languageLabel(language: StandardSolutionLanguage, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`problems.languages.${language}`);
}

function emptySample(): TestCaseDto {
  return { input: "", expectedOutput: "", sample: true };
}
