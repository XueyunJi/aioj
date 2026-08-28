import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react";
import { Badge, Button, cn } from "@aioj/ui-react";
import type { ReactNode } from "react";

export { EmptyState } from "@aioj/ui-react";

export function PageSection({
  eyebrow,
  title,
  description,
  actions
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-4 border-b border-[var(--oj-border-soft)] pb-5 md:flex-row md:items-end md:justify-between">
      <div className="min-w-0">
        {eyebrow ? <p className="mb-2 text-sm font-medium text-[var(--oj-primary)]">{eyebrow}</p> : null}
        <h1 className="text-balance text-2xl font-semibold text-[var(--oj-ink)] md:text-3xl">{title}</h1>
        {description ? <p className="mt-2 max-w-[72ch] text-pretty text-sm leading-6 text-[var(--oj-ink-muted)]">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}

export function Field({
  label,
  children,
  error
}: {
  label: string;
  children: ReactNode;
  error?: string | null;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-[var(--oj-ink)]">{label}</span>
      {children}
      {error ? <span className="mt-1.5 block text-sm text-[var(--oj-danger)]">{error}</span> : null}
    </label>
  );
}

export const inputClass = cn(
  "h-10 w-full rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)] outline-none transition-colors",
  "placeholder:text-[var(--oj-ink-soft)] focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
);

export const textareaClass = cn(
  "w-full rounded-xl border border-[var(--oj-border)] bg-white px-3 py-2 text-sm leading-6 text-[var(--oj-ink)] outline-none transition-colors",
  "placeholder:text-[var(--oj-ink-soft)] focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
);

export function SkeletonBlock({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-xl bg-slate-200/70", className)} />;
}

export function LoadingPanel({ label }: { label: string }) {
  return (
    <div className="grid min-h-48 place-items-center rounded-2xl border border-[var(--oj-border-soft)] bg-white">
      <div className="flex items-center gap-2 text-sm text-[var(--oj-ink-muted)]">
        <Loader2 className="size-4 animate-spin" aria-hidden="true" />
        {label}
      </div>
    </div>
  );
}

export function ErrorPanel({
  title,
  description,
  action,
  tone = "danger"
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  tone?: "danger" | "success";
}) {
  const Icon = tone === "success" ? CheckCircle2 : AlertTriangle;
  return (
    <div className={cn(
      "rounded-2xl border p-5",
      tone === "success" ? "border-emerald-200 bg-emerald-50 text-emerald-950" : "border-red-200 bg-red-50 text-red-950"
    )}>
      <div className="flex items-start gap-3">
        <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold">{title}</h2>
          {description ? <p className={cn("mt-1 text-sm leading-6", tone === "success" ? "text-emerald-900" : "text-red-900")}>{description}</p> : null}
          {action ? <div className="mt-4">{action}</div> : null}
        </div>
      </div>
    </div>
  );
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  cancelLabel,
  confirmLabel,
  onConfirm
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  cancelLabel: string;
  confirmLabel: string;
  onConfirm: () => void | Promise<void>;
}) {
  return (
    <AlertDialog.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-40 bg-slate-950/35" onClick={() => onOpenChange(false)} />
        <AlertDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,420px)] -translate-x-1/2 -translate-y-1/2 rounded-2xl border border-[var(--oj-border)] bg-white p-5 shadow-lg outline-none">
          <AlertDialog.Title className="text-base font-semibold text-[var(--oj-ink)]">{title}</AlertDialog.Title>
          <AlertDialog.Description className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">
            {description}
          </AlertDialog.Description>
          <div className="mt-5 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <Button variant="outline">{cancelLabel}</Button>
            </AlertDialog.Cancel>
            <AlertDialog.Action asChild>
              <Button
                className="bg-red-700 hover:bg-red-800"
                onClick={(event) => {
                  event.preventDefault();
                  void Promise.resolve(onConfirm()).then(() => onOpenChange(false));
                }}
              >
                {confirmLabel}
              </Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}

export function MetaBadge({ label, tone = "neutral" }: { label: string; tone?: "blue" | "green" | "amber" | "red" | "neutral" }) {
  return <Badge tone={tone}>{label}</Badge>;
}
