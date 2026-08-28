import * as React from "react";
import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { Bot, ClipboardList, Database, GraduationCap, LayoutDashboard, LogOut, ShieldCheck, SlidersHorizontal, Trophy, UserRound, Users } from "lucide-react";
import type { Role } from "@aioj/api-client";
import { Button, cn } from "@aioj/ui-react";
import { useAuth } from "../lib/auth";
import { useI18n } from "../lib/i18n";

const navIcons = {
  dashboard: LayoutDashboard,
  users: Users,
  roles: ShieldCheck,
  classes: GraduationCap,
  problems: Database,
  contests: Trophy,
  drafts: Bot,
  aiConfig: SlidersHorizontal,
  operations: ClipboardList
};

const ADMIN_APP_ROLES: readonly Role[] = ["TEACHER", "ADMIN"];
const ADMIN_ONLY_ROLES: readonly Role[] = ["ADMIN"];
type AdminNavHref = "/dashboard" | "/users" | "/roles" | "/classes" | "/problems" | "/contests" | "/ai-drafts" | "/ai-model-configs" | "/operations";
type AdminNavItem = {
  label: string;
  href: AdminNavHref;
  icon: keyof typeof navIcons;
  roles: readonly Role[];
};

function adminNavItem(item: AdminNavItem): AdminNavItem {
  return item;
}

export function AdminShell() {
  const { t, locale, localeOptions, setLocale } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const [loggingOut, setLoggingOut] = React.useState(false);
  const navItems = [
    adminNavItem({ label: t("nav.dashboard"), href: "/dashboard", icon: "dashboard", roles: ADMIN_APP_ROLES }),
    adminNavItem({ label: t("nav.users"), href: "/users", icon: "users", roles: ADMIN_ONLY_ROLES }),
    adminNavItem({ label: t("nav.roles"), href: "/roles", icon: "roles", roles: ADMIN_ONLY_ROLES }),
    adminNavItem({ label: t("nav.classes"), href: "/classes", icon: "classes", roles: ADMIN_APP_ROLES }),
    adminNavItem({ label: t("nav.problems"), href: "/problems", icon: "problems", roles: ADMIN_APP_ROLES }),
    adminNavItem({ label: t("nav.contests"), href: "/contests", icon: "contests", roles: ADMIN_APP_ROLES }),
    adminNavItem({ label: t("nav.aiDrafts"), href: "/ai-drafts", icon: "drafts", roles: ADMIN_APP_ROLES }),
    adminNavItem({ label: t("nav.aiModelConfigs"), href: "/ai-model-configs", icon: "aiConfig", roles: ADMIN_ONLY_ROLES }),
    adminNavItem({ label: t("nav.operations"), href: "/operations", icon: "operations", roles: ADMIN_APP_ROLES })
  ].filter((item) => auth.hasAnyRole(item.roles));
  const userInitial = (auth.displayName || t("shell.adminFallback")).trim().slice(0, 1).toUpperCase();
  const roleLabel = auth.isAdmin ? t("role.ADMIN") : auth.isTeacher ? t("role.TEACHER") : t("role.STUDENT");

  async function logout() {
    setLoggingOut(true);
    try {
      await auth.logout();
      await navigate({ to: "/login" });
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <div className="min-h-dvh bg-[var(--oj-app-bg)] text-[var(--oj-ink)]">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 border-r border-[var(--oj-border)] bg-[var(--oj-sidebar)] px-4 py-5 lg:block">
        <div className="flex items-center gap-3 px-2">
          <div className="grid size-11 place-items-center rounded-xl bg-[var(--oj-primary)] text-white">
            <ShieldCheck className="size-5" aria-hidden="true" />
          </div>
          <div>
            <div className="text-sm font-semibold text-[var(--oj-sidebar-ink)]">{t("common.appName")}</div>
            <div className="text-xs text-[var(--oj-sidebar-muted)]">{t("common.adminConsole")}</div>
          </div>
        </div>
        <nav className="mt-8 space-y-1" aria-label={t("shell.adminNavLabel")}>
          {navItems.map((item) => {
            const Icon = navIcons[item.icon];
            const active = pathname === item.href || (item.href !== "/dashboard" && pathname.startsWith(item.href));
            return (
              <Link
                key={item.href}
                to={item.href}
                className={cn(
                  "flex h-10 items-center gap-3 rounded-lg px-3 text-sm font-medium text-[var(--oj-sidebar-muted)] outline-none transition-colors hover:bg-white/7 hover:text-white focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
                  active && "bg-white/10 text-white"
                )}
                aria-current={active ? "page" : undefined}
              >
                <Icon className="size-4" aria-hidden="true" />
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="absolute inset-x-4 bottom-5 rounded-xl border border-white/10 bg-white/[0.04] p-3">
          <div className="flex items-center gap-3">
            <span className="grid size-9 place-items-center rounded-lg bg-white/10 text-sm font-semibold text-white">{userInitial}</span>
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-white">{auth.displayName || t("shell.adminFallback")}</div>
              <div className="text-xs text-[var(--oj-sidebar-muted)]">{roleLabel}</div>
            </div>
          </div>
        </div>
      </aside>
      <main className="min-h-dvh lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-[var(--oj-border-soft)] bg-[var(--oj-app-bg)]/95 px-4 py-3 backdrop-blur supports-[backdrop-filter]:bg-[var(--oj-app-bg)]/82 lg:px-8">
          <div className="mx-auto flex max-w-[1500px] items-center justify-between gap-3">
            <div className="min-w-0 lg:hidden">
              <div className="text-sm font-semibold">{t("common.appName")}</div>
              <select
                value={pathname === "/" ? "/dashboard" : navItems.find((item) => pathname.startsWith(item.href))?.href ?? "/dashboard"}
                onChange={(event) => {
                  void navigate({ to: event.target.value as AdminNavHref });
                }}
                className="mt-1 h-9 max-w-[56vw] rounded-lg border border-[var(--oj-border)] bg-white px-2 text-sm"
                aria-label={t("shell.adminNavLabel")}
              >
                {navItems.map((item) => <option key={item.href} value={item.href}>{item.label}</option>)}
              </select>
            </div>
            <div className="hidden min-w-0 text-sm text-[var(--oj-ink-muted)] lg:block">
              {t("common.adminConsole")}
            </div>
            <div className="flex min-w-0 items-center gap-2">
              <select
                value={locale}
                onChange={(event) => setLocale(event.target.value as typeof locale)}
                className="hidden h-9 rounded-lg border border-[var(--oj-border)] bg-white px-3 text-sm md:block"
                aria-label={t("locale.label")}
              >
                {localeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
              <Button asChild variant="ghost" size="sm">
                <Link to="/dashboard">
                  <UserRound className="size-4" aria-hidden="true" />
                  <span className="hidden sm:inline">{auth.displayName || t("shell.adminFallback")}</span>
                </Link>
              </Button>
              <Button variant="outline" size="sm" disabled={loggingOut} onClick={() => void logout()}>
                <LogOut className="size-4" aria-hidden="true" />
                <span className="hidden sm:inline">{loggingOut ? t("common.loading") : t("common.logout")}</span>
              </Button>
            </div>
          </div>
        </header>
        <Outlet />
      </main>
    </div>
  );
}
