import * as React from "react";
import { Link2, X } from "lucide-react";
import { Button } from "@aioj/ui-react";
import type { BuiltSelectionContext } from "../../lib/selectionContextBuilder";
import { useI18n } from "../../lib/i18n";

export function SelectedContextChip({
  reference,
  onClear
}: {
  reference: BuiltSelectionContext;
  onClear: () => void;
}) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(false);
  const text = reference.payload.selectedText?.trim() || "";
  const before = reference.payload.surroundingContext?.before?.trim() || "";
  const after = reference.payload.surroundingContext?.after?.trim() || "";
  const source = reference.payload.sourceRole || reference.payload.sourceType || t("aiAssistant.selectionFallbackSource");

  return (
    <div data-ai-selection-ui="true" className="relative mb-3 inline-flex max-w-full">
      <div className="inline-flex max-w-full items-center gap-2 rounded-xl border border-[var(--oj-border)] bg-white px-3 py-2 text-xs text-[var(--oj-ink-muted)] shadow-sm">
        <button
          type="button"
          className="inline-flex min-w-0 flex-1 items-center gap-2 text-left outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          aria-label={t("aiAssistant.selectionOpenReference")}
        >
          <Link2 className="size-3.5 shrink-0 text-[var(--oj-primary)]" aria-hidden="true" />
          <span className="shrink-0 font-medium text-[var(--oj-ink)]">{t("aiAssistant.selectionReferenced")}</span>
          <span className="min-w-0 truncate">{reference.label}</span>
        </button>
        <button
          type="button"
          className="grid size-6 shrink-0 place-items-center rounded-md hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          onClick={(event) => {
            event.stopPropagation();
            onClear();
          }}
          aria-label={t("aiAssistant.selectionClear")}
        >
          <X className="size-3.5" aria-hidden="true" />
        </button>
      </div>

      {open ? (
        <div
          className="absolute bottom-[calc(100%+8px)] left-0 z-40 w-[min(28rem,calc(100vw-48px))] rounded-2xl border border-[var(--oj-border)] bg-white p-3 text-sm shadow-lg"
          role="dialog"
          aria-label={t("aiAssistant.selectionPreviewTitle")}
        >
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-sm font-semibold text-[var(--oj-ink)]">{t("aiAssistant.selectionPreviewTitle")}</p>
              <p className="mt-1 text-xs text-[var(--oj-ink-muted)]">
                {t("aiAssistant.selectionPreviewMeta", { source, label: reference.label })}
              </p>
            </div>
            <button
              type="button"
              className="grid size-7 shrink-0 place-items-center rounded-lg text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
              onClick={() => setOpen(false)}
              aria-label={t("common.close")}
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          </div>
          <div className="mt-3 max-h-56 overflow-auto rounded-xl border border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)] p-3 text-xs leading-5 text-[var(--oj-ink)]">
            {before ? <p className="mb-2 text-[var(--oj-ink-soft)]">{before}</p> : null}
            <p className="whitespace-pre-wrap font-medium text-[var(--oj-ink)]">{text}</p>
            {after ? <p className="mt-2 text-[var(--oj-ink-soft)]">{after}</p> : null}
          </div>
          <div className="mt-3 flex justify-end gap-2">
            <Button size="sm" variant="outline" onClick={() => setOpen(false)}>{t("common.close")}</Button>
            <Button size="sm" variant="ghost" onClick={onClear}>{t("aiAssistant.selectionClear")}</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
