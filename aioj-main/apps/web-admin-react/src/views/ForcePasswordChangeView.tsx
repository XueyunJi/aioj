import * as React from "react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { ApiError } from "@aioj/api-client";
import { Button } from "@aioj/ui-react";
import { Field, inputClass } from "../components/Common";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";

export function ForcePasswordChangeView() {
  const { t } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { redirect?: string };
  const [currentPassword, setCurrentPassword] = React.useState("");
  const [newPassword, setNewPassword] = React.useState("");
  const [confirmPassword, setConfirmPassword] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [submitting, setSubmitting] = React.useState(false);
  const [loggingOut, setLoggingOut] = React.useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (!currentPassword || !newPassword) {
      setError(t("auth.passwordChangeRequiredFields"));
      return;
    }
    if (newPassword.length < 8) {
      setError(t("auth.newPasswordTooShort"));
      return;
    }
    if (newPassword === currentPassword) {
      setError(t("auth.newPasswordSameAsCurrent"));
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(t("auth.passwordMismatch"));
      return;
    }
    setSubmitting(true);
    try {
      await auth.changePassword({ currentPassword, newPassword });
      await navigate({ to: safeRedirect(search.redirect, "/dashboard"), replace: true });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.userMessage : t("auth.passwordChangeFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  async function cancelLogin() {
    setError(null);
    setLoggingOut(true);
    try {
      await auth.logout();
      await navigate({ to: "/login", replace: true });
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <main className="grid min-h-dvh place-items-center bg-[var(--oj-app-bg)] px-4 py-8">
      <section className="w-full max-w-md rounded-xl border border-[var(--oj-border)] bg-white p-6 shadow-sm">
        <p className="text-sm font-semibold text-[var(--oj-primary)]">{t("common.adminConsole")}</p>
        <h1 className="mt-3 text-2xl font-semibold text-[var(--oj-ink)]">{t("auth.forcePasswordTitle")}</h1>
        <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{t("auth.forcePasswordCopy")}</p>
        <form className="mt-6 space-y-4" onSubmit={submit}>
          <Field label={t("auth.currentPassword")}>
            <input className={inputClass} type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} autoComplete="current-password" />
          </Field>
          <Field label={t("auth.newPassword")}>
            <input className={inputClass} type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} autoComplete="new-password" />
          </Field>
          <Field label={t("auth.confirmPassword")}>
            <input className={inputClass} type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} autoComplete="new-password" />
          </Field>
          {error ? <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">{error}</p> : null}
          <Button type="submit" className="w-full" disabled={submitting || loggingOut}>{submitting ? t("common.loading") : t("auth.updatePassword")}</Button>
          <Button type="button" variant="outline" className="w-full" disabled={submitting || loggingOut} onClick={cancelLogin}>
            {loggingOut ? t("common.loading") : t("auth.cancelLogin")}
          </Button>
        </form>
      </section>
    </main>
  );
}

function safeRedirect(value: string | undefined, fallback: string) {
  if (!value || value === "/force-password-change" || value.startsWith("/login")) {
    return fallback;
  }
  return value;
}
