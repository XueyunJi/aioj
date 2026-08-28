import type { AiProblemContextSummary, AiRenderHints } from "@aioj/api-client";
import { cn } from "@aioj/ui-react";
import { useI18n } from "../../lib/i18n";
import { ProblemAwareMarkdown } from "./ProblemAwareMarkdown";
import { ProblemContextCard } from "./ProblemContextCard";

interface AssistantMessageRendererProps {
  contentMarkdown: string;
  renderHints?: AiRenderHints;
  problemContext?: AiProblemContextSummary;
  parseWarnings?: string[];
  pending?: boolean;
  className?: string;
}

export function AssistantMessageRenderer({
  contentMarkdown,
  renderHints,
  problemContext,
  parseWarnings,
  pending,
  className
}: AssistantMessageRendererProps) {
  const { t } = useI18n();
  const visibleMarkdown = normalizeAssistantMarkdown(
    contentMarkdown || (pending ? t("ai.generating") : ""),
    t("aiAssistant.responseParseFailed")
  );
  const shouldShowProblemContext = Boolean(
    problemContext
    && renderHints?.showProblemContext !== "none"
    && (renderHints?.showProblemContext === "compact" || problemContext.title || problemContext.problemId)
  );

  return (
    <div className={cn("min-w-0", className)}>
      {shouldShowProblemContext && problemContext ? (
        <ProblemContextCard problemContext={problemContext} />
      ) : null}
      <ProblemAwareMarkdown content={visibleMarkdown} />
      {import.meta.env.DEV && parseWarnings?.length ? (
        <p className="mt-2 text-[11px] leading-5 text-amber-700">
          {t("aiAssistant.responseParseWarning", { warnings: parseWarnings.join(", ") })}
        </p>
      ) : null}
    </div>
  );
}

function normalizeAssistantMarkdown(value: string, fallback: string) {
  const text = value.trim();
  if (!text) return fallback;
  const candidate = extractProtocolCandidate(text);
  if (!candidate) return value;
  try {
    const parsed = JSON.parse(candidate);
    if (!isInternalProtocol(parsed)) return value;
    return typeof parsed.content === "string" && parsed.content.trim() ? parsed.content : fallback;
  } catch {
    return looksLikeInternalProtocol(text) ? fallback : value;
  }
}

function extractProtocolCandidate(text: string) {
  const unfenced = stripSingleJsonFence(text);
  const start = unfenced.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  let inString = false;
  let escape = false;
  for (let index = start; index < unfenced.length; index += 1) {
    const char = unfenced[index];
    if (inString) {
      if (escape) {
        escape = false;
        continue;
      }
      if (char === "\\") {
        escape = true;
        continue;
      }
      if (char === "\"") inString = false;
      continue;
    }
    if (char === "\"") {
      inString = true;
      continue;
    }
    if (char === "{") depth += 1;
    if (char === "}") {
      depth -= 1;
      if (depth === 0) return unfenced.slice(start, index + 1);
    }
  }
  return null;
}

function stripSingleJsonFence(text: string) {
  if (!text.startsWith("```")) return text;
  const firstNewline = text.indexOf("\n");
  if (firstNewline < 0) return text;
  const language = text.slice(3, firstNewline).trim().toLowerCase();
  if (language && language !== "json") return text;
  const closingFence = text.lastIndexOf("```");
  if (closingFence <= firstNewline) return text;
  if (text.slice(closingFence + 3).trim()) return text;
  return text.slice(firstNewline + 1, closingFence).trim();
}

function isInternalProtocol(value: unknown) {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  const hasVisibleSlot = "content" in record || "clarification" in record;
  const hasMetadata = "teachingDecision" in record
    || "stuckLayer" in record
    || "studentLevel" in record
    || "renderHints" in record
    || "clarification" in record;
  return hasVisibleSlot && hasMetadata;
}

function looksLikeInternalProtocol(value: string) {
  return value.includes("\"teachingDecision\"")
    || value.includes("\"stuckLayer\"")
    || value.includes("\"studentLevel\"")
    || value.includes("\"clarification\"")
    || value.includes("\"renderHints\"");
}
