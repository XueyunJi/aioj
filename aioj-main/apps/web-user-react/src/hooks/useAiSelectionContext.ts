import * as React from "react";
import type { AiSelectionContextPayload, ProblemResponse } from "@aioj/api-client";
import { buildSelectionContextFromWindow, withSelectionIntent, type BuiltSelectionContext } from "../lib/selectionContextBuilder";

interface UseAiSelectionContextOptions {
  rootRef: React.RefObject<HTMLElement | null>;
  conversationId?: string;
  problem?: ProblemResponse;
  language?: string;
}

export function useAiSelectionContext({
  rootRef,
  conversationId,
  problem,
  language
}: UseAiSelectionContextOptions) {
  const [selection, setSelection] = React.useState<BuiltSelectionContext | null>(null);
  const ignoreSelectionUiRef = React.useRef(false);

  const clearSelection = React.useCallback(() => {
    setSelection(null);
  }, []);

  const chooseIntent = React.useCallback((uiIntent: AiSelectionContextPayload["uiIntent"]) => {
    setSelection((current) => current ? withSelectionIntent(current, uiIntent) : current);
  }, []);

  React.useEffect(() => {
    const root = rootRef.current;
    if (!root) return;

    const isSelectionUiTarget = (target: EventTarget | null) => {
      return target instanceof Element && Boolean(target.closest("[data-ai-selection-ui='true']"));
    };

    const markSelectionUiInteraction = (event: Event) => {
      if (!isSelectionUiTarget(event.target)) return;
      ignoreSelectionUiRef.current = true;
      window.setTimeout(() => {
        ignoreSelectionUiRef.current = false;
      }, 160);
    };

    const updateSelection = () => {
      window.setTimeout(() => {
        const activeElement = document.activeElement;
        if (ignoreSelectionUiRef.current || isSelectionUiTarget(activeElement)) {
          return;
        }
        const next = buildSelectionContextFromWindow({ root, conversationId, problem, language });
        setSelection(next);
      }, 0);
    };
    const closeOnScroll = () => setSelection(null);

    root.addEventListener("mouseup", updateSelection);
    root.addEventListener("keyup", updateSelection);
    root.addEventListener("scroll", closeOnScroll, { passive: true });
    document.addEventListener("mousedown", markSelectionUiInteraction, true);
    document.addEventListener("selectionchange", updateSelection);
    return () => {
      root.removeEventListener("mouseup", updateSelection);
      root.removeEventListener("keyup", updateSelection);
      root.removeEventListener("scroll", closeOnScroll);
      document.removeEventListener("mousedown", markSelectionUiInteraction, true);
      document.removeEventListener("selectionchange", updateSelection);
    };
  }, [conversationId, language, problem, rootRef]);

  React.useEffect(() => {
    setSelection(null);
  }, [conversationId]);

  return {
    selection,
    clearSelection,
    chooseIntent
  };
}
