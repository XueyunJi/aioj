import { useNavigate } from "@tanstack/react-router";
import { ShieldAlert } from "lucide-react";
import { Button } from "@aioj/ui-react";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";

export function BlockedView() {
  const { t } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const name = auth.profile?.displayName || auth.profile?.account || t("blocked.thisAccount");

  async function logout() {
    await auth.logout();
    await navigate({ to: "/login" });
  }

  return (
    <main className="grid min-h-dvh place-items-center bg-[var(--oj-app-bg)] px-4 py-8">
      <article className="w-full max-w-lg rounded-xl border border-[var(--oj-border)] bg-white p-6 shadow-sm">
        <div className="grid size-12 place-items-center rounded-xl bg-red-50 text-red-700">
          <ShieldAlert className="size-6" aria-hidden="true" />
        </div>
        <p className="mt-5 text-sm font-medium text-red-700">{t("blocked.tag")}</p>
        <h1 className="mt-2 text-2xl font-semibold text-[var(--oj-ink)]">{t("blocked.title")}</h1>
        <p className="mt-3 text-sm leading-6 text-[var(--oj-ink-muted)]">
          {t("blocked.description", { name })}
        </p>
        <div className="mt-6 flex flex-wrap gap-2">
          <Button onClick={() => void logout()}>{t("common.logout")}</Button>
          <Button variant="outline" onClick={() => void logout()}>{t("blocked.useAnother")}</Button>
        </div>
      </article>
    </main>
  );
}
