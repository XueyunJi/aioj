import * as AlertDialog from "@radix-ui/react-alert-dialog";
import * as Dialog from "@radix-ui/react-dialog";
import { AlertTriangle, CheckCircle2, Loader2, X } from "lucide-react";
import type { ReactNode } from "react";
import { Badge, Button, cn } from "@aioj/ui-react";

export { EmptyState } from "@aioj/ui-react";

export const inputClass = cn(
  "h-10 w-full rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm text-[var(--oj-ink)] outline-none transition-colors",
  "placeholder:text-[var(--oj-ink-soft)] focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
);

export const textareaClass = cn(
  "w-full rounded-lg border border-[var(--oj-border)] bg-white px-3 py-2 text-sm leading-6 text-[var(--oj-ink)] outline-none transition-colors",
  "placeholder:text-[var(--oj-ink-soft)] focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
);

export const selectClass = inputClass;

export function PageHeader({
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
        <h1 className="text-balance text-2xl font-semibold text-[var(--oj-ink)]">{title}</h1>
        {description ? <p className="mt-2 max-w-[72ch] text-pretty text-sm leading-6 text-[var(--oj-ink-muted)]">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function Field({
  label,
  children,
  error,
  hint
}: {
  label: string;
  children: ReactNode;
  error?: string | null;
  hint?: ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-[var(--oj-ink)]">{label}</span>
      {children}
      {error ? <span className="mt-1.5 block text-sm text-[var(--oj-danger)]">{error}</span> : hint ? <span className="mt-1.5 block text-xs leading-5 text-[var(--oj-ink-muted)]">{hint}</span> : null}
    </label>
  );
}

export function LoadingPanel({ label }: { label: string }) {
  return (
    <div className="grid min-h-44 place-items-center rounded-xl border border-[var(--oj-border-soft)] bg-white">
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
      "rounded-xl border p-4",
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

export function TableShell({ children }: { children: ReactNode }) {
  return (
    <section className="min-w-0 max-w-full overflow-hidden rounded-xl border border-[var(--oj-border)] bg-white">
      <div className="max-w-full overflow-x-auto overscroll-x-contain [&_table]:w-full">{children}</div>
    </section>
  );
}

export function PaginationRow({
  page,
  total,
  pageSize,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [20, 50, 100],
  previousLabel,
  nextLabel,
  pageSizeLabel,
  disabled = false
}: {
  page: number;
  total: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
  pageSizeOptions?: number[];
  previousLabel: string;
  nextLabel: string;
  pageSizeLabel?: string;
  disabled?: boolean;
}) {
  const totalPages = Math.max(1, Math.ceil(total / Math.max(1, pageSize)));
  const safePage = Math.min(Math.max(1, page), totalPages);
  const start = total === 0 ? 0 : (safePage - 1) * pageSize + 1;
  const end = Math.min(total, safePage * pageSize);
  return (
    <div className="flex flex-col gap-3 rounded-xl border border-[var(--oj-border)] bg-white px-4 py-3 text-sm text-[var(--oj-ink-muted)] sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-wrap items-center gap-3">
        <span className="tabular-nums">{start}-{end} / {total}</span>
        <span className="tabular-nums">{safePage} / {totalPages}</span>
        {onPageSizeChange ? (
          <label className="inline-flex items-center gap-2">
            <span>{pageSizeLabel}</span>
            <select
              className="h-9 rounded-lg border border-[var(--oj-border)] bg-white px-2 text-sm text-[var(--oj-ink)] outline-none focus:border-[var(--oj-primary)] focus:ring-2 focus:ring-[var(--oj-focus)]"
              value={pageSize}
              onChange={(event) => onPageSizeChange(Number(event.target.value))}
            >
              {pageSizeOptions.map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </label>
        ) : null}
      </div>
      <div className="flex items-center gap-2">
        <Button type="button" variant="outline" size="sm" disabled={disabled || safePage <= 1} onClick={() => onPageChange(safePage - 1)}>
          {previousLabel}
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={disabled || safePage >= totalPages} onClick={() => onPageChange(safePage + 1)}>
          {nextLabel}
        </Button>
      </div>
    </div>
  );
}

export function SidePanel({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  wide = false,
  presentation = "side",
  workspaceSize = "xl",
  workspaceHeight = "fixed"
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  presentation?: "side" | "workspace";
  workspaceSize?: "md" | "lg" | "xl";
  workspaceHeight?: "fixed" | "fit";
}) {
  const workspace = presentation === "workspace";
  const fitWorkspace = workspace && workspaceHeight === "fit";
  const workspaceWidth = workspaceSize === "md"
    ? "w-[min(94vw,860px)]"
    : workspaceSize === "lg"
      ? "w-[min(95vw,1120px)]"
      : "w-[min(96vw,1440px)]";
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay
          className="fixed inset-0 z-40 bg-slate-950/35"
          onClick={() => onOpenChange(false)}
        />
        <Dialog.Content
          onPointerDownOutside={() => onOpenChange(false)}
          className={cn(
            workspace
              ? cn(
                "fixed left-1/2 top-1/2 z-50 flex -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-[var(--oj-border)] bg-white shadow-xl outline-none",
                workspaceWidth,
                fitWorkspace ? "max-h-[min(92dvh,920px)]" : "h-[min(94dvh,980px)]"
              )
              : "fixed inset-y-0 right-0 z-50 flex w-[min(96vw,760px)] flex-col border-l border-[var(--oj-border)] bg-white shadow-xl outline-none",
            !workspace && wide && "w-[min(96vw,1040px)]"
          )}
        >
          <div className="flex items-start justify-between gap-4 border-b border-[var(--oj-border-soft)] px-5 py-4">
            <div className="min-w-0">
              <Dialog.Title className="text-base font-semibold text-[var(--oj-ink)]">{title}</Dialog.Title>
              {description ? <Dialog.Description className="mt-1 text-sm leading-6 text-[var(--oj-ink-muted)]">{description}</Dialog.Description> : null}
            </div>
            <Dialog.Close asChild>
              <button type="button" className="grid size-8 shrink-0 place-items-center rounded-lg text-[var(--oj-ink-muted)] hover:bg-[var(--oj-surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]" aria-label="Close">
                <X className="size-4" aria-hidden="true" />
              </button>
            </Dialog.Close>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
          {footer ? <div className="border-t border-[var(--oj-border-soft)] px-5 py-4">{footer}</div> : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
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
        <AlertDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,420px)] -translate-x-1/2 -translate-y-1/2 rounded-xl border border-[var(--oj-border)] bg-white p-5 shadow-lg outline-none">
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

export function StatusBadge({ label, tone = "neutral" }: { label: string; tone?: "blue" | "green" | "amber" | "red" | "neutral" }) {
  return <Badge className="w-fit" tone={tone}>{label}</Badge>;
}
