import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, Info, Loader2, Play, Save, SlidersHorizontal, XCircle } from "lucide-react";
import {
  api,
  type AiModelConfigPayload,
  type AiModelConfigResponse,
  type AiModelConfigScope,
  type AiModelConfigTestResponse,
  type AiModelListResponse,
  type AiModelOption
} from "@aioj/api-client";
import { Badge, Button } from "@aioj/ui-react";
import { ErrorPanel, Field, LoadingPanel, PageHeader, inputClass, selectClass, textareaClass } from "../components/Common";
import { useI18n } from "../lib/i18n";
import { useToast } from "../lib/toast";
import { readableCaughtError, readableStoredError } from "../lib/readableError";

type FormState = {
  enabled: boolean;
  provider: string;
  baseUrl: string;
  model: string;
  jsonOutputEnabled: boolean;
  thinkingEnabled: boolean;
  reasoningEffort: "high" | "max";
  temperature: string;
  maxTokens: string;
  embeddingDimension: string;
  prompt: string;
};

type ModelCapability = Pick<AiModelOption, "supportsJsonOutput" | "supportsThinking" | "thinkingEffortModes" | "fixedTemperature" | "recommendedTemperature" | "contextLength" | "deprecated"> & {
  providerKind: "deepseek" | "kimi" | "embedding" | "custom";
};

const SCOPES: AiModelConfigScope[] = ["TEXT_GENERATION", "MEMORY_EXTRACTION", "REPORT_ANALYSIS", "PROBLEM_DRAFT", "ACCOUNT_IMPORT_PARSE", "INTENT", "AGENT_CURATOR", "EMBEDDING"];

export function AiModelConfigsView() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [forms, setForms] = React.useState<Record<AiModelConfigScope, FormState>>(() => Object.fromEntries(
    SCOPES.map((scope) => [scope, emptyForm(scope)])
  ) as Record<AiModelConfigScope, FormState>);
  const [testResults, setTestResults] = React.useState<Partial<Record<AiModelConfigScope, AiModelConfigTestResponse>>>({});

  const configsQuery = useQuery({
    queryKey: ["admin-ai-model-configs"],
    queryFn: () => api.aiModelConfigs()
  });

  React.useEffect(() => {
    if (!configsQuery.data) return;
    setForms(Object.fromEntries(
      SCOPES.map((scope) => {
        const config = configsQuery.data.find((item) => item.scope === scope);
        return [scope, config ? formFromConfig(config) : emptyForm(scope)];
      })
    ) as Record<AiModelConfigScope, FormState>);
  }, [configsQuery.data]);

  const saveMutation = useMutation({
    mutationFn: ({ scope, payload }: { scope: AiModelConfigScope; payload: AiModelConfigPayload }) => api.updateAiModelConfig(scope, payload),
    onSuccess: async (_data, variables) => {
      toast.success(t("aiModelConfig.saveSuccess", { scope: scopeLabel(variables.scope, t) }));
      await queryClient.invalidateQueries({ queryKey: ["admin-ai-model-configs"] });
      await queryClient.invalidateQueries({ queryKey: ["admin-ai-models"] });
    },
    onError: (caught) => {
      toast.error(readableCaughtError(caught, locale, t("common.errorFallback")));
    }
  });

  const testMutation = useMutation({
    mutationFn: ({ scope, payload }: { scope: AiModelConfigScope; payload: AiModelConfigPayload & { prompt?: string } }) =>
      api.testAiModelConfig(scope, payload),
    onSuccess: (result, variables) => {
      setTestResults((current) => ({ ...current, [variables.scope]: result }));
    },
    onError: (caught, variables) => {
      setTestResults((current) => ({
        ...current,
        [variables.scope]: {
          success: false,
          provider: forms[variables.scope].provider,
          model: forms[variables.scope].model,
          latencyMillis: 0,
          promptTokens: 0,
          completionTokens: 0,
          contentPreview: null,
          errorMessage: readableCaughtError(caught, locale, t("common.errorFallback"))
        }
      }));
    }
  });

  function updateForm(scope: AiModelConfigScope, patch: Partial<FormState>) {
    setForms((current) => ({ ...current, [scope]: { ...current[scope], ...patch } }));
  }

  function save(scope: AiModelConfigScope) {
    if (!scopeDirty(scope)) return;
    saveMutation.mutate({ scope, payload: payloadFromForm(scope, forms[scope]) });
  }

  function test(scope: AiModelConfigScope) {
    const form = forms[scope];
    testMutation.mutate({
      scope,
      payload: {
        ...payloadFromForm(scope, form),
        prompt: form.prompt.trim() || undefined
      }
    });
  }

  const configs = configsQuery.data ?? [];

  function scopeBaseline(scope: AiModelConfigScope): string {
    const config = configs.find((item) => item.scope === scope);
    return JSON.stringify({ ...(config ? formFromConfig(config) : emptyForm(scope)), prompt: "" });
  }

  function scopeDirty(scope: AiModelConfigScope): boolean {
    if (!configsQuery.data) return false;
    return JSON.stringify({ ...forms[scope], prompt: "" }) !== scopeBaseline(scope);
  }

  return (
    <div className="mx-auto flex max-w-[1540px] flex-col gap-6 px-4 py-5 md:px-8">
      <PageHeader
        eyebrow={t("common.adminConsole")}
        title={t("aiModelConfig.title")}
        description={t("aiModelConfig.subtitle")}
        actions={(
          <Button variant="outline" disabled={configsQuery.isFetching} onClick={() => void configsQuery.refetch()}>
            <SlidersHorizontal className="size-4" aria-hidden="true" />
            {t("common.refresh")}
          </Button>
        )}
      />

      {configsQuery.isLoading ? (
        <LoadingPanel label={t("aiModelConfig.loading")} />
      ) : configsQuery.isError ? (
        <ErrorPanel title={t("aiModelConfig.loadFailed")} action={<Button variant="outline" onClick={() => void configsQuery.refetch()}>{t("common.refresh")}</Button>} />
      ) : (
        <div className="space-y-4">
          {SCOPES.map((scope) => (
            <ConfigSection
              key={scope}
              scope={scope}
              config={configs.find((item) => item.scope === scope)}
              form={forms[scope]}
              dirty={scopeDirty(scope)}
              saving={saveMutation.isPending && saveMutation.variables?.scope === scope}
              testing={testMutation.isPending && testMutation.variables?.scope === scope}
              testResult={testResults[scope]}
              onChange={(patch) => updateForm(scope, patch)}
              onSave={() => save(scope)}
              onTest={() => test(scope)}
              t={t}
              locale={locale}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ConfigSection({
  scope,
  config,
  form,
  dirty,
  saving,
  testing,
  testResult,
  onChange,
  onSave,
  onTest,
  t,
  locale
}: {
  scope: AiModelConfigScope;
  config?: AiModelConfigResponse;
  form: FormState;
  dirty: boolean;
  saving: boolean;
  testing: boolean;
  testResult?: AiModelConfigTestResponse;
  onChange: (patch: Partial<FormState>) => void;
  onSave: () => void;
  onTest: () => void;
  t: (key: string, params?: Record<string, string | number>, fallback?: string) => string;
  locale: "zh-CN" | "en-US";
}) {
  const isText = scope === "TEXT_GENERATION";
  const isEmbedding = scope === "EMBEDDING";
  const inheritsText = !isText && !isEmbedding && !form.enabled;
  const providerKind = providerKindFor(form.provider, form.model, form.baseUrl, isEmbedding);
  const effectiveProviderKind = inheritsText && config
    ? providerKindFor(config.provider, config.model, config.baseUrl, false)
    : providerKind;
  const modelQuery = useQuery({
    queryKey: ["admin-ai-models", scope, form.provider.trim(), form.baseUrl.trim()],
    queryFn: () => api.aiModelConfigModels(scope, form.provider.trim(), form.baseUrl.trim()),
    enabled: !inheritsText && Boolean(form.provider.trim() && form.baseUrl.trim()),
    staleTime: 30_000,
    retry: false
  });
  const modelList = modelQuery.data;
  const selectedOption = modelList?.models.find((item) => sameText(item.id, form.model));
  const capability = capabilityFor(scope, form, selectedOption);
  const showModelSelect = modelList?.fetchStatus === "SUCCESS" && modelList.models.length > 0;
  const showModelManual = !inheritsText && !showModelSelect;
  const showJsonOutput = !isEmbedding && capability.supportsJsonOutput;
  const showThinking = !isEmbedding && capability.supportsThinking;
  const showEffort = showThinking && form.thinkingEnabled && capability.thinkingEffortModes.length > 0;
  const showTemperature = !isEmbedding && !temperatureControlledByProvider(capability, form.thinkingEnabled);
  const providerOptions = isEmbedding ? embeddingProviderOptions(t) : textProviderOptions(t);
  const currentApiKeyEnvName = isEmbedding ? (modelList?.apiKeyEnvName || config?.apiKeyEnvName || "") : apiKeyEnvNameFor(effectiveProviderKind);
  const currentApiKeyConfigured = inheritsText ? Boolean(config?.apiKeyConfigured) : apiKeyConfiguredForCurrentForm(config, modelList, currentApiKeyEnvName);

  React.useEffect(() => {
    const firstModel = modelList?.models[0]?.id;
    if (!form.model && showModelSelect && firstModel) {
      onChange({ model: firstModel });
    }
  }, [form.model, modelList, onChange, showModelSelect]);

  return (
    <section className="rounded-xl border border-[var(--oj-border)] bg-white p-4">
      <div className="flex flex-col gap-3 border-b border-[var(--oj-border-soft)] pb-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-base font-semibold text-[var(--oj-ink)]">{scopeLabel(scope, t)}</h2>
            <Badge tone={currentApiKeyConfigured ? "green" : "amber"}>{currentApiKeyConfigured ? t("aiModelConfig.envConfigured") : t("aiModelConfig.envMissing")}</Badge>
            {currentApiKeyEnvName ? (
              <Badge tone={currentApiKeyConfigured ? "neutral" : "amber"}>{currentApiKeyEnvName}</Badge>
            ) : null}
            <Badge tone={config?.inherited ? "blue" : config?.source === "DATABASE" ? "green" : "neutral"}>
              {sourceLabel(config?.source, t)}
            </Badge>
          </div>
          <p className="mt-1 max-w-[86ch] text-sm leading-6 text-[var(--oj-ink-muted)]">{scopeDescription(scope, t)}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" disabled={testing} onClick={onTest}>
            {testing ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Play className="size-4" aria-hidden="true" />}
            {t("aiModelConfig.test")}
          </Button>
          <Button disabled={saving || !dirty} onClick={onSave}>
            {saving ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Save className="size-4" aria-hidden="true" />}
            {t("common.save")}
          </Button>
        </div>
      </div>

      <div className="grid gap-4 py-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(320px,0.8fr)]">
        <div className="grid gap-4 md:grid-cols-2">
          {!isText ? (
            <label className="flex min-h-10 items-center gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 text-sm text-[var(--oj-ink)] md:col-span-2">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(event) => onChange({ enabled: event.target.checked })}
              />
              {t("aiModelConfig.enableOverride")}
            </label>
          ) : null}
          <Field label={isEmbedding ? t("aiModelConfig.provider") : t("aiModelConfig.api")}>
            <select
              className={selectClass}
              value={isEmbedding ? (sameText(form.provider, "dashscope") ? "dashscope" : "custom") : providerOptionValue(providerKind)}
              disabled={inheritsText}
              onChange={(event) => onChange(providerPatch(scope, event.target.value))}
            >
              {providerOptions.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </Field>
          <Field label={t("aiModelConfig.baseUrl")}>
            <input className={inputClass} value={form.baseUrl} disabled={inheritsText} onChange={(event) => onChange({ baseUrl: event.target.value })} placeholder={isEmbedding ? "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings" : "https://api.deepseek.com/chat/completions"} />
          </Field>
          <Field label={t("aiModelConfig.model")} hint={inheritsText ? t("aiModelConfig.inheritedTestHint") : modelHint(modelQuery.isFetching, modelList, t)}>
            {showModelSelect ? (
              <select
                className={selectClass}
                value={form.model}
                disabled={inheritsText}
                onChange={(event) => onChange(modelPatch(scope, form, modelList, event.target.value))}
              >
                {modelList.models.map((model) => (
                  <option key={model.id} value={model.id}>
                    {model.id}{model.contextLength ? ` / ${model.contextLength}` : ""}{model.deprecated ? ` / ${t("aiModelConfig.deprecated")}` : ""}
                  </option>
                ))}
              </select>
            ) : (
              <input className={inputClass} value={form.model} disabled={inheritsText} onChange={(event) => onChange(modelPatch(scope, form, modelList, event.target.value))} placeholder={isEmbedding ? "text-embedding-v3" : "deepseek-v4-pro"} />
            )}
          </Field>
          {inheritsText ? (
            <div className="flex items-center rounded-lg border border-dashed border-[var(--oj-border)] px-3 py-2 text-xs leading-5 text-[var(--oj-ink-muted)]">
              {t("aiModelConfig.inheritedTestHint")}
            </div>
          ) : null}
          {showModelManual ? (
            <div className="flex items-center rounded-lg border border-dashed border-[var(--oj-border)] px-3 py-2 text-xs leading-5 text-[var(--oj-ink-muted)]">
              {modelFallbackMessage(modelQuery.isFetching, modelList, t, locale)}
            </div>
          ) : null}
          {showJsonOutput ? (
            <label className="flex min-h-10 items-center gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-white px-3 text-sm text-[var(--oj-ink)]">
              <input type="checkbox" checked={form.jsonOutputEnabled} disabled={inheritsText} onChange={(event) => onChange({ jsonOutputEnabled: event.target.checked })} />
              {t("aiModelConfig.jsonOutput")}
            </label>
          ) : null}
          {showThinking ? (
            <label className="flex min-h-10 items-center gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-white px-3 text-sm text-[var(--oj-ink)]">
              <input type="checkbox" checked={form.thinkingEnabled} disabled={inheritsText} onChange={(event) => onChange({ thinkingEnabled: event.target.checked })} />
              {capability.providerKind === "deepseek" ? t("aiModelConfig.deepSeekThinking") : t("aiModelConfig.kimiThinking")}
            </label>
          ) : null}
          {showEffort ? (
            <Field label={t("aiModelConfig.reasoningEffort")}>
              <select className={selectClass} value={form.reasoningEffort} disabled={inheritsText} onChange={(event) => onChange({ reasoningEffort: event.target.value as "high" | "max" })}>
                {capability.thinkingEffortModes.map((mode) => (
                  <option key={mode} value={mode}>{mode}</option>
                ))}
              </select>
            </Field>
          ) : null}
          {showTemperature ? (
            <Field label={t("aiModelConfig.temperature")}>
              <input className={inputClass} value={form.temperature} disabled={inheritsText} onChange={(event) => onChange({ temperature: event.target.value })} placeholder={t("aiModelConfig.inheritDefault")} inputMode="decimal" />
            </Field>
          ) : !isEmbedding ? (
            <div className="flex items-center rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-xs leading-5 text-[var(--oj-ink-muted)]">
              {temperatureHint(capability, form.thinkingEnabled, t)}
            </div>
          ) : null}
          {!isEmbedding ? (
            <Field label={t("aiModelConfig.maxTokens")}>
              <input className={inputClass} value={form.maxTokens} disabled={inheritsText} onChange={(event) => onChange({ maxTokens: event.target.value })} placeholder={t("aiModelConfig.inheritDefault")} inputMode="numeric" />
            </Field>
          ) : null}
          {isEmbedding ? (
            <Field label={t("aiModelConfig.embeddingDimension")}>
              <input className={inputClass} value={form.embeddingDimension} onChange={(event) => onChange({ embeddingDimension: event.target.value })} placeholder="1024" inputMode="numeric" />
            </Field>
          ) : null}
        </div>

        <div className="space-y-3">
          <Field label={t("aiModelConfig.testPrompt")}>
            <textarea className={`${textareaClass} min-h-28`} value={form.prompt} onChange={(event) => onChange({ prompt: event.target.value })} placeholder={t("aiModelConfig.testPromptPlaceholder")} />
          </Field>
          {testResult ? <TestResult result={testResult} t={t} locale={locale} /> : (
            <div className="rounded-lg border border-dashed border-[var(--oj-border)] p-3 text-sm leading-6 text-[var(--oj-ink-muted)]">
              {t("aiModelConfig.noTestYet")}
            </div>
          )}
          <div className="flex items-start gap-2 rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink-muted)]">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <span>{inheritsText ? t("aiModelConfig.inheritedEnvHint") : envHint(scope, providerKind, t)}</span>
          </div>
        </div>
      </div>
    </section>
  );
}

function TestResult({ result, t, locale }: { result: AiModelConfigTestResponse; t: (key: string, params?: Record<string, string | number>, fallback?: string) => string; locale: "zh-CN" | "en-US" }) {
  return (
    <div className={`rounded-lg border p-3 text-sm ${result.success ? "border-emerald-200 bg-emerald-50 text-emerald-900" : "border-red-200 bg-red-50 text-red-950"}`}>
      <div className="flex items-center gap-2 font-medium">
        {result.success ? <CheckCircle2 className="size-4" aria-hidden="true" /> : <XCircle className="size-4" aria-hidden="true" />}
        {result.success ? t("aiModelConfig.testSuccess") : t("aiModelConfig.testFailed")}
      </div>
      <div className="mt-2 grid gap-1 text-xs tabular-nums">
        <span>{result.provider} / {result.model}</span>
        <span>{t("aiModelConfig.latency", { value: result.latencyMillis })}</span>
        <span>{t("aiModelConfig.usage", { prompt: result.promptTokens, completion: result.completionTokens })}</span>
      </div>
      {result.contentPreview ? <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-white/70 p-2 text-xs">{result.contentPreview}</pre> : null}
      {result.errorMessage ? <p className="mt-2 text-xs leading-5">{readableStoredError(result.errorMessage, locale, result.errorMessage, "ai")}</p> : null}
    </div>
  );
}

function emptyForm(scope: AiModelConfigScope): FormState {
  return {
    enabled: scope === "TEXT_GENERATION",
    provider: scope === "EMBEDDING" ? "dashscope" : "deepseek",
    baseUrl: scope === "EMBEDDING" ? "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings" : "https://api.deepseek.com/chat/completions",
    model: scope === "EMBEDDING" ? "text-embedding-v3" : "deepseek-v4-pro",
    jsonOutputEnabled: scope !== "EMBEDDING",
    thinkingEnabled: false,
    reasoningEffort: "high",
    temperature: "",
    maxTokens: "",
    embeddingDimension: scope === "EMBEDDING" ? "1024" : "",
    prompt: ""
  };
}

function formFromConfig(config: AiModelConfigResponse): FormState {
  const provider = config.scope === "EMBEDDING" ? config.provider || "" : canonicalTextApi(config.provider, config.model, config.baseUrl);
  const baseUrl = config.scope === "EMBEDDING" ? config.baseUrl || "" : normalizedTextBaseUrl(provider, config.baseUrl || "");
  return {
    ...emptyForm(config.scope),
    enabled: config.scope === "TEXT_GENERATION" ? true : !config.inherited && config.source === "DATABASE",
    provider,
    baseUrl,
    model: config.model || "",
    jsonOutputEnabled: config.jsonOutputEnabled,
    thinkingEnabled: config.thinkingEnabled,
    reasoningEffort: config.reasoningEffort === "max" ? "max" : "high",
    temperature: config.temperature == null ? "" : String(config.temperature),
    maxTokens: config.maxTokens == null ? "" : String(config.maxTokens),
    embeddingDimension: config.embeddingDimension == null ? "" : String(config.embeddingDimension),
    prompt: ""
  };
}

function payloadFromForm(scope: AiModelConfigScope, form: FormState): AiModelConfigPayload {
  const capability = capabilityFor(scope, form);
  return {
    enabled: scope === "TEXT_GENERATION" ? true : form.enabled,
    provider: scope === "EMBEDDING" ? form.provider.trim() : canonicalTextApi(form.provider, form.model, form.baseUrl),
    baseUrl: form.baseUrl.trim(),
    model: form.model.trim(),
    apiKeyAction: "CLEAR",
    jsonOutputEnabled: scope !== "EMBEDDING" && capability.supportsJsonOutput ? form.jsonOutputEnabled : false,
    thinkingEnabled: scope !== "EMBEDDING" && capability.supportsThinking ? form.thinkingEnabled : false,
    reasoningEffort: capability.supportsThinking && capability.thinkingEffortModes.length > 0 ? form.reasoningEffort : "high",
    temperature: scope !== "EMBEDDING" && !temperatureControlledByProvider(capability, form.thinkingEnabled) ? numberOrNull(form.temperature) : null,
    maxTokens: scope !== "EMBEDDING" ? integerOrNull(form.maxTokens) : undefined,
    embeddingDimension: scope === "EMBEDDING" ? integerOrNull(form.embeddingDimension) : undefined
  };
}

function providerPatch(scope: AiModelConfigScope, value: string): Partial<FormState> {
  if (scope === "EMBEDDING") {
    if (value === "dashscope") {
      return {
        provider: "dashscope",
        baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings",
        model: "text-embedding-v3",
        jsonOutputEnabled: false,
        thinkingEnabled: false,
        reasoningEffort: "high"
      };
    }
    return { provider: "custom", baseUrl: "", model: "", jsonOutputEnabled: false, thinkingEnabled: false, reasoningEffort: "high" };
  }
  if (value === "deepseek") {
    return {
      provider: "deepseek",
      baseUrl: "https://api.deepseek.com/chat/completions",
      model: "deepseek-v4-pro",
      jsonOutputEnabled: true,
      thinkingEnabled: false,
      reasoningEffort: "high"
    };
  }
  if (value === "kimi") {
    return {
      provider: "kimi",
      baseUrl: "https://api.moonshot.cn/v1/chat/completions",
      model: "kimi-k2.6",
      jsonOutputEnabled: true,
      thinkingEnabled: false,
      reasoningEffort: "high"
    };
  }
  return providerPatch(scope, "deepseek");
}

function modelPatch(scope: AiModelConfigScope, form: FormState, list: AiModelListResponse | undefined, modelId: string): Partial<FormState> {
  const nextForm = { ...form, model: modelId };
  const capability = capabilityFor(scope, nextForm, list?.models.find((item) => sameText(item.id, modelId)));
  return {
    model: modelId,
    jsonOutputEnabled: scope !== "EMBEDDING" && capability.supportsJsonOutput ? form.jsonOutputEnabled : false,
    thinkingEnabled: scope !== "EMBEDDING" && capability.supportsThinking ? form.thinkingEnabled : false,
    reasoningEffort: capability.thinkingEffortModes.includes(form.reasoningEffort) ? form.reasoningEffort : "high",
    temperature: temperatureControlledByProvider(capability, form.thinkingEnabled) ? "" : form.temperature
  };
}

function capabilityFor(scope: AiModelConfigScope, form: FormState, option?: AiModelOption): ModelCapability {
  const providerKind = providerKindFor(form.provider, form.model, form.baseUrl, scope === "EMBEDDING");
  if (scope === "EMBEDDING") {
    return emptyCapability(providerKind);
  }
  if (option) {
    return {
      providerKind,
      supportsJsonOutput: option.supportsJsonOutput,
      supportsThinking: option.supportsThinking,
      thinkingEffortModes: option.thinkingEffortModes ?? [],
      fixedTemperature: option.fixedTemperature,
      recommendedTemperature: option.recommendedTemperature,
      contextLength: option.contextLength,
      deprecated: option.deprecated
    };
  }
  if (providerKind === "deepseek") {
    const model = form.model.trim().toLowerCase();
    const supportsThinking = model.startsWith("deepseek-v4-") || model === "deepseek-chat" || model === "deepseek-reasoner";
    return {
      providerKind,
      supportsJsonOutput: true,
      supportsThinking,
      thinkingEffortModes: supportsThinking ? ["high", "max"] : [],
      fixedTemperature: null,
      recommendedTemperature: null,
      contextLength: null,
      deprecated: model === "deepseek-chat" || model === "deepseek-reasoner"
    };
  }
  if (providerKind === "kimi") {
    const model = form.model.trim().toLowerCase();
    const supportsThinking = model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6");
    return {
      providerKind: "kimi",
      supportsJsonOutput: true,
      supportsThinking,
      thinkingEffortModes: [],
      fixedTemperature: null,
      recommendedTemperature: supportsThinking ? 0.6 : null,
      contextLength: null,
      deprecated: false
    };
  }
  return emptyCapability("custom");
}

function emptyCapability(providerKind: ModelCapability["providerKind"]): ModelCapability {
  return {
    providerKind,
    supportsJsonOutput: false,
    supportsThinking: false,
    thinkingEffortModes: [],
    fixedTemperature: null,
    recommendedTemperature: null,
    contextLength: null,
    deprecated: false
  };
}

function temperatureControlledByProvider(capability: ModelCapability, thinkingEnabled: boolean) {
  return (capability.providerKind === "deepseek" && capability.supportsThinking && thinkingEnabled)
    || (capability.providerKind === "kimi" && capability.supportsThinking);
}

function temperatureHint(capability: ModelCapability, thinkingEnabled: boolean, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  if (capability.providerKind === "deepseek") {
    return t("aiModelConfig.temperatureDeepSeekThinking");
  }
  if (capability.providerKind === "kimi") {
    return t("aiModelConfig.temperatureKimiThinking", { value: thinkingEnabled ? "1.0" : "0.6" });
  }
  return t("aiModelConfig.temperatureProviderControlled");
}

function providerKindFor(provider: string, model: string, baseUrl: string, embedding: boolean): ModelCapability["providerKind"] {
  if (embedding) {
    return provider.toLowerCase().includes("dashscope") ? "embedding" : "custom";
  }
  const normalizedProvider = provider.trim().toLowerCase();
  if (normalizedProvider === "deepseek") {
    return "deepseek";
  }
  if (normalizedProvider === "kimi" || normalizedProvider === "moonshot") {
    return "kimi";
  }
  const combined = `${provider} ${model} ${baseUrl}`.toLowerCase();
  if (combined.includes("deepseek")) {
    return "deepseek";
  }
  if (model.toLowerCase().startsWith("kimi-") || combined.includes("kimi")) {
    return "kimi";
  }
  if (combined.includes("moonshot")) {
    return "kimi";
  }
  return "custom";
}

function providerOptionValue(providerKind: ModelCapability["providerKind"]) {
  if (providerKind === "deepseek") return "deepseek";
  if (providerKind === "kimi") return "kimi";
  return "deepseek";
}

function textProviderOptions(t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return [
    { value: "deepseek", label: t("aiModelConfig.providerDeepSeek") },
    { value: "kimi", label: t("aiModelConfig.providerKimi") }
  ];
}

function embeddingProviderOptions(t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return [
    { value: "dashscope", label: t("aiModelConfig.providerDashScope") },
    { value: "custom", label: t("aiModelConfig.providerCustom") }
  ];
}

function modelHint(
  fetching: boolean,
  list: AiModelListResponse | undefined,
  t: (key: string, params?: Record<string, string | number>, fallback?: string) => string
) {
  if (fetching) {
    return t("aiModelConfig.modelLoading");
  }
  if (list?.fetchStatus === "SUCCESS") {
    return t("aiModelConfig.modelLoaded", { count: list.models.length });
  }
  return t("aiModelConfig.modelManualHint");
}

function modelFallbackMessage(
  fetching: boolean,
  list: AiModelListResponse | undefined,
  t: (key: string, params?: Record<string, string | number>, fallback?: string) => string,
  locale: "zh-CN" | "en-US"
) {
  if (fetching) {
    return t("aiModelConfig.modelLoading");
  }
  if (!list) {
    return t("aiModelConfig.modelManualHint");
  }
  if (list.fetchStatus === "MISSING_KEY") {
    return t("aiModelConfig.modelMissingKey");
  }
  if (list.fetchStatus === "UNSUPPORTED") {
    return t("aiModelConfig.modelUnsupported");
  }
  return readableStoredError(list.errorMessage, locale, t("aiModelConfig.modelFetchFailed"), "ai");
}

function numberOrNull(value: string) {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : null;
}

function integerOrNull(value: string) {
  const trimmed = value.trim();
  return trimmed ? Number.parseInt(trimmed, 10) : null;
}

function sameText(left: string, right: string) {
  return left.trim().toLowerCase() === right.trim().toLowerCase();
}

function apiKeyEnvNameFor(providerKind: ModelCapability["providerKind"]) {
  if (providerKind === "kimi") return "KIMI_API_KEY";
  if (providerKind === "deepseek") return "DEEPSEEK_API_KEY";
  return "";
}

function apiKeyConfiguredForCurrentForm(
  config: AiModelConfigResponse | undefined,
  modelList: AiModelListResponse | undefined,
  envName: string
) {
  if (!envName) {
    return Boolean(config?.apiKeyConfigured);
  }
  if (modelList?.apiKeyEnvName === envName) {
    return modelList.apiKeyConfigured;
  }
  return config?.apiKeyEnvName === envName ? config.apiKeyConfigured : false;
}

function canonicalTextApi(provider: string, model: string, baseUrl: string) {
  const normalizedProvider = provider.trim().toLowerCase();
  if (normalizedProvider === "kimi" || normalizedProvider === "moonshot") return "kimi";
  if (normalizedProvider === "deepseek") return "deepseek";
  return providerKindFor(provider, model, baseUrl, false) === "kimi" ? "kimi" : "deepseek";
}

function normalizedTextBaseUrl(provider: string, baseUrl: string) {
  if (provider === "kimi" && (!baseUrl.trim() || baseUrl.toLowerCase().includes("api.moonshot.ai"))) {
    return "https://api.moonshot.cn/v1/chat/completions";
  }
  return baseUrl;
}

function scopeLabel(scope: AiModelConfigScope, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`aiModelConfig.scope.${scope}`);
}

function scopeDescription(scope: AiModelConfigScope, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`aiModelConfig.scopeDescription.${scope}`);
}

function sourceLabel(source: string | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`aiModelConfig.source.${source || "NONE"}`, undefined, source || "NONE");
}

function envHint(scope: AiModelConfigScope, providerKind: ModelCapability["providerKind"], t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return t(`aiModelConfig.envHint.${scope}.${providerKind}`, undefined, t(`aiModelConfig.envHint.${scope}.default`));
}
