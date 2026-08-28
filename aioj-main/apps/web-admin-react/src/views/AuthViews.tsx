import * as React from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { ApiError } from "@aioj/api-client";
import type { Role } from "@aioj/api-client";
import { Button } from "@aioj/ui-react";
import { Field, inputClass } from "../components/Common";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";

function AuthFrame({
  title,
  copy,
  children
}: {
  title: string;
  copy: string;
  children: React.ReactNode;
}) {
  const { t, locale, localeOptions, setLocale } = useI18n();
  return (
    <main className="grid min-h-dvh bg-[var(--oj-app-bg)] px-4 py-6 md:grid-cols-[minmax(0,1fr)_430px] md:gap-8 md:px-8">
      <section className="hidden min-h-0 flex-col justify-between rounded-xl border border-[var(--oj-border)] bg-[var(--oj-sidebar)] p-8 text-white md:flex">
        <div>
          <p className="text-sm font-medium text-[var(--oj-sidebar-muted)]">{t("common.product")}</p>
          <h1 className="mt-5 max-w-3xl text-balance text-4xl font-semibold leading-tight">
            {t("auth.adminTitle")}
          </h1>
          <p className="mt-4 max-w-2xl text-pretty text-sm leading-6 text-[var(--oj-sidebar-muted)]">{t("auth.adminCopy")}</p>
        </div>
        <div className="grid gap-3 lg:grid-cols-3">
          {[t("nav.users"), t("nav.problems"), t("nav.aiDrafts")].map((item) => (
            <div key={item} className="rounded-xl border border-white/10 bg-white/[0.04] p-4">
              <div className="text-sm font-semibold">{item}</div>
              <p className="mt-2 text-xs leading-5 text-[var(--oj-sidebar-muted)]">{t("common.adminConsole")}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="mx-auto flex w-full max-w-md flex-col justify-center">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <p className="text-sm font-semibold text-[var(--oj-ink)]">{t("common.appName")}</p>
            <p className="text-xs text-[var(--oj-ink-muted)]">{t("common.admin")}</p>
          </div>
          <select
            value={locale}
            onChange={(event) => setLocale(event.target.value as typeof locale)}
            className="h-9 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm"
            aria-label={t("locale.label")}
          >
            {localeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </div>
        <div className="rounded-xl border border-[var(--oj-border)] bg-white p-6 shadow-sm">
          <h2 className="text-2xl font-semibold text-[var(--oj-ink)]">{title}</h2>
          <p className="mt-2 text-sm leading-6 text-[var(--oj-ink-muted)]">{copy}</p>
          {children}
        </div>
      </section>
    </main>
  );
}

const ADMIN_APP_ROLES: readonly Role[] = ["TEACHER", "ADMIN"];

export function LoginView() {
  const { t } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { redirect?: string; expired?: string; registered?: string; account?: string };
  const [account, setAccount] = React.useState(search.account ?? "");
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState<string | null>(search.expired ? t("auth.expired") : null);
  const [submitting, setSubmitting] = React.useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (!account.trim() || !password) {
      setError(t("auth.accountPasswordRequired"));
      return;
    }
    setSubmitting(true);
    try {
      const tokens = await auth.login(account.trim(), password);
      if (!ADMIN_APP_ROLES.some((role) => tokens.roles.includes(role))) {
        await navigate({ to: "/blocked" });
        return;
      }
      if (tokens.passwordResetRequired) {
        await navigate({ to: "/force-password-change", search: { redirect: search.redirect || "/dashboard" } });
        return;
      }
      await navigate({ to: search.redirect || "/dashboard" });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.userMessage : t("auth.adminSignInFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthFrame title={t("auth.signIn")} copy={t("auth.adminCopy")}>
      <form className="mt-6 space-y-4" onSubmit={submit}>
        {search.registered ? <p className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-900">{t("auth.adminRegisteredAlert")}</p> : null}
        {error ? <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">{error}</p> : null}
        <Field label={t("common.account")}>
          <input className={inputClass} value={account} onChange={(event) => setAccount(event.target.value)} placeholder={t("auth.adminAccountPlaceholder")} autoComplete="username" />
        </Field>
        <Field label={t("common.password")}>
          <input className={inputClass} value={password} onChange={(event) => setPassword(event.target.value)} placeholder={t("auth.passwordPlaceholder")} type="password" autoComplete="current-password" />
        </Field>
        <Button className="w-full" disabled={submitting}>{submitting ? t("common.loading") : t("auth.signIn")}</Button>
      </form>
    </AuthFrame>
  );
}

export function RegisterView() {
  const { t } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const [account, setAccount] = React.useState("");
  const [displayName, setDisplayName] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [confirm, setConfirm] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  const [submitting, setSubmitting] = React.useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (account.trim().length < 3) return setError(t("auth.accountTooShort"));
    if (!displayName.trim()) return setError(t("auth.displayNameRequired"));
    if (email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) return setError(t("auth.emailInvalid"));
    if (password.length < 8) return setError(t("auth.adminPasswordTooShort"));
    if (password !== confirm) return setError(t("auth.passwordMismatch"));
    setSubmitting(true);
    try {
      await auth.register({
        account: account.trim(),
        displayName: displayName.trim(),
        email: email.trim() || undefined,
        password
      });
      await navigate({ to: "/login", search: { registered: "1", account: account.trim() } });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.userMessage : t("auth.registerFailed"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthFrame title={t("auth.register")} copy={t("auth.adminRegisterCopy")}>
      <form className="mt-6 space-y-4" onSubmit={submit}>
        {error ? <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">{error}</p> : null}
        <Field label={t("common.account")}>
          <input className={inputClass} value={account} onChange={(event) => setAccount(event.target.value)} placeholder={t("auth.accountPlaceholder")} autoComplete="username" />
        </Field>
        <Field label={t("common.displayName")}>
          <input className={inputClass} value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder={t("auth.displayNamePlaceholder")} autoComplete="name" />
        </Field>
        <Field label={t("common.email")}>
          <input className={inputClass} value={email} onChange={(event) => setEmail(event.target.value)} placeholder={t("auth.emailPlaceholder")} autoComplete="email" />
        </Field>
        <Field label={t("common.password")}>
          <input className={inputClass} value={password} onChange={(event) => setPassword(event.target.value)} placeholder={t("auth.adminPasswordRulePlaceholder")} type="password" autoComplete="new-password" />
        </Field>
        <Field label={t("auth.confirmPassword")}>
          <input className={inputClass} value={confirm} onChange={(event) => setConfirm(event.target.value)} placeholder={t("auth.confirmPassword")} type="password" autoComplete="new-password" />
        </Field>
        <Button className="w-full" disabled={submitting}>{submitting ? t("common.loading") : t("auth.register")}</Button>
      </form>
      <p className="mt-5 text-center text-sm text-[var(--oj-ink-muted)]">
        {t("auth.adminAlreadyAccount")}{" "}
        <Link className="font-medium text-[var(--oj-primary)]" to="/login">{t("auth.signIn")}</Link>
      </p>
    </AuthFrame>
  );
}
