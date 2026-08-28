import * as React from "react";
import { CircleAlert, CircleCheck, X } from "lucide-react";

export type ToastKind = "success" | "error";

export interface ToastItem {
  id: number;
  kind: ToastKind;
  message: string;
}

export interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = React.createContext<ToastApi | null>(null);

const AUTO_DISMISS_MILLIS = 4000;
const MAX_VISIBLE_TOASTS = 5;

let nextToastId = 1;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = React.useState<ToastItem[]>([]);
  const timers = React.useRef(new Map<number, number>());

  const dismiss = React.useCallback((id: number) => {
    setToasts((items) => items.filter((item) => item.id !== id));
    const timer = timers.current.get(id);
    if (timer != null) {
      window.clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = React.useCallback((kind: ToastKind, message: string) => {
    const id = nextToastId++;
    setToasts((items) => [...items.slice(-(MAX_VISIBLE_TOASTS - 1)), { id, kind, message }]);
    const timer = window.setTimeout(() => dismiss(id), AUTO_DISMISS_MILLIS);
    timers.current.set(id, timer);
  }, [dismiss]);

  React.useEffect(() => () => {
    timers.current.forEach((timer) => window.clearTimeout(timer));
    timers.current.clear();
  }, []);

  const api = React.useMemo<ToastApi>(() => ({
    success: (message) => push("success", message),
    error: (message) => push("error", message)
  }), [push]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 top-4 z-[100] flex flex-col items-center gap-2 px-4" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={
              "pointer-events-auto flex w-full max-w-xl items-start gap-2 rounded-xl border px-4 py-3 shadow-lg " +
              (toast.kind === "success"
                ? "border-emerald-200 bg-emerald-50 text-emerald-900"
                : "border-red-200 bg-red-50 text-red-800")
            }
          >
            {toast.kind === "success"
              ? <CircleCheck className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-hidden="true" />
              : <CircleAlert className="mt-0.5 size-4 shrink-0 text-red-600" aria-hidden="true" />}
            <p className="min-w-0 flex-1 whitespace-pre-wrap break-words text-sm leading-5">{toast.message}</p>
            <button
              type="button"
              className="shrink-0 rounded-md p-1 transition-colors hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
              onClick={() => dismiss(toast.id)}
              aria-label="close"
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const context = React.useContext(ToastContext);
  if (!context) {
    throw new Error("useToast must be used within ToastProvider");
  }
  return context;
}
