import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CheckSquare,
  MessageSquarePlus,
  Pencil,
  Search,
  SendHorizonal,
  Square,
  Trash2,
  X
} from "lucide-react";
import { activeQueryRefetchInterval, api, ApiError, streamAi, type AiAssistantMessageEvent, type AiChatMessageResponse, type AiClarification, type AiClarificationAnswerPayload, type AiClarificationOption, type AiContestContextPayload, type AiContextBuildReport, type AiConversationResponse, type AiProblemContextSummary, type AiRenderHints, type AiSelectionContextPayload, type AiStreamContextEvent, type AiStreamDoneEvent, type AiSubmissionContextPayload, type AiSubmissionContextSummary, type EntityId, type ProblemResponse } from "@aioj/api-client";
import { Badge, Button, cn, shouldToggleRowSelection } from "@aioj/ui-react";
import { ConfirmDialog, ErrorPanel, LoadingPanel, textareaClass } from "./Common";
import { useI18n } from "../lib/i18n";
import { difficultyTone, formatDateTime } from "../lib/format";
import { AssistantMessageRenderer } from "./ai/AssistantMessageRenderer";
import { SelectionAskToolbar } from "./ai/SelectionAskToolbar";
import { SelectedContextChip } from "./ai/SelectedContextChip";
import { mergeChatMessages } from "../lib/chatMessageMerge";
import { withSelectionIntent, type BuiltSelectionContext } from "../lib/selectionContextBuilder";
import { useAiSelectionContext } from "../hooks/useAiSelectionContext";
import { readableStoredError } from "../lib/readableError";

type AiSource = "ai_tutor" | "problem_detail" | "submission_analysis";

interface LocalMessage extends AiChatMessageResponse {
  local?: boolean;
  status?: "sending" | "success" | "error";
  streamId?: string;
  parseWarnings?: string[];
  renderHints?: AiRenderHints;
  problemContext?: AiProblemContextSummary;
  submissionContext?: AiSubmissionContextSummary;
}

interface PendingDelete {
  ids: string[];
  title: string;
  description: string;
}

type ContextDebugSection = AiContextBuildReport["sections"][number];

interface RetrievalHitDebug {
  ownerType?: string;
  ownerId?: string;
  score?: number;
  reasons?: string[];
  preview?: string;
}

export interface AiTutorWorkspaceProps {
  source?: AiSource;
  problem?: ProblemResponse;
  problemId?: EntityId;
  problemTitle?: string | null;
  code?: string;
  language?: string;
  compact?: boolean;
  lockedProblem?: boolean;
  contestContext?: AiContestContextPayload | null;
  submissionContext?: AiSubmissionContextPayload | null;
  sourceRefType?: string;
  sourceRefId?: string;
  initialPrompt?: string;
}

const conversationTitleLimit = 42;
const hiddenAssistMode = "assist" as const;

function localId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return `${prefix}_${crypto.randomUUID()}`;
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function compactPreview(value?: string | null) {
  return value?.replace(/\s+/g, " ").trim().slice(0, conversationTitleLimit);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function retrievalHits(section: ContextDebugSection): RetrievalHitDebug[] {
  const rawHits = section.metadata?.hits;
  if (!Array.isArray(rawHits)) return [];
  return rawHits.filter(isRecord).map((item) => ({
    ownerType: typeof item.ownerType === "string" ? item.ownerType : undefined,
    ownerId: typeof item.ownerId === "string" ? item.ownerId : undefined,
    score: typeof item.score === "number" ? item.score : undefined,
    reasons: Array.isArray(item.reasons) ? item.reasons.filter((reason): reason is string => typeof reason === "string") : [],
    preview: typeof item.preview === "string" ? item.preview : undefined
  }));
}

function submissionContextPayload(context?: AiSubmissionContextPayload | null): AiSubmissionContextPayload | undefined {
  if (!context?.submissionId) return undefined;
  const payload: AiSubmissionContextPayload = { submissionId: context.submissionId };
  if (context.intent) payload.intent = context.intent;
  if (typeof context.userSelected === "boolean") payload.userSelected = context.userSelected;
  if (context.note) payload.note = context.note;
  return payload;
}

function inferConversationMode(message: string) {
  const lower = message.toLowerCase();
  const verdictWord = /\b(wa|re|tle|ce|wrong[- ]?answer|runtime[- ]?error|time[- ]?limit|compile[- ]?error)\b/.test(lower);
  if ((message.includes("代码") || message.includes("实现") || lower.includes("code") || lower.includes("implementation") || lower.includes("solution"))
    && (message.includes("讲解") || message.includes("思路") || message.includes("解释") || lower.includes("explain") || lower.includes("walkthrough"))) {
    return "code_explain";
  }
  if (message.includes("为什么错") || message.includes("报错") || verdictWord || lower.includes("debug") || lower.includes("error")) {
    return "debug";
  }
  if (message.includes("边界") || message.includes("单调") || lower.includes("boundary") || lower.includes("edge case") || lower.includes("corner case") || lower.includes("monotonic")) {
    return "boundary";
  }
  if (message.includes("概念") || message.includes("原理") || message.includes("为什么") || lower.includes("concept") || lower.includes("principle") || lower.includes("how does")) {
    return "concept";
  }
  if (message.includes("提示") || message.includes("怎么入手") || lower.includes("hint") || lower.includes("how to start") || lower.includes("where to start") || lower.includes("approach")) {
    return "hint";
  }
  return "qa";
}

function parseClarification(data: string): AiClarification | null {
  try {
    const parsed = JSON.parse(data) as AiClarification;
    const legacyOptions = Array.isArray(parsed.options) ? parsed.options : [];
    const inputOptions = Array.isArray(parsed.input?.options) ? parsed.input.options : [];
    const options = legacyOptions.length ? legacyOptions : inputOptions;
    return {
      ...parsed,
      options,
      input: parsed.input ? { ...parsed.input, options } : parsed.input
    };
  } catch {
    return null;
  }
}

interface ParsedContextEvent {
  debugPreview: string;
  problemContext?: AiProblemContextSummary;
  submissionContext?: AiSubmissionContextSummary;
  renderHints?: AiRenderHints;
  contextBuildReport?: AiContextBuildReport;
}

function toLocalMessageStatus(message: AiChatMessageResponse): LocalMessage["status"] {
  if (message.status === "RUNNING") return "sending";
  if (message.status === "FAILED") return "error";
  return "success";
}

function visibleServerMessageContent(message: AiChatMessageResponse, failedFallback: string, locale: "zh-CN" | "en-US") {
  if (message.status === "FAILED") {
    // Blocked or refused turns store the actual explanation in content; only fall back to
    // errorMessage when content is empty.
    if (message.content?.trim()) {
      return message.content;
    }
    return readableStoredError(message.errorMessage, locale, failedFallback, "ai");
  }
  return message.content;
}

function parseAssistantMessageEvent(data: string): AiAssistantMessageEvent {
  try {
    const parsed = JSON.parse(data) as Partial<AiAssistantMessageEvent> & { content?: string };
    if (parsed && typeof parsed === "object") {
      if (typeof parsed.contentMarkdown === "string") {
        return {
          messageId: parsed.messageId,
          assistantMessageId: parsed.assistantMessageId,
          userMessageId: parsed.userMessageId,
          conversationId: parsed.conversationId,
          clientMessageId: parsed.clientMessageId,
          requestClientMessageId: parsed.requestClientMessageId,
          contentMarkdown: parsed.contentMarkdown,
          parseWarnings: Array.isArray(parsed.parseWarnings) ? parsed.parseWarnings : [],
          renderHints: parsed.renderHints,
          problemContext: parsed.problemContext,
          submissionContext: parsed.submissionContext
        };
      }
      if (typeof parsed.content === "string") {
        return { contentMarkdown: parsed.content };
      }
    }
  } catch {
    // Legacy streaming sends plain text chunks.
  }
  return { contentMarkdown: data };
}

function parseDoneEvent(data: string): AiStreamDoneEvent | null {
  try {
    const parsed = JSON.parse(data) as AiStreamDoneEvent;
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

function parseContextDebugPreview(data: string): ParsedContextEvent {
  try {
    const parsed = JSON.parse(data) as AiStreamContextEvent;
    return {
      debugPreview: parsed.conversationContextPack || parsed.currentProblems || parsed.conversationSummary || data,
      problemContext: parsed.problemContext,
      submissionContext: parsed.submissionContext,
      renderHints: parsed.renderHints,
      contextBuildReport: parsed.contextBuildReport
    };
  } catch {
    return { debugPreview: data };
  }
}

function clarificationOptions(clarification: AiClarification) {
  return clarification.input?.options?.length ? clarification.input.options : clarification.options ?? [];
}

function clarificationKind(clarification: AiClarification) {
  if (clarification.input?.kind) return clarification.input.kind;
  const options = clarificationOptions(clarification);
  const hasOpenInput = options.some((option) => ["text", "textarea", "free_text", "code"].includes(option.type ?? ""));
  if (!options.length) return "free_text";
  return hasOpenInput ? "mixed" : "single_choice";
}

function clarificationOptionKey(option: AiClarificationOption, index: number) {
  return option.id || option.label?.trim() || `option:${index}`;
}

function optionAnswerText(option: AiClarificationOption) {
  return option.label?.trim() ?? "";
}

function selectedOptionId(option: AiClarificationOption, index: number) {
  return option.id || option.label?.trim() || `option:${index}`;
}

function hasClarificationCustomInput(clarification: AiClarification) {
  const kind = clarificationKind(clarification);
  return ["free_text", "code", "mixed", "number", "file"].includes(kind)
    || Boolean(clarification.input?.allowCustom)
    || clarificationOptions(clarification).some((option) => ["text", "textarea", "free_text", "code"].includes(option.type ?? ""));
}

function isClarificationCodeInput(clarification: AiClarification) {
  const kind = clarificationKind(clarification);
  return kind === "code" || clarification.input?.customKind === "code";
}

function buildClarificationAnswerPayload(
  clarification: AiClarification,
  selectedKeys: Set<string>,
  customText: string
): AiClarificationAnswerPayload | null {
  const options = clarificationOptions(clarification);
  const selectedOptions = options.filter((option, index) => selectedKeys.has(clarificationOptionKey(option, index)));
  const clean = customText.trim();
  const lines = [
    ...selectedOptions.map(optionAnswerText).filter(Boolean),
    clean
  ].filter(Boolean);
  const answerText = lines.join("\n").trim();
  if (!answerText) return null;
  return {
    requestId: clarification.id,
    question: clarification.prompt || clarification.title || "",
    answerText,
    selectedOptionIds: selectedOptions.map(selectedOptionId),
    customText: clean || undefined
  };
}

const CONVERSATION_PAGE_SIZE = 100;

export function AiTutorWorkspace({
  source = "ai_tutor",
  problem,
  problemId,
  problemTitle,
  code,
  language,
  compact = false,
  lockedProblem = false,
  contestContext = null,
  submissionContext = null,
  sourceRefType,
  sourceRefId,
  initialPrompt
}: AiTutorWorkspaceProps) {
  const { t, list, locale } = useI18n();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = React.useState("");
  const [conversationPage, setConversationPage] = React.useState(1);
  const [problemFilter, setProblemFilter] = React.useState<EntityId | "">(problem?.id ?? problemId ?? "");
  const [selectedId, setSelectedId] = React.useState<string | null>(null);
  const [selectedIds, setSelectedIds] = React.useState<Set<string>>(() => new Set());
  const [manageMode, setManageMode] = React.useState(false);
  const [draft, setDraft] = React.useState("");
  const [pinnedReference, setPinnedReference] = React.useState<BuiltSelectionContext | null>(null);
  const [localMessages, setLocalMessages] = React.useState<Record<string, LocalMessage[]>>({});
  const [streaming, setStreaming] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [clarification, setClarification] = React.useState<AiClarification | null>(null);
  const [clarificationText, setClarificationText] = React.useState("");
  const [selectedClarificationOptions, setSelectedClarificationOptions] = React.useState<Set<string>>(() => new Set());
  const [contextDebugPreview, setContextDebugPreview] = React.useState("");
  const [contextBuildReport, setContextBuildReport] = React.useState<AiContextBuildReport | null>(null);
  const [pendingDelete, setPendingDelete] = React.useState<PendingDelete | null>(null);
  const [renaming, setRenaming] = React.useState(false);
  const [renameValue, setRenameValue] = React.useState("");
  const [optimisticConversations, setOptimisticConversations] = React.useState<AiConversationResponse[]>([]);
  const [problemContextByConversationId, setProblemContextByConversationId] = React.useState<Record<string, AiProblemContextSummary>>({});
  const [renderHintsByConversationId, setRenderHintsByConversationId] = React.useState<Record<string, AiRenderHints>>({});
  const messageRootRef = React.useRef<HTMLDivElement | null>(null);
  const draftRef = React.useRef<HTMLTextAreaElement | null>(null);
  const pendingClientIdsRef = React.useRef<Set<string>>(new Set());
  const streamingRef = React.useRef(false);
  const streamAbortRef = React.useRef<AbortController | null>(null);
  const initialPromptKeyRef = React.useRef("");
  const sourceRefResolvedKeyRef = React.useRef("");

  const sourceRefActive = Boolean(sourceRefType && sourceRefId);
  const sourceRefKey = sourceRefActive ? `${source}:${sourceRefType}:${sourceRefId}` : "";
  const requestedProblemId = problem?.id ?? problemId ?? "";
  const conversationProblemId = (lockedProblem ? requestedProblemId : problemFilter) || undefined;

  React.useEffect(() => {
    const nextProblemId = problem?.id ?? problemId;
    if (nextProblemId) setProblemFilter(nextProblemId);
  }, [problem?.id, problemId]);

  React.useEffect(() => {
    if (!sourceRefActive) return;
    setSelectedId(null);
    setOptimisticConversations([]);
    sourceRefResolvedKeyRef.current = "";
  }, [sourceRefActive, sourceRefKey]);

  React.useEffect(() => {
    const key = submissionContext?.submissionId ? `${submissionContext.submissionId}:${initialPrompt ?? ""}` : "";
    if (!key || initialPromptKeyRef.current === key || draft.trim()) return;
    initialPromptKeyRef.current = key;
    setDraft(initialPrompt ?? defaultSubmissionPrompt(submissionContext?.intent, t));
    window.setTimeout(() => draftRef.current?.focus(), 0);
  }, [draft, initialPrompt, submissionContext?.intent, submissionContext?.submissionId, t]);

  const conversationsQuery = useQuery({
    queryKey: ["ai", "conversations", keyword, conversationProblemId, sourceRefKey, conversationPage],
    queryFn: () => api.aiConversations({
      page: conversationPage,
      pageSize: CONVERSATION_PAGE_SIZE,
      keyword: keyword.trim() || undefined,
      problemId: conversationProblemId
    })
  });

  const serverConversations = conversationsQuery.data?.records ?? [];
  const conversationsTotal = conversationsQuery.data?.total ?? 0;
  const conversationsMaxPage = Math.max(1, Math.ceil(conversationsTotal / CONVERSATION_PAGE_SIZE));
  const conversations = React.useMemo(() => {
    const serverIds = new Set(serverConversations.map((item) => item.conversationId));
    return [
      ...optimisticConversations.filter((item) => !serverIds.has(item.conversationId)),
      ...serverConversations
    ];
  }, [optimisticConversations, serverConversations]);

  React.useEffect(() => {
    if (sourceRefActive && sourceRefResolvedKeyRef.current !== sourceRefKey) return;
    if (!selectedId && conversations.length > 0) setSelectedId(conversations[0].conversationId);
    if (selectedId && conversations.length > 0 && !conversations.some((item) => item.conversationId === selectedId)) {
      setSelectedId(conversations[0].conversationId);
    }
  }, [conversations, selectedId, sourceRefActive, sourceRefKey]);

  const selectedConversation = conversations.find((item) => item.conversationId === selectedId) ?? null;

  React.useEffect(() => {
    setClarification(null);
    setClarificationText("");
    setSelectedClarificationOptions(new Set());
    setContextDebugPreview("");
    setContextBuildReport(null);
  }, [selectedId]);

  React.useEffect(() => {
    setRenameValue(selectedConversation?.title || t("aiAssistant.newConversationTitle"));
    setRenaming(false);
  }, [selectedConversation?.conversationId, selectedConversation?.title, t]);

  const problemsQuery = useQuery({
    queryKey: ["problems", "ai-filter"],
    queryFn: () => api.problems({ page: 1, pageSize: 100 }),
    enabled: !lockedProblem
  });

  const messagesQuery = useQuery({
    queryKey: ["ai", "messages", selectedId],
    queryFn: () => api.aiHistory(selectedId!),
    enabled: Boolean(selectedId),
    refetchInterval: (query) => activeQueryRefetchInterval(query, (data) => data?.some((message) => message.status === "RUNNING") ?? false)
  });

  const createConversation = useMutation({
    mutationFn: (payload: { message?: string; preserveDraft?: boolean }) => api.createAiConversation({
      problemId: requestedProblemId || problemFilter || undefined,
      title: compactPreview(payload.message) || t("aiAssistant.newConversationTitle"),
      source,
      sourceRefType: sourceRefActive ? sourceRefType : undefined,
      sourceRefId: sourceRefActive ? sourceRefId : undefined,
      mode: hiddenAssistMode
    }),
    onSuccess: async (conversation, variables) => {
      setKeyword("");
      setSelectedId(conversation.conversationId);
      if (!variables.preserveDraft) setDraft("");
      setError(null);
      setClarification(null);
      setClarificationText("");
      setSelectedClarificationOptions(new Set());
      setContextDebugPreview("");
      setContextBuildReport(null);
      setRenaming(false);
      setOptimisticConversations((current) => [
        conversation,
        ...current.filter((item) => item.conversationId !== conversation.conversationId)
      ]);
      await queryClient.invalidateQueries({ queryKey: ["ai", "conversations"] });
    }
  });

  React.useEffect(() => {
    if (!sourceRefActive || !sourceRefKey || !requestedProblemId) return;
    if (sourceRefResolvedKeyRef.current === sourceRefKey) return;
    if (createConversation.isPending || conversationsQuery.isLoading) return;
    sourceRefResolvedKeyRef.current = sourceRefKey;
    const titleSeed = initialPrompt || defaultSubmissionPrompt(submissionContext?.intent, t);
    void createConversation.mutateAsync({ message: titleSeed, preserveDraft: true }).catch(() => {
      sourceRefResolvedKeyRef.current = "";
    });
  }, [
    conversationsQuery.isLoading,
    createConversation,
    initialPrompt,
    requestedProblemId,
    sourceRefActive,
    sourceRefKey,
    submissionContext?.intent,
    t
  ]);

  const updateConversation = useMutation({
    mutationFn: (payload: { conversationId: string; title?: string; mode?: string }) =>
      api.updateAiConversation(payload.conversationId, { title: payload.title, mode: payload.mode }),
    onSuccess: async (conversation) => {
      setOptimisticConversations((current) => current.map((item) => (
        item.conversationId === conversation.conversationId ? conversation : item
      )));
      await queryClient.invalidateQueries({ queryKey: ["ai", "conversations"] });
    }
  });

  const deleteConversations = useMutation({
    mutationFn: (ids: string[]) => ids.length === 1 ? api.deleteAiConversation(ids[0]) : api.batchDeleteAiConversations(ids),
    onSuccess: async (_, ids) => {
      setSelectedIds(new Set());
      if (selectedId && ids.includes(selectedId)) setSelectedId(null);
      setOptimisticConversations((current) => current.filter((item) => !ids.includes(item.conversationId)));
      setLocalMessages((current) => {
        const next = { ...current };
        ids.forEach((id) => delete next[id]);
        return next;
      });
      setProblemContextByConversationId((current) => {
        const next = { ...current };
        ids.forEach((id) => delete next[id]);
        return next;
      });
      setRenderHintsByConversationId((current) => {
        const next = { ...current };
        ids.forEach((id) => delete next[id]);
        return next;
      });
      await queryClient.invalidateQueries({ queryKey: ["ai", "conversations"] });
    }
  });

  const serverMessages: LocalMessage[] = (messagesQuery.data ?? []).map((message) => ({
    ...message,
    content: visibleServerMessageContent(message, t("ai.chatFailed"), locale),
    status: toLocalMessageStatus(message)
  }));
  const shownMessages: LocalMessage[] = selectedId ? mergeChatMessages(serverMessages, localMessages[selectedId] ?? []) : [];
  const emptyPrompts = list("aiAssistant.quick.assist");
  const problemRecords = problemsQuery.data?.records ?? [];
  const selectedConversationProblemId = selectedConversation?.problemId ?? selectedConversation?.recentProblemId ?? null;
  const selectedConversationProblem = selectedConversationProblemId
    ? problemRecords.find((item) => item.id === selectedConversationProblemId)
    : undefined;
  const selectedConversationProblemContext = selectedId ? problemContextByConversationId[selectedId] : undefined;
  const filterProblem = problemRecords.find((item) => item.id === problemFilter);
  const linkedProblem = problem ?? selectedConversationProblem ?? filterProblem;
  const selectedConversationProblemTitle = problem?.title
    ?? problemTitle
    ?? selectedConversationProblem?.title
    ?? selectedConversationProblemContext?.title
    ?? (selectedConversationProblemId ? t("aiAssistant.problemReference", { id: selectedConversationProblemId }) : null);
  const problemLabelSeparator = locale === "zh-CN" ? "：" : ": ";
  const selectedConversationProblemLabel = selectedConversationProblemTitle
    ? `${t(lockedProblem ? "aiAssistant.currentProblem" : "aiAssistant.linkedProblem")}${problemLabelSeparator}${selectedConversationProblemTitle}`
    : t("aiAssistant.unlinkedProblem");
  const {
    selection: activeSelection,
    clearSelection: clearActiveSelection
  } = useAiSelectionContext({
    rootRef: messageRootRef,
    conversationId: selectedId ?? undefined,
    problem: linkedProblem,
    language
  });

  React.useEffect(() => {
    if (activeSelection) {
      setPinnedReference(activeSelection);
    }
  }, [activeSelection]);

  const clearReference = React.useCallback(() => {
    setPinnedReference(null);
    clearActiveSelection();
    window.getSelection()?.removeAllRanges();
  }, [clearActiveSelection]);

  React.useEffect(() => () => {
    streamAbortRef.current?.abort();
  }, []);

  const appendLocal = React.useCallback((conversationId: string, message: LocalMessage) => {
    setLocalMessages((current) => ({
      ...current,
      [conversationId]: mergeChatMessages([], [...(current[conversationId] ?? []), message])
    }));
  }, []);

  const updateLocalMatch = React.useCallback((conversationId: string, match: { id?: string; clientMessageId?: string }, patch: Partial<LocalMessage>) => {
    setLocalMessages((current) => ({
      ...current,
      [conversationId]: (current[conversationId] ?? []).map((message) => {
        const sameId = match.id && message.id === match.id;
        const sameClientId = match.clientMessageId && message.clientMessageId === match.clientMessageId;
        if (!sameId && !sameClientId) return message;
        const next = { ...message };
        Object.entries(patch).forEach(([key, value]) => {
          if (value !== undefined) {
            (next as Record<string, unknown>)[key] = value;
          }
        });
        return next;
      })
    }));
  }, []);

  const sendMessage = React.useCallback(async (text: string, options?: { displayContent?: string; clarificationAnswer?: AiClarificationAnswerPayload; selectionContext?: AiSelectionContextPayload; selectionLabel?: string }) => {
    const clean = text.trim();
    if (!clean || streaming || streamingRef.current) return;
    const clientMessageId = localId("client");
    if (pendingClientIdsRef.current.has(clientMessageId)) return;
    pendingClientIdsRef.current.add(clientMessageId);
    streamingRef.current = true;
    const selectionPayload = options?.selectionContext ?? pinnedReference?.payload;
    const selectionLabel = options?.selectionLabel ?? pinnedReference?.label;
    const visibleContent = options?.displayContent?.trim()
      || (selectionPayload && selectionLabel ? `${t("aiAssistant.selectionMessagePrefix", { label: selectionLabel })}\n${clean}` : clean);
    setError(null);
    setClarification(null);
    setClarificationText("");
    setSelectedClarificationOptions(new Set());
    setContextDebugPreview("");
    setContextBuildReport(null);

    let conversationId = selectedId;
    if (!conversationId) {
      try {
        const conversation = await createConversation.mutateAsync({ message: clean });
        conversationId = conversation.conversationId;
      } catch (err) {
        const message = err instanceof ApiError ? err.userMessage : t("ai.chatFailed");
        setError(message);
        pendingClientIdsRef.current.delete(clientMessageId);
        streamingRef.current = false;
        return;
      }
    }
    const fallbackMode = inferConversationMode(clean);
    setOptimisticConversations((current) => current.map((item) => (
      item.conversationId === conversationId ? { ...item, mode: fallbackMode } : item
    )));
    const outboundSubmissionContext = submissionContextPayload(submissionContext);
    const localSubmissionContext: AiSubmissionContextSummary | undefined = outboundSubmissionContext ? {
      ...outboundSubmissionContext,
      source: "request.submissionContext"
    } : undefined;

    const userMessage: LocalMessage = {
      id: localId("user"),
      conversationId,
      problemId: linkedProblem?.id ?? requestedProblemId ?? null,
      clientMessageId,
      role: "user",
      content: visibleContent,
      createdAt: new Date().toISOString(),
      local: true,
      status: "success",
      submissionContext: localSubmissionContext
    };
    const assistantId = localId("assistant");
    const assistantMessage: LocalMessage = {
      id: assistantId,
      conversationId,
      problemId: linkedProblem?.id ?? requestedProblemId ?? null,
      clientMessageId: `${clientMessageId}:assistant`,
      streamId: assistantId,
      role: "assistant",
      content: "",
      createdAt: new Date().toISOString(),
      local: true,
      status: "sending",
      submissionContext: localSubmissionContext
    };

    appendLocal(conversationId, userMessage);
    appendLocal(conversationId, assistantMessage);
    setDraft("");
    setStreaming(true);
    const abortController = new AbortController();
    streamAbortRef.current = abortController;

    try {
      let assistantText = "";
      let capturedTurnId: string | undefined;
      const outboundSelection = selectionPayload
        ? { ...selectionPayload, conversationId, ...(contestContext ? { codeContext: undefined } : {}) }
        : undefined;
      const outboundPayload = {
        conversationId,
        problemId: (linkedProblem?.id ?? requestedProblemId) || undefined,
        clientMessageId,
        message: clean,
        mode: hiddenAssistMode,
        problemContext: linkedProblem ? {
          id: linkedProblem.id,
          title: linkedProblem.title,
          difficulty: linkedProblem.difficulty,
          statement: linkedProblem.statement,
          notes: linkedProblem.notes,
          tags: linkedProblem.tags,
          samples: linkedProblem.samples,
          timeLimitMillis: linkedProblem.timeLimitMillis,
          memoryLimitKb: linkedProblem.memoryLimitKb
        } : undefined,
        codeContext: code && !contestContext ? { code, language } : undefined,
        clarificationAnswer: options?.clarificationAnswer,
        selectionContext: outboundSelection,
        contestContext: contestContext ?? undefined,
        submissionContext: outboundSubmissionContext
      };
      const handleStreamEvent = (event: string, data: string) => {
        if (event === "message") {
          const parsed = parseAssistantMessageEvent(data);
          const isStructured = data.trim().startsWith("{") && typeof parsed.contentMarkdown === "string";
          assistantText = isStructured ? parsed.contentMarkdown : assistantText + parsed.contentMarkdown;
          if (parsed.problemContext) {
            setProblemContextByConversationId((current) => ({ ...current, [conversationId]: parsed.problemContext! }));
          }
          if (parsed.renderHints) {
            setRenderHintsByConversationId((current) => ({ ...current, [conversationId]: parsed.renderHints! }));
          }
          updateLocalMatch(conversationId, { id: assistantId, clientMessageId: `${clientMessageId}:assistant` }, {
            id: parsed.assistantMessageId ? String(parsed.assistantMessageId) : parsed.messageId ? String(parsed.messageId) : undefined,
            clientMessageId: parsed.clientMessageId || `${clientMessageId}:assistant`,
            content: assistantText,
            parseWarnings: parsed.parseWarnings,
            renderHints: parsed.renderHints,
            problemContext: parsed.problemContext,
            submissionContext: parsed.submissionContext,
            status: "sending"
          });
        }
        if (event === "meta") {
          const parsed = parseDoneEvent(data);
          if (parsed?.turnId) {
            capturedTurnId = parsed.turnId;
          }
          if (parsed?.userMessageId) {
            updateLocalMatch(conversationId, { id: userMessage.id, clientMessageId }, {
              id: String(parsed.userMessageId),
              clientMessageId
            });
          }
        }
        if (event === "clarification") {
          const nextClarification = parseClarification(data);
          if (nextClarification) {
            setClarification(nextClarification);
            setClarificationText("");
            setSelectedClarificationOptions(new Set());
          }
        }
        if (event === "context") {
          const parsed = parseContextDebugPreview(data);
          setContextDebugPreview(parsed.debugPreview);
          setContextBuildReport(parsed.contextBuildReport ?? null);
          if (parsed.problemContext) {
            setProblemContextByConversationId((current) => ({ ...current, [conversationId]: parsed.problemContext! }));
          }
          if (parsed.renderHints) {
            setRenderHintsByConversationId((current) => ({ ...current, [conversationId]: parsed.renderHints! }));
          }
        }
        if (event === "error") {
          setError(data || t("ai.streamError"));
          updateLocalMatch(conversationId, { id: assistantId, clientMessageId: `${clientMessageId}:assistant` }, { status: "error" });
        }
        if (event === "done") {
          const parsed = parseDoneEvent(data);
          if (parsed?.conversationMode) {
            setOptimisticConversations((current) => current.map((item) => (
              item.conversationId === conversationId ? { ...item, mode: parsed.conversationMode || item.mode } : item
            )));
          }
          if (parsed?.userMessageId) {
            updateLocalMatch(conversationId, { id: userMessage.id, clientMessageId }, {
              id: String(parsed.userMessageId),
              clientMessageId
            });
          }
          if (parsed?.assistantMessageId) {
            updateLocalMatch(conversationId, { id: assistantId, clientMessageId: `${clientMessageId}:assistant` }, {
              id: String(parsed.assistantMessageId),
              clientMessageId: parsed.assistantClientMessageId || `${clientMessageId}:assistant`
            });
          }
        }
      };
      const runStream = (resumeTurnId?: string) => streamAi(outboundPayload, handleStreamEvent, {
        signal: abortController.signal,
        resumeTurnId
      });
      try {
        await runStream();
      } catch (streamErr) {
        // Connection dropped mid-turn: reattach once by turnId; the server replays the
        // existing turn (no second provider call, no duplicate messages).
        if (streamErr instanceof DOMException && streamErr.name === "AbortError") {
          throw streamErr;
        }
        if (!capturedTurnId) {
          throw streamErr;
        }
        await runStream(capturedTurnId);
      }
      updateLocalMatch(conversationId, { id: assistantId, clientMessageId: `${clientMessageId}:assistant` }, {
        content: assistantText || t("ai.streaming"),
        status: "success"
      });
      clearReference();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["ai", "messages", conversationId] }),
        queryClient.invalidateQueries({ queryKey: ["ai", "conversations"] })
      ]);
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        return;
      }
      const message = err instanceof ApiError ? err.userMessage : t("ai.chatFailed");
      setError(message);
      updateLocalMatch(conversationId, { id: assistantId, clientMessageId: `${clientMessageId}:assistant` }, { content: message, status: "error" });
    } finally {
      if (streamAbortRef.current === abortController) {
        streamAbortRef.current = null;
      }
      pendingClientIdsRef.current.delete(clientMessageId);
      streamingRef.current = false;
      setStreaming(false);
    }
  }, [
    appendLocal,
    clearReference,
    code,
    createConversation,
    contestContext,
    language,
    linkedProblem,
    queryClient,
    selectedId,
    submissionContext,
    pinnedReference,
    streaming,
    t,
    updateLocalMatch
  ]);

  const visibleIds = conversations.map((item) => item.conversationId);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIds.has(id));

  const handleSelectionIntent = React.useCallback((intent: AiSelectionContextPayload["uiIntent"]) => {
    if (!activeSelection) return;
    const nextReference = withSelectionIntent(activeSelection, intent);
    setPinnedReference(nextReference);
    clearActiveSelection();
    window.getSelection()?.removeAllRanges();
    if (!draft.trim()) {
      const promptByIntent: Record<string, string> = {
        ask_about_selection: t("aiAssistant.selectionAskPrompt"),
        explain_selection: t("aiAssistant.selectionExplainPrompt"),
        debug_selection: t("aiAssistant.selectionDebugPrompt"),
        optimize_selection: t("aiAssistant.selectionOptimizePrompt"),
        continue_from_selection: t("aiAssistant.selectionContinuePrompt")
      };
      setDraft(promptByIntent[String(intent)] ?? t("aiAssistant.selectionAskPrompt"));
    }
    window.setTimeout(() => draftRef.current?.focus(), 0);
  }, [activeSelection, clearActiveSelection, draft, t]);

  const switchConversation = React.useCallback((conversationId: string) => {
    if (conversationId === selectedId) return;
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    streamingRef.current = false;
    setStreaming(false);
    clearReference();
    setSelectedId(conversationId);
  }, [clearReference, selectedId]);

  const toggleSelected = (id: string) => {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectVisible = () => {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (allVisibleSelected) visibleIds.forEach((id) => next.delete(id));
      else visibleIds.forEach((id) => next.add(id));
      return next;
    });
  };

  const startNewConversation = () => {
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    streamingRef.current = false;
    setStreaming(false);
    clearReference();
    setKeyword("");
    setError(null);
    setClarification(null);
    setClarificationText("");
    setSelectedClarificationOptions(new Set());
    setContextDebugPreview("");
    setContextBuildReport(null);
    void createConversation.mutateAsync({});
  };

  const submitClarificationWithSelection = React.useCallback((selectedKeys: Set<string>, customText: string) => {
    if (!clarification) return;
    const payload = buildClarificationAnswerPayload(clarification, selectedKeys, customText);
    if (!payload) return;
    void sendMessage(payload.answerText, {
      displayContent: payload.answerText,
      clarificationAnswer: payload
    });
  }, [clarification, sendMessage]);

  const toggleClarificationOption = React.useCallback((option: AiClarificationOption, index: number) => {
    if (!clarification) return;
    const key = clarificationOptionKey(option, index);
    if (clarificationKind(clarification) === "multi_choice") {
      setSelectedClarificationOptions((current) => {
        const next = new Set(current);
        if (next.has(key)) next.delete(key);
        else next.add(key);
        return next;
      });
      return;
    }
    submitClarificationWithSelection(new Set([key]), "");
  }, [clarification, submitClarificationWithSelection]);

  const submitClarification = React.useCallback(() => {
    if (!clarification) return;
    submitClarificationWithSelection(selectedClarificationOptions, clarificationText);
  }, [clarification, clarificationText, selectedClarificationOptions, submitClarificationWithSelection]);

  const contextDebugSections = contextBuildReport?.sections ?? [];
  const contextDebugSourceEntries = Object.entries(contextBuildReport?.sourceSummary ?? {});
  const contextBudgetReport = contextBuildReport?.budget;

  return (
    <section className={cn(
      "grid min-h-0 overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white",
      compact ? "h-full grid-cols-1 lg:grid-cols-[290px_minmax(0,1fr)]" : "grid-cols-1 lg:h-[calc(100dvh-7rem)] lg:min-h-[680px] lg:grid-cols-[340px_minmax(0,1fr)]"
    )}>
      <aside className={cn(
        "flex min-h-0 flex-col border-b border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] lg:border-b-0 lg:border-r",
        !compact && "max-h-[975px] lg:max-h-none"
      )}>
        <div className="space-y-3 border-b border-[var(--oj-border-soft)] p-4">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold text-[var(--oj-ink)]">{t("ai.history")}</h2>
              <p className="mt-0.5 text-xs text-[var(--oj-ink-muted)]">
                {t("aiAssistant.conversationCount", { count: conversations.length })}
              </p>
            </div>
            <Button
              size="sm"
              variant="outline"
              disabled={createConversation.isPending}
              onClick={startNewConversation}
            >
              <MessageSquarePlus className="size-4" aria-hidden="true" />
              {t("aiAssistant.newConversation")}
            </Button>
          </div>

          <label className="relative block">
            <span className="sr-only">{t("aiAssistant.searchPlaceholder")}</span>
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--oj-ink-soft)]" aria-hidden="true" />
            <input
              value={keyword}
              onChange={(event) => { setKeyword(event.target.value); setConversationPage(1); }}
              className="h-10 w-full rounded-xl border border-[var(--oj-border)] bg-white pl-9 pr-3 text-sm outline-none placeholder:text-[var(--oj-ink-soft)] focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
              placeholder={t("aiAssistant.searchPlaceholder")}
            />
          </label>

          {!lockedProblem ? (
            <select
              value={problemFilter}
              onChange={(event) => { setProblemFilter(event.target.value); setConversationPage(1); }}
              className="h-10 w-full rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)] outline-none focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
              aria-label={t("aiAssistant.problemFilter")}
            >
              <option value="">{t("aiAssistant.allProblems")}</option>
              {problemsQuery.data?.records.map((item) => (
                <option key={item.id} value={item.id}>{item.title}</option>
              ))}
            </select>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant={manageMode ? "secondary" : "ghost"} onClick={() => setManageMode((value) => !value)}>
              {t("aiAssistant.manageHistory")}
            </Button>
            {manageMode ? (
              <>
                <Button size="sm" variant="ghost" onClick={selectVisible}>
                  {allVisibleSelected ? <CheckSquare className="size-4" aria-hidden="true" /> : <Square className="size-4" aria-hidden="true" />}
                  {t("aiAssistant.selectVisible")}
                </Button>
                {selectedIds.size > 0 ? (
                  <Button
                    size="sm"
                    variant="outline"
                    className="border-red-200 text-red-700 hover:bg-red-50"
                    onClick={() => setPendingDelete({
                      ids: [...selectedIds],
                      title: t("aiAssistant.deleteSelected"),
                      description: t("aiAssistant.batchDeleteConfirm", { count: selectedIds.size })
                    })}
                  >
                    <Trash2 className="size-4" aria-hidden="true" />
                    {t("aiAssistant.selectedCount", { count: selectedIds.size })}
                  </Button>
                ) : null}
              </>
            ) : null}
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          {conversationsQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : conversationsQuery.isError ? (
            <ErrorPanel title={t("ai.chatFailed")} description={conversationsQuery.error instanceof ApiError ? conversationsQuery.error.userMessage : undefined} />
          ) : conversations.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-[var(--oj-border)] bg-white p-5 text-sm text-[var(--oj-ink-muted)]">
              <p className="font-medium text-[var(--oj-ink)]">{keyword || problemFilter ? t("aiAssistant.noSearchTitle") : t("aiAssistant.allHistoryEmptyTitle")}</p>
              <p className="mt-2 leading-6">{keyword || problemFilter ? t("aiAssistant.noSearchDescription") : t("aiAssistant.allHistoryEmptyDescription")}</p>
            </div>
          ) : (
            <div className="space-y-2">
              {conversations.map((conversation) => {
                const active = conversation.conversationId === selectedId;
                const checked = selectedIds.has(conversation.conversationId);
                const title = conversation.title || t("aiAssistant.newConversationTitle");
                const modeLabel = conversation.mode ? t(`aiAssistant.modes.${conversation.mode}`, undefined, conversation.mode) : null;
                return (
                  <article
                    key={conversation.conversationId}
                    className={cn(
                      "group flex cursor-pointer gap-2 rounded-xl border bg-white p-3 transition-colors",
                      active ? "border-[var(--oj-primary)]" : checked ? "border-blue-300 bg-blue-50" : "border-[var(--oj-border-soft)] hover:border-[var(--oj-border)]"
                    )}
                    onClick={(event) => {
                      if (manageMode && shouldToggleRowSelection(event)) {
                        toggleSelected(conversation.conversationId);
                      }
                    }}
                  >
                    {manageMode ? (
                      <button
                        type="button"
                        className="mt-0.5 grid size-7 shrink-0 place-items-center rounded-lg text-[var(--oj-primary)] hover:bg-blue-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
                        onClick={() => toggleSelected(conversation.conversationId)}
                        aria-label={checked ? t("common.deselectItem") : t("common.selectItem")}
                      >
                        {checked ? <CheckSquare className="size-4" aria-hidden="true" /> : <Square className="size-4" aria-hidden="true" />}
                      </button>
                    ) : null}
                      <button
                        type="button"
                        className="min-w-0 flex-1 text-left outline-none"
                        onClick={() => manageMode
                          ? toggleSelected(conversation.conversationId)
                          : switchConversation(conversation.conversationId)}
                      >
                      <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-start gap-2">
                        <h3 className="min-w-0 truncate text-sm font-semibold text-[var(--oj-ink)]" title={title}>
                          {title}
                        </h3>
                        {modeLabel ? (
                          <Badge tone="blue" className="max-w-[8.75rem] shrink-0 overflow-hidden whitespace-nowrap px-2" title={modeLabel}>
                            <span className="min-w-0 truncate whitespace-nowrap">{modeLabel}</span>
                          </Badge>
                        ) : null}
                      </div>
                      <div className="mt-3 flex items-center justify-between gap-2 text-xs text-[var(--oj-ink-soft)]">
                        <span>{t("aiAssistant.messageCount", { count: conversation.messageCount ?? 0 })}</span>
                        <span>{formatDateTime(conversation.updatedAt)}</span>
                      </div>
                    </button>
                  </article>
                );
              })}
            </div>
          )}
          {conversationsTotal > 0 ? (
            <div className="mt-3 flex items-center justify-between gap-2 rounded-xl border border-[var(--oj-border-soft)] bg-white px-3 py-2 text-xs text-[var(--oj-ink-muted)]">
              <span className="tabular-nums">{conversationsTotal} · {conversationPage}/{conversationsMaxPage}</span>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  className="rounded-lg border border-[var(--oj-border)] px-2 py-1 font-medium text-[var(--oj-ink)] outline-none transition hover:border-[var(--oj-primary)] hover:text-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={conversationPage <= 1}
                  onClick={() => setConversationPage((current) => Math.max(1, current - 1))}
                >
                  {t("common.previous")}
                </button>
                <button
                  type="button"
                  className="rounded-lg border border-[var(--oj-border)] px-2 py-1 font-medium text-[var(--oj-ink)] outline-none transition hover:border-[var(--oj-primary)] hover:text-[var(--oj-primary)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)] disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={conversationPage >= conversationsMaxPage}
                  onClick={() => setConversationPage((current) => Math.min(conversationsMaxPage, current + 1))}
                >
                  {t("common.next")}
                </button>
              </div>
            </div>
          ) : null}
        </div>
      </aside>

      <div className={cn("flex min-h-0 flex-col", !compact && "min-h-[620px] lg:min-h-0")}>
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] px-4 py-3">
          <div className="min-w-0">
            {renaming && selectedConversation ? (
              <div className="flex flex-wrap items-center gap-2">
                <input
                  value={renameValue}
                  onChange={(event) => setRenameValue(event.target.value)}
                  className="h-9 min-w-[240px] rounded-xl border border-[var(--oj-border)] px-3 text-sm outline-none focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
                  aria-label={t("aiAssistant.renameConversation")}
                />
                <Button
                  size="sm"
                  onClick={() => {
                    if (selectedConversation) {
                      updateConversation.mutate({ conversationId: selectedConversation.conversationId, title: renameValue.trim() || selectedConversation.title });
                    }
                  }}
                >
                  {t("common.save")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRenaming(false)} aria-label={t("common.cancel")}>
                  <X className="size-4" aria-hidden="true" />
                </Button>
              </div>
            ) : (
              <>
                <h2 className="truncate text-base font-semibold text-[var(--oj-ink)]">
                  {selectedConversation?.title || t("aiAssistant.newConversationTitle")}
                </h2>
                <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
                  {selectedConversation || problem ? selectedConversationProblemLabel : t("aiAssistant.unlinkedProblem")}
                </p>
              </>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {selectedConversation ? (
              <>
                <Button size="sm" variant="ghost" onClick={() => setRenaming(true)}>
                  <Pencil className="size-4" aria-hidden="true" />
                  {t("aiAssistant.renameShort")}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  className="text-red-700 hover:bg-red-50"
                  onClick={() => setPendingDelete({
                    ids: [selectedConversation.conversationId],
                    title: t("aiAssistant.deleteConversation"),
                    description: t("aiAssistant.deleteConfirm")
                  })}
                >
                  <Trash2 className="size-4" aria-hidden="true" />
                  {t("common.delete")}
                </Button>
              </>
            ) : null}
          </div>
        </div>

        <div ref={messageRootRef} className="min-h-0 flex-1 overflow-y-auto bg-white px-4 py-4">
          {messagesQuery.isLoading ? (
            <LoadingPanel label={t("common.loading")} />
          ) : shownMessages.length === 0 ? (
            <div className="grid min-h-full place-items-center">
              <div className="max-w-xl text-center">
                <h3 className="text-lg font-semibold text-[var(--oj-ink)]">{t("ai.emptyTitle")}</h3>
                <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("ai.empty")}</p>
                <div className="mt-5 flex flex-wrap justify-center gap-2">
                  {emptyPrompts.slice(0, 4).map((prompt) => (
                    <Button key={prompt} variant="outline" size="sm" onClick={() => setDraft(prompt)}>
                      {prompt}
                    </Button>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="mx-auto flex max-w-4xl flex-col gap-4">
              {shownMessages.map((message) => {
                const isUser = message.role === "user";
                return (
                  <div key={message.id} className={cn("flex", isUser ? "justify-end" : "justify-start")}>
                    <article
                      className={cn(
                      "max-w-[min(760px,88%)] rounded-2xl px-4 py-3 text-sm leading-6",
                      isUser ? "bg-[var(--oj-primary)] text-white" : "border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] text-[var(--oj-ink)]",
                      message.status === "error" && "border-red-200 bg-red-50 text-red-900"
                    )}
                      data-ai-selectable="true"
                      data-ai-source-type={isUser ? "user_message" : "assistant_message"}
                      data-ai-message-id={message.id}
                      data-ai-role={message.role}
                      data-ai-section-title={isUser ? t("ai.you") : t("ai.tutor")}
                    >
                      <div className={cn("mb-1 text-xs font-medium", isUser ? "text-blue-100" : "text-[var(--oj-ink-soft)]")}>
                        {isUser ? t("ai.you") : t("ai.tutor")}
                      </div>
                      {isUser ? (
                        <p className="whitespace-pre-wrap text-pretty">{message.content || (message.status === "sending" ? t("ai.thinking") : "")}</p>
                      ) : (
                        <AssistantMessageRenderer
                          contentMarkdown={message.content}
                          pending={message.status === "sending"}
                          parseWarnings={message.parseWarnings}
                          renderHints={message.renderHints ?? renderHintsByConversationId[message.conversationId]}
                          problemContext={message.problemContext ?? problemContextByConversationId[message.conversationId]}
                        />
                      )}
                    </article>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {activeSelection ? (
          <SelectionAskToolbar
            x={activeSelection.rect.left}
            y={activeSelection.rect.top}
            onPick={handleSelectionIntent}
            onDismiss={clearActiveSelection}
          />
        ) : null}

        <div className="border-t border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-4" data-ai-selection-ui="true">
          {linkedProblem ? (
            <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-[var(--oj-ink-muted)]">
              <Badge tone={difficultyTone(linkedProblem.difficulty)}>{t(`difficulty.${linkedProblem.difficulty}`)}</Badge>
              <span className="font-medium text-[var(--oj-ink)]">{linkedProblem.title}</span>
              <span>{contestContext ? t("aiAssistant.contextProblemOnly") : code ? t("aiAssistant.contextCodeIncluded") : t("aiAssistant.contextAutoHint")}</span>
            </div>
          ) : null}

          {contestContext ? (
            <p className="mb-3 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">
              {t("aiAssistant.contestGuardNotice")}
            </p>
          ) : null}

          {submissionContext ? (
            <div className="mb-3 rounded-xl border border-blue-200 bg-blue-50 px-3 py-2 text-xs leading-5 text-blue-950">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone="blue">{t("aiAssistant.submissionContextChip", { id: submissionContext.submissionId })}</Badge>
                <span>{t("aiAssistant.submissionContextIncluded")}</span>
              </div>
              {submissionContext.note ? (
                <p className="mt-1 text-blue-900">{submissionContext.note}</p>
              ) : null}
            </div>
          ) : null}

          {clarification ? (() => {
            const kind = clarificationKind(clarification);
            const options = clarificationOptions(clarification);
            const showCustomInput = hasClarificationCustomInput(clarification);
            const isCodeInput = isClarificationCodeInput(clarification);
            const placeholder = clarification.input?.placeholder
              || (isCodeInput ? t("aiAssistant.clarificationCodePlaceholder") : t("aiAssistant.clarificationTextareaPlaceholder"));
            return (
              <div className="mb-3 rounded-2xl border border-blue-200 bg-blue-50 p-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="text-sm font-semibold text-blue-950">{clarification.title || t("aiAssistant.clarificationTitle")}</h3>
                      <Badge tone="blue">{t(`aiAssistant.clarificationKind.${kind}`, undefined, kind)}</Badge>
                    </div>
                    <p className="mt-1 text-xs leading-5 text-blue-900">{clarification.prompt || t("aiAssistant.clarificationHint")}</p>
                    {clarification.assumption ? <p className="mt-1 text-xs leading-5 text-blue-800">{t("aiAssistant.clarificationAssumption", { text: clarification.assumption })}</p> : null}
                  </div>
                  <button
                    type="button"
                    className="grid size-7 shrink-0 place-items-center rounded-lg text-blue-800 hover:bg-blue-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
                    onClick={() => setClarification(null)}
                    aria-label={t("aiAssistant.clarificationDismiss")}
                  >
                    <X className="size-4" aria-hidden="true" />
                  </button>
                </div>

                {options.length ? (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {options.map((option, index) => {
                      const key = clarificationOptionKey(option, index);
                      const checked = selectedClarificationOptions.has(key);
                      return (
                        <Button
                          key={key}
                          size="sm"
                          variant={checked ? "secondary" : "outline"}
                          onClick={() => toggleClarificationOption(option, index)}
                        >
                          {kind === "multi_choice" ? (
                            checked ? <CheckSquare className="size-4" aria-hidden="true" /> : <Square className="size-4" aria-hidden="true" />
                          ) : null}
                          {option.label || t("aiAssistant.clarificationFallback")}
                        </Button>
                      );
                    })}
                  </div>
                ) : kind === "confirm" ? (
                  <div className="mt-3 flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      onClick={() => submitClarificationWithSelection(new Set(["confirm"]), clarification.assumption || t("aiAssistant.clarificationConfirm"))}
                    >
                      {t("aiAssistant.clarificationConfirm")}
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => setClarification(null)}>
                      {t("common.cancel")}
                    </Button>
                  </div>
                ) : null}

                {showCustomInput ? (
                  <div className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-end">
                    <textarea
                      value={clarificationText}
                      onChange={(event) => setClarificationText(event.target.value)}
                      className={cn(
                        "min-h-20 min-w-0 flex-1 resize-y rounded-xl border border-blue-200 bg-white px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-[var(--oj-focus)]",
                        isCodeInput && "min-h-32 font-mono"
                      )}
                      placeholder={placeholder}
                    />
                    <Button
                      size="sm"
                      disabled={!clarificationText.trim() && selectedClarificationOptions.size === 0}
                      onClick={submitClarification}
                    >
                      {t("aiAssistant.clarificationSend")}
                    </Button>
                  </div>
                ) : kind === "multi_choice" ? (
                  <div className="mt-3">
                    <Button size="sm" disabled={selectedClarificationOptions.size === 0} onClick={submitClarification}>
                      {t("aiAssistant.clarificationSend")}
                    </Button>
                  </div>
                ) : null}
              </div>
            );
          })() : null}

          {error ? <p className="mb-2 text-sm text-[var(--oj-danger)]">{error}</p> : null}

          {import.meta.env.DEV && contextDebugPreview ? (
            <details className="mb-3 rounded-xl border border-dashed border-[var(--oj-border)] bg-white p-3">
              <summary className="cursor-pointer text-xs font-semibold text-[var(--oj-ink)]">
                {t("aiAssistant.contextDebugTitle")}
              </summary>
              <p className="mt-2 text-xs text-[var(--oj-ink-muted)]">{t("aiAssistant.contextDebugCopy")}</p>
              {contextBuildReport ? (
                <div className="mt-3 space-y-3">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-[var(--oj-ink-muted)]">
                    <Badge tone="blue">
                      {t("aiAssistant.contextDebugTokens", { total: contextBuildReport.totalEstimatedTokens })}
                    </Badge>
                    <span>{t("aiAssistant.contextDebugSectionCount", { required: contextBuildReport.requiredSectionCount, optional: contextBuildReport.optionalSectionCount })}</span>
                  </div>
                  {contextBudgetReport?.modelWindowTokens ? (
                    <div className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-xs text-[var(--oj-ink-muted)]">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold text-[var(--oj-ink)]">{t("aiAssistant.contextDebugBudget")}</span>
                        <Badge tone="blue">{t("aiAssistant.contextDebugBudgetModel", { model: contextBudgetReport.model || "-" })}</Badge>
                        <Badge tone="neutral">{t("aiAssistant.contextDebugBudgetWindow", { count: contextBudgetReport.modelWindowTokens })}</Badge>
                        <Badge tone="neutral">{t("aiAssistant.contextDebugBudgetThreshold", { count: contextBudgetReport.compressionThresholdTokens })}</Badge>
                        <Badge tone={contextBudgetReport.compressionApplied ? "blue" : "neutral"}>
                          {t("aiAssistant.contextDebugCompressionApplied", { value: contextBudgetReport.compressionApplied ? t("common.yes") : t("common.no") })}
                        </Badge>
                      </div>
                      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
                        <span>{t("aiAssistant.contextDebugBudgetLimit", { count: contextBudgetReport.maxPromptBudgetTokens })}</span>
                        <span>{t("aiAssistant.contextDebugBudgetEstimate", { before: contextBudgetReport.estimatedPromptTokensBefore, after: contextBudgetReport.estimatedPromptTokensAfter })}</span>
                      </div>
                      {contextBudgetReport.trimmedSections.length ? (
                        <p className="mt-1 truncate" title={contextBudgetReport.trimmedSections.join(", ")}>
                          {t("aiAssistant.contextDebugTrimmedSections", { items: contextBudgetReport.trimmedSections.join(", ") })}
                        </p>
                      ) : null}
                      {contextBudgetReport.droppedSections.length ? (
                        <p className="mt-1 truncate" title={contextBudgetReport.droppedSections.join(", ")}>
                          {t("aiAssistant.contextDebugDroppedSections", { items: contextBudgetReport.droppedSections.join(", ") })}
                        </p>
                      ) : null}
                      {contextBudgetReport.warnings.length ? (
                        <p className="mt-1 truncate text-amber-700" title={contextBudgetReport.warnings.join(", ")}>
                          {t("aiAssistant.contextDebugBudgetWarnings", { items: contextBudgetReport.warnings.join(", ") })}
                        </p>
                      ) : null}
                    </div>
                  ) : null}
                  {contextDebugSourceEntries.length ? (
                    <div className="flex flex-wrap gap-2">
                      {contextDebugSourceEntries.map(([source, count]) => (
                        <Badge key={source} tone="neutral" className="max-w-full">
                          <span className="max-w-[12rem] truncate" title={source}>{source}</span>
                          <span> x{count}</span>
                        </Badge>
                      ))}
                    </div>
                  ) : null}
                  {contextDebugSections.length ? (
                    <div className="grid gap-2">
                      {contextDebugSections.map((section) => {
                        const hits = retrievalHits(section);
                        return (
                          <div key={section.id} className="rounded-lg border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] px-3 py-2 text-xs text-[var(--oj-ink-muted)]">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-semibold text-[var(--oj-ink)]">{section.title || section.id}</span>
                              <Badge tone={section.required ? "blue" : "neutral"}>
                                {section.required ? t("aiAssistant.contextDebugRequired") : t("aiAssistant.contextDebugOptional")}
                              </Badge>
                              <span>{section.type}</span>
                              <span>{section.sensitivity}</span>
                              <span>{t("aiAssistant.contextDebugSectionTokens", { count: section.estimatedTokens })}</span>
                            </div>
                            <div className="mt-1 truncate" title={section.source}>{section.source}</div>
                            {hits.length ? (
                              <div className="mt-2 grid gap-1">
                                {hits.slice(0, 5).map((hit, index) => (
                                  <div key={`${section.id}-${hit.ownerType || "hit"}-${hit.ownerId || index}`} className="rounded-md bg-white px-2 py-1">
                                    <div className="flex flex-wrap items-center gap-2">
                                      <Badge tone="neutral">
                                        {t("aiAssistant.contextDebugRetrievalHit", {
                                          owner: hit.ownerType || "-",
                                          score: typeof hit.score === "number" ? hit.score.toFixed(3) : "-"
                                        })}
                                      </Badge>
                                      {hit.reasons?.length ? (
                                        <span className="truncate" title={hit.reasons.join(", ")}>
                                          {t("aiAssistant.contextDebugRetrievalReasons", { items: hit.reasons.join(", ") })}
                                        </span>
                                      ) : null}
                                    </div>
                                    {hit.preview ? <div className="mt-1 truncate" title={hit.preview}>{hit.preview}</div> : null}
                                  </div>
                                ))}
                              </div>
                            ) : null}
                          </div>
                        );
                      })}
                    </div>
                  ) : null}
                </div>
              ) : null}
            </details>
          ) : null}

          {pinnedReference ? (
            <SelectedContextChip reference={pinnedReference} onClear={clearReference} />
          ) : null}

          <form
            className="flex gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              void sendMessage(draft, pinnedReference ? {
                selectionContext: pinnedReference.payload,
                selectionLabel: pinnedReference.label
              } : undefined);
            }}
          >
            <textarea
              ref={draftRef}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              className={cn(textareaClass, "min-h-20 resize-none bg-white")}
              placeholder={t("aiAssistant.placeholder")}
            />
            <Button type="submit" className="self-end" disabled={!draft.trim() || streaming || createConversation.isPending}>
              <SendHorizonal className="size-4" aria-hidden="true" />
              {streaming ? t("ai.streaming") : t("ai.send")}
            </Button>
          </form>
          <p className="mt-2 text-xs leading-5 text-[var(--oj-ink-muted)]">{t("aiAssistant.ruleShort")}</p>
        </div>
      </div>

      {pendingDelete ? (
        <ConfirmDialog
          open={Boolean(pendingDelete)}
          onOpenChange={(open) => {
            if (!open) setPendingDelete(null);
          }}
          title={pendingDelete.title}
          description={pendingDelete.description}
          cancelLabel={t("common.cancel")}
          confirmLabel={t("common.delete")}
          onConfirm={async () => {
            await deleteConversations.mutateAsync(pendingDelete.ids);
            setPendingDelete(null);
          }}
        />
      ) : null}
    </section>
  );
}

function defaultSubmissionPrompt(intent: string | undefined, t: (key: string, params?: Record<string, string | number>, fallback?: string) => string) {
  return intent === "OPTIMIZE"
    ? t("aiAssistant.submissionAcceptedPrompt")
    : t("aiAssistant.submissionAnalysisPrompt");
}
