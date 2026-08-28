import { MessageSquare, Sparkles, Wrench, Bug, ArrowRight, X } from "lucide-react";
import { cn } from "@aioj/ui-react";
import type { AiSelectionContextPayload } from "@aioj/api-client";
import { useI18n } from "../../lib/i18n";

interface SelectionAskToolbarProps {
  x: number;
  y: number;
  onPick: (intent: AiSelectionContextPayload["uiIntent"]) => void;
  onDismiss: () => void;
}

export function SelectionAskToolbar({ x, y, onPick, onDismiss }: SelectionAskToolbarProps) {
  const { t } = useI18n();
  const viewportWidth = typeof window === "undefined" ? 1024 : window.innerWidth;
  const viewportHeight = typeof window === "undefined" ? 768 : window.innerHeight;
  const top = Math.min(Math.max(12, y - 48), Math.max(12, viewportHeight - 144));
  const left = Math.max(12, Math.min(x, viewportWidth - 372));
  const actions: Array<{ intent: AiSelectionContextPayload["uiIntent"]; label: string; icon: typeof MessageSquare }> = [
    { intent: "ask_about_selection", label: t("aiAssistant.selectionAsk"), icon: MessageSquare },
    { intent: "explain_selection", label: t("aiAssistant.selectionExplain"), icon: Sparkles },
    { intent: "debug_selection", label: t("aiAssistant.selectionDebug"), icon: Bug },
    { intent: "optimize_selection", label: t("aiAssistant.selectionOptimize"), icon: Wrench },
    { intent: "continue_from_selection", label: t("aiAssistant.selectionContinue"), icon: ArrowRight }
  ];

  return (
    <div
      data-ai-selection-ui="true"
      className="fixed z-50 flex max-w-[calc(100vw-24px)] items-center gap-1 rounded-xl border border-[var(--oj-border)] bg-white p-1 shadow-lg"
      style={{ left, top }}
      role="toolbar"
      aria-label={t("aiAssistant.selectionToolbar")}
      onMouseDown={(event) => event.preventDefault()}
    >
      {actions.map((action) => {
        const Icon = action.icon;
        return (
          <button
            key={action.intent}
            type="button"
            className={cn(
              "inline-flex h-8 items-center gap-1.5 rounded-lg px-2 text-xs font-medium text-[var(--oj-ink-muted)]",
              "hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
            )}
            onClick={() => onPick(action.intent)}
          >
            <Icon className="size-3.5" aria-hidden="true" />
            <span className="whitespace-nowrap">{action.label}</span>
          </button>
        );
      })}
      <button
        type="button"
        className="grid size-8 place-items-center rounded-lg text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
        onClick={onDismiss}
        aria-label={t("common.cancel")}
      >
        <X className="size-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}
