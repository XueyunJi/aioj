import * as React from "react";
import { ApiError, api, authStore } from "@aioj/api-client";
import { AlertTriangle, CheckCircle2, Loader2 } from "lucide-react";

type HandoffState = "switching" | "success" | "error";

export function ticketFromFragment(fragment: string) {
  if (!fragment.startsWith("#")) return null;
  const ticket = new URLSearchParams(fragment.slice(1)).get("ticket");
  return ticket?.trim() || null;
}

export function clearFragment() {
  window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
}

export function validServerNextPath(nextPath: string) {
  return /^\/problems\/[1-9][0-9]*$/.test(nextPath);
}

export async function exchangeTutorHandoff(ticket: string, redirect: (nextPath: string) => void) {
  try {
    const exchange = await api.exchangeHandoff(ticket);
    if (!validServerNextPath(exchange.nextPath)) {
      throw new Error("Invalid server nextPath");
    }
    authStore.save(exchange.tokens);
    const profile = await api.me();
    if (profile.passwordResetRequired) {
      throw new Error("Password reset is required");
    }
    redirect(exchange.nextPath);
  } catch (error) {
    authStore.clear();
    throw error;
  }
}

export function TutorHandoffView() {
  const [state, setState] = React.useState<HandoffState>("switching");
  const [message, setMessage] = React.useState("正在切换账号...");
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    started.current = true;
    const ticket = ticketFromFragment(window.location.hash);
    clearFragment();

    if (!ticket) {
      authStore.clear();
      setState("error");
      setMessage("切换链接无效或已过期，请返回 Tutor 重新打开题目。");
      return;
    }

    void (async () => {
      try {
        let nextPath = "";
        await exchangeTutorHandoff(ticket, (path) => { nextPath = path; });
        setState("success");
        setMessage("账号切换成功，正在打开题目...");
        window.location.replace(nextPath);
      } catch (error) {
        setState("error");
        setMessage(error instanceof ApiError && Math.trunc(error.code / 100) === 503
          ? "账号同步服务暂时不可用，请稍后从 Tutor 重试。"
          : "切换链接无效或已过期，请返回 Tutor 重新打开题目。");
      }
    })();
  }, []);

  const Icon = state === "switching" ? Loader2 : state === "success" ? CheckCircle2 : AlertTriangle;
  return (
    <main className="grid min-h-dvh place-items-center bg-[var(--oj-app-bg)] px-4 py-8">
      <section className="w-full max-w-md border border-[var(--oj-border)] bg-white p-6 shadow-sm">
        <div className="flex items-start gap-3">
          <Icon
            className={`mt-0.5 size-5 shrink-0 ${state === "switching" ? "animate-spin text-[var(--oj-primary)]" : state === "success" ? "text-emerald-700" : "text-red-700"}`}
            aria-hidden="true"
          />
          <div>
            <h1 className="text-lg font-semibold text-[var(--oj-ink)]">Tutor 账号同步</h1>
            <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]" role={state === "error" ? "alert" : "status"}>
              {message}
            </p>
          </div>
        </div>
      </section>
    </main>
  );
}
