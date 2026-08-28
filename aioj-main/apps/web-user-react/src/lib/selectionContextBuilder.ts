import type { AiSelectionContextPayload, ProblemResponse } from "@aioj/api-client";

export interface BuiltSelectionContext {
  payload: AiSelectionContextPayload;
  label: string;
  rect: DOMRect;
}

interface BuildSelectionOptions {
  root: HTMLElement;
  conversationId?: string;
  problem?: ProblemResponse;
  language?: string;
}

const maxSelectedText = 4000;

export function buildSelectionContextFromWindow(options: BuildSelectionOptions): BuiltSelectionContext | null {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
  const selectedText = selection.toString().replace(/\s+$/g, "");
  if (!selectedText.trim()) return null;

  const range = selection.getRangeAt(0);
  const common = range.commonAncestorContainer;
  const commonElement = common.nodeType === Node.ELEMENT_NODE ? common as Element : common.parentElement;
  if (!commonElement || !options.root.contains(commonElement)) {
    return null;
  }

  const anchorElement = elementFromNode(selection.anchorNode);
  const focusElement = elementFromNode(selection.focusNode);
  const sourceElement = closestSelectable(anchorElement) || closestSelectable(focusElement);
  if (!sourceElement) return null;

  const codeElement = closestCodeElement(anchorElement) || closestCodeElement(focusElement);
  const isCode = Boolean(codeElement);
  const sourceType = isCode ? "code_block" : sourceElement.dataset.aiSourceType || "assistant_message";
  const sourceRole = sourceElement.dataset.aiRole;
  const sourceMessageId = sourceElement.dataset.aiMessageId;
  const sourceText = sourceElement.textContent ?? "";
  const selectedIndex = sourceText.indexOf(selectedText);
  const before = selectedIndex >= 0 ? sourceText.slice(Math.max(0, selectedIndex - 500), selectedIndex) : "";
  const after = selectedIndex >= 0 ? sourceText.slice(selectedIndex + selectedText.length, selectedIndex + selectedText.length + 500) : "";
  const lineRange = isCode ? codeLineRange(codeElement?.textContent ?? "", selectedText) : {};
  const language = isCode ? inferCodeLanguage(codeElement, options.language) : undefined;
  const rect = firstUsefulRect(range);
  const label = selectionLabel(sourceType, language, lineRange.startLine, lineRange.endLine, sourceRole);

  return {
    label,
    rect,
    payload: {
      selectionId: makeSelectionId(),
      conversationId: options.conversationId,
      sourceType,
      sourceMessageId,
      sourceRole,
      selectedText: selectedText.slice(0, maxSelectedText),
      selectedMarkdown: isCode ? codeFence(selectedText, language) : undefined,
      selectionRange: {
        startOffset: selectedIndex >= 0 ? selectedIndex : undefined,
        endOffset: selectedIndex >= 0 ? selectedIndex + selectedText.length : undefined,
        ...lineRange
      },
      surroundingContext: {
        before,
        after,
        sectionTitle: sourceElement.dataset.aiSectionTitle,
        messagePreview: compact(sourceText, 220)
      },
      codeContext: isCode ? {
        language,
        functionName: inferFunctionName(selectedText),
        enclosingSymbol: inferFunctionName(sourceText),
        latestCodeMessageId: sourceMessageId,
        codeHash: hashText(codeElement?.textContent ?? selectedText),
        hasCompileRisk: /void\s+\w+\s*\(|&\s*\w+|return\s+/.test(selectedText)
      } : undefined,
      problemContext: options.problem ? {
        problemId: options.problem.id,
        title: options.problem.title,
        tags: options.problem.tags,
        constraints: inferConstraints(options.problem.statement)
      } : undefined,
      uiIntent: "ask_about_selection"
    }
  };
}

export function withSelectionIntent(context: BuiltSelectionContext, uiIntent: AiSelectionContextPayload["uiIntent"]) {
  return {
    ...context,
    payload: {
      ...context.payload,
      uiIntent
    }
  };
}

function elementFromNode(node: Node | null) {
  if (!node) return null;
  return node.nodeType === Node.ELEMENT_NODE ? node as Element : node.parentElement;
}

function closestSelectable(element: Element | null) {
  return element?.closest<HTMLElement>("[data-ai-selectable='true']") ?? null;
}

function closestCodeElement(element: Element | null) {
  return element?.closest<HTMLElement>("pre code, code") ?? null;
}

function firstUsefulRect(range: Range) {
  const rects = [...range.getClientRects()].filter((rect) => rect.width > 0 && rect.height > 0);
  return rects[0] ?? range.getBoundingClientRect();
}

function codeLineRange(code: string, selected: string) {
  const startOffset = code.indexOf(selected);
  if (startOffset < 0) return {};
  const before = code.slice(0, startOffset);
  const startLine = before.split("\n").length;
  const endLine = startLine + selected.split("\n").length - 1;
  return { startLine, endLine };
}

function inferCodeLanguage(codeElement: HTMLElement | null | undefined, fallback?: string) {
  const className = codeElement?.className ?? "";
  const match = /language-([a-z0-9_+#-]+)/i.exec(String(className));
  return match?.[1] || fallback || "text";
}

function inferFunctionName(text: string) {
  const cpp = /\b(?:int|long|void|bool|double|string|auto)\s+([A-Za-z_]\w*)\s*\(/.exec(text);
  if (cpp) return cpp[1];
  const python = /\bdef\s+([A-Za-z_]\w*)\s*\(/.exec(text);
  if (python) return python[1];
  const java = /\b(?:public|private|protected)?\s*(?:static\s+)?(?:int|long|void|boolean|double|String)\s+([A-Za-z_]\w*)\s*\(/.exec(text);
  return java?.[1];
}

function inferConstraints(statement: string) {
  return statement
    .split(/\n+/)
    .map((line) => line.trim())
    .filter((line) => /<=|≥|≤|constraints?|数据范围|限制|1e\d|\d+\s*\*\s*10/i.test(line))
    .slice(0, 6);
}

function selectionLabel(sourceType: string, language?: string, startLine?: number, endLine?: number, role?: string) {
  if (sourceType === "code_block") {
    const range = startLine ? ` ${startLine}-${endLine ?? startLine}` : "";
    return `${(language || "code").toUpperCase()} code${range}`;
  }
  if (sourceType === "problem_context") return "problem context";
  if (role === "assistant") return "assistant reply";
  if (role === "user") return "user message";
  return "selected text";
}

function codeFence(text: string, language?: string) {
  const fence = text.includes("```") ? "````" : "```";
  return `${fence}${language || ""}\n${text}\n${fence}`;
}

function compact(value: string, max: number) {
  const normalized = value.replace(/\s+/g, " ").trim();
  return normalized.length <= max ? normalized : `${normalized.slice(0, max)}...`;
}

function hashText(value: string) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash).toString(36);
}

function makeSelectionId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
