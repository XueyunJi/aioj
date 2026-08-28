import { CircleDashed } from "lucide-react";
import { cn } from "../lib/cn";
import { Button } from "../primitives/Button";

export interface EmptyStateProps {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
  className?: string;
}

export function EmptyState({ title, description, actionLabel, onAction, className }: EmptyStateProps) {
  return (
    <div className={cn("flex min-h-64 flex-col items-center justify-center rounded-2xl border border-dashed border-[var(--oj-border)] bg-white/70 p-8 text-center", className)}>
      <CircleDashed className="mb-4 size-10 text-[var(--oj-primary)]" aria-hidden="true" />
      <h3 className="text-base font-semibold text-[var(--oj-ink)]">{title}</h3>
      {description ? <p className="mt-2 max-w-md text-sm text-[var(--oj-ink-muted)]">{description}</p> : null}
      {actionLabel && onAction ? (
        <Button className="mt-5" size="sm" onClick={onAction}>
          {actionLabel}
        </Button>
      ) : null}
    </div>
  );
}
