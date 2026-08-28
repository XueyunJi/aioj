import * as React from "react";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Link,
  Outlet,
  redirect,
  RouterProvider,
  useNavigate,
  useRouterState
} from "@tanstack/react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, authStore, isAuthenticationError, isAuthStorageKey, subscribeUserNotifications, type AuthChangedDetail } from "@aioj/api-client";
import { AppShell, Button } from "@aioj/ui-react";
import { LogOut, UserRound } from "lucide-react";
import { AuthProvider, useAuth } from "./lib/auth";
import { useI18n } from "./lib/i18n";

type RouterContext = {
  auth: ReturnType<typeof useAuth>;
};

type LoginSearch = { redirect?: string; expired?: string };
type ProblemSearch = { keyword?: string; difficulty?: string; tag?: string; page?: number };
type ProblemDetailSearch = { contestId?: string; contestRunId?: string; contestProblemId?: string };
type ContestDetailSearch = { runId?: string; tab?: string };
type ContestScoreboardSearch = { runId?: string };
type ContestPostmortemSearch = { runId?: string };
type SubmissionSearch = { status?: string; problemId?: string; contestId?: string; contestRunId?: string; contestProblemId?: string; language?: string; scope?: string; page?: number };

const LoginRouteView = lazyRoute(() => import("./views/AuthViews").then((module) => ({ default: module.LoginView })));
const ForcePasswordChangeRouteView = lazyRoute(() => import("./views/ForcePasswordChangeView").then((module) => ({ default: module.ForcePasswordChangeView })));
const DashboardRouteView = lazyRoute(() => import("./views/DashboardView").then((module) => ({ default: module.DashboardView })));
const ProblemsRouteView = lazyRoute(() => import("./views/ProblemsView").then((module) => ({ default: module.ProblemsView })));
const ProblemDetailRouteView = lazyRoute(() => import("./views/ProblemDetailView").then((module) => ({ default: module.ProblemDetailView })));
const ContestsRouteView = lazyRoute(() => import("./views/ContestsView").then((module) => ({ default: module.ContestsView })));
const ContestDetailRouteView = lazyRoute(() => import("./views/ContestDetailView").then((module) => ({ default: module.ContestDetailView })));
const ContestProblemDetailRouteView = lazyRoute(() => import("./views/ContestProblemDetailView").then((module) => ({ default: module.ContestProblemDetailView })));
const ContestScoreboardRouteView = lazyRoute(() => import("./views/ContestScoreboardView").then((module) => ({ default: module.ContestScoreboardView })));
const ContestPostmortemRouteView = lazyRoute(() => import("./views/ContestStudentPostmortemView").then((module) => ({ default: module.ContestStudentPostmortemView })));
const SubmissionsRouteView = lazyRoute(() => import("./views/SubmissionsView").then((module) => ({ default: module.SubmissionsView })));
const AiChatRouteView = lazyRoute(() => import("./views/AiChatView").then((module) => ({ default: module.AiChatView })));
const ProfileRouteView = lazyRoute(() => import("./views/ProfileView").then((module) => ({ default: module.ProfileView })));

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  validateSearch: (search: Record<string, unknown>): LoginSearch => {
    const next: LoginSearch = {};
    if (typeof search.redirect === "string") next.redirect = search.redirect;
    if (typeof search.expired === "string") next.expired = search.expired;
    return next;
  },
  beforeLoad: async ({ context }) => {
    if (!authStore.accessToken) return;
    let profile;
    try {
      profile = await context.auth.ensureProfile();
    } catch (error) {
      if (isAuthenticationError(error)) context.auth.clearLocal();
    }
    if (profile?.passwordResetRequired) throw redirect({ to: "/force-password-change" });
    if (profile) throw redirect({ to: "/" });
  },
  component: LoginRouteView
});

const forcePasswordChangeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/force-password-change",
  beforeLoad: async ({ context, location }) => {
    if (!authStore.accessToken) {
      context.auth.clearLocal();
      throw redirect({ to: "/login", search: { redirect: location.href } });
    }
    let profile;
    try {
      profile = await context.auth.ensureProfile();
    } catch (error) {
      if (isAuthenticationError(error)) {
        throw redirect({ to: "/login", search: { redirect: location.href, expired: "1" } });
      }
    }
    if (!profile?.passwordResetRequired) throw redirect({ to: "/" });
  },
  component: ForcePasswordChangeRouteView
});

const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "app",
  beforeLoad: async ({ context, location }) => {
    if (!authStore.accessToken) {
      context.auth.clearLocal();
      throw redirect({ to: "/login", search: { redirect: location.href } });
    }
    let profile;
    try {
      profile = await context.auth.ensureProfile();
    } catch (error) {
      if (isAuthenticationError(error)) {
        throw redirect({ to: "/login", search: { redirect: location.href, expired: "1" } });
      }
    }
    if (profile?.passwordResetRequired) {
      throw redirect({ to: "/force-password-change", search: { redirect: location.href } });
    }
  },
  component: StudentLayout
});

const dashboardRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  component: DashboardRouteView
});

const problemsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/problems",
  validateSearch: (search: Record<string, unknown>): ProblemSearch => {
    const next: ProblemSearch = {};
    if (typeof search.keyword === "string") next.keyword = search.keyword;
    if (typeof search.difficulty === "string") next.difficulty = search.difficulty;
    if (typeof search.tag === "string") next.tag = search.tag;
    const page = Number(search.page ?? 1);
    if (Number.isFinite(page) && page > 1) next.page = page;
    return next;
  },
  component: ProblemsRouteView
});

const problemDetailRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/problems/$problemId",
  validateSearch: (search: Record<string, unknown>): ProblemDetailSearch => {
    const next: ProblemDetailSearch = {};
    if (typeof search.contestId === "string") next.contestId = search.contestId;
    if (typeof search.contestRunId === "string") next.contestRunId = search.contestRunId;
    if (typeof search.contestProblemId === "string") next.contestProblemId = search.contestProblemId;
    return next;
  },
  component: ProblemDetailRouteView
});

const contestsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests",
  component: ContestsRouteView
});

const contestDetailRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests/$contestId",
  validateSearch: (search: Record<string, unknown>): ContestDetailSearch => {
    const next: ContestDetailSearch = {};
    if (typeof search.runId === "string") next.runId = search.runId;
    if (typeof search.tab === "string") next.tab = search.tab;
    return next;
  },
  component: ContestDetailRouteView
});

const contestProblemDetailRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests/$contestId/runs/$contestRunId/problems/$contestProblemId",
  component: ContestProblemDetailRouteView
});

const contestScoreboardRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests/$contestId/scoreboard",
  validateSearch: (search: Record<string, unknown>): ContestScoreboardSearch => {
    const next: ContestScoreboardSearch = {};
    if (typeof search.runId === "string") next.runId = search.runId;
    return next;
  },
  component: ContestScoreboardRouteView
});

const contestPostmortemRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests/$contestId/postmortem",
  validateSearch: (search: Record<string, unknown>): ContestPostmortemSearch => {
    const next: ContestPostmortemSearch = {};
    if (typeof search.runId === "string") next.runId = search.runId;
    return next;
  },
  component: ContestPostmortemRouteView
});

const submissionsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/submissions",
  validateSearch: (search: Record<string, unknown>): SubmissionSearch => {
    const next: SubmissionSearch = {};
    if (typeof search.status === "string") next.status = search.status;
    if (typeof search.problemId === "string") next.problemId = search.problemId;
    if (typeof search.contestId === "string") next.contestId = search.contestId;
    if (typeof search.contestRunId === "string") next.contestRunId = search.contestRunId;
    if (typeof search.contestProblemId === "string") next.contestProblemId = search.contestProblemId;
    if (typeof search.language === "string") next.language = search.language;
    if (typeof search.scope === "string") next.scope = search.scope;
    const page = Number(search.page ?? 1);
    if (Number.isFinite(page) && page > 1) next.page = page;
    return next;
  },
  component: SubmissionsRouteView
});

const aiChatRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/ai-chat",
  component: AiChatRouteView
});

const aiTutorRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/ai-tutor",
  component: AiChatRouteView
});

const profileRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/profile",
  component: ProfileRouteView
});

const routeTree = rootRoute.addChildren([
  loginRoute,
  forcePasswordChangeRoute,
  appRoute.addChildren([
    dashboardRoute,
    problemsRoute,
    problemDetailRoute,
    contestsRoute,
    contestDetailRoute,
    contestProblemDetailRoute,
    contestScoreboardRoute,
    contestPostmortemRoute,
    submissionsRoute,
    aiChatRoute,
    aiTutorRoute,
    profileRoute
  ])
]);

export const router = createRouter({
  routeTree,
  context: { auth: undefined! },
  defaultPreload: "intent"
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

function lazyRoute(loader: () => Promise<{ default: React.ComponentType }>) {
  const Component = React.lazy(loader);
  return function LazyRoute() {
    return (
      <React.Suspense fallback={<RouteLoading />}>
        <Component />
      </React.Suspense>
    );
  };
}

function RouteLoading() {
  const { t } = useI18n();
  return (
    <div className="grid min-h-[50vh] place-items-center text-sm text-[var(--oj-ink-muted)]">
      {t("common.loading")}
    </div>
  );
}

function StudentLayout() {
  const { t, locale, setLocale, localeOptions } = useI18n();
  const auth = useAuth();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const queryClient = useQueryClient();
  const invitationUnreadQuery = useQuery({
    queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"],
    queryFn: () => api.userNotificationUnreadCount("CONTEST_INVITATION"),
    enabled: auth.isAuthenticated && Boolean(auth.profile?.userId),
    refetchOnWindowFocus: true
  });
  const hasUnreadInvitations = (invitationUnreadQuery.data?.count ?? 0) > 0;

  React.useEffect(() => {
    if (!auth.isAuthenticated || !auth.profile?.userId) {
      return undefined;
    }
    const controller = new AbortController();
    void subscribeUserNotifications((event) => {
      void queryClient.invalidateQueries({ queryKey: ["student-notifications"] });
      if (event.type === "CONTEST_INVITATION") {
        void queryClient.invalidateQueries({ queryKey: ["student-notification-unread-count", "CONTEST_INVITATION"] });
        void queryClient.invalidateQueries({ queryKey: ["student-contest-invitations"] });
      }
      if (event.type === "STUDENT_POSTMORTEM_JOB_COMPLETED" || event.type === "STUDENT_POSTMORTEM_JOB_FAILED") {
        void queryClient.invalidateQueries({ queryKey: ["student-postmortem-notifications"] });
        void queryClient.invalidateQueries({ queryKey: ["student-postmortem-active-job"] });
        void queryClient.invalidateQueries({ queryKey: ["student-contest-postmortem-reports"] });
      }
    }, { signal: controller.signal }).catch(() => {
      // Authentication lifecycle handling is centralized by api-client. A durable
      // REST refetch remains the source of truth if this wake-up channel closes.
    });
    return () => controller.abort();
  }, [auth.isAuthenticated, auth.profile?.userId, queryClient]);

  const navItems = [
    { label: t("nav.home"), href: "/", icon: "home" as const, active: pathname === "/" },
    { label: t("nav.problems"), href: "/problems", icon: "problems" as const, active: pathname.startsWith("/problems") },
    {
      label: t("nav.contests"),
      href: "/contests",
      icon: "contests" as const,
      active: pathname.startsWith("/contests"),
      notificationDot: hasUnreadInvitations,
      notificationLabel: t("shell.unreadNotifications")
    },
    { label: t("nav.submissions"), href: "/submissions", icon: "submissions" as const, active: pathname.startsWith("/submissions") },
    { label: t("nav.aiChat"), href: "/ai-chat", icon: "ai" as const, active: pathname.startsWith("/ai") },
    { label: t("nav.profile"), href: "/profile", icon: "profile" as const, active: pathname.startsWith("/profile") }
  ];

  return (
    <AppShell
      title={t("common.appName")}
      subtitle={t("common.userProduct")}
      navLabel={t("shell.userNavLabel")}
      navItems={navItems}
      onNavigate={(href) => {
        void navigate({ to: href as "/" | "/problems" | "/contests" | "/submissions" | "/ai-chat" | "/profile" });
      }}
      topSlot={(
        <>
          <select
            value={locale}
            onChange={(event) => setLocale(event.target.value as typeof locale)}
            className="hidden h-9 rounded-xl border border-[var(--oj-border)] bg-white px-3 text-sm md:block"
            aria-label={t("locale.label")}
          >
            {localeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
          <Button asChild variant="ghost" size="sm">
            <Link to="/profile">
              <UserRound className="size-4" aria-hidden="true" />
              <span className="hidden sm:inline">{auth.profile?.displayName ?? t("shell.studentFallback")}</span>
            </Link>
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              await auth.logout();
              await navigate({ to: "/login" });
            }}
          >
            <LogOut className="size-4" aria-hidden="true" />
            <span className="hidden sm:inline">{t("common.logout")}</span>
          </Button>
        </>
      )}
      mobileSlot={(
        <select
          value={navItems.find((item) => item.active)?.href ?? "/"}
          onChange={(event) => {
            void navigate({
              to: event.target.value as "/" | "/problems" | "/contests" | "/submissions" | "/ai-chat" | "/profile"
            });
          }}
          className="h-9 max-w-[42vw] rounded-xl border border-[var(--oj-border)] bg-white px-2 text-sm lg:hidden"
          aria-label={t("shell.userNavLabel")}
        >
          {navItems.map((item) => <option key={item.href} value={item.href}>{item.label}</option>)}
        </select>
      )}
    >
      <Outlet />
    </AppShell>
  );
}

export function AppRouter() {
  return (
    <AuthProvider>
      <RouterBridge />
    </AuthProvider>
  );
}

function RouterBridge() {
  const auth = useAuth();
  const queryClient = useQueryClient();
  React.useEffect(() => {
    const redirectToLogin = (expired: boolean) => {
      const location = router.state.location;
      if (location.pathname === "/login" || location.pathname === "/force-password-change") return;
      const search = expired
        ? { expired: "1", redirect: location.href || location.pathname }
        : { redirect: location.href || location.pathname };
      void router.navigate({ to: "/login", search, replace: true });
    };
    const resetQueries = async () => {
      await queryClient.cancelQueries();
      queryClient.clear();
    };
    const handleAuthExpired = () => {
      void resetQueries().finally(() => redirectToLogin(true));
    };
    const handleAuthChanged = (event: Event) => {
      const reason = (event as CustomEvent<AuthChangedDetail>).detail?.reason;
      if (reason === "save" || reason === "clear") void resetQueries();
    };
    const handleStorage = (event: StorageEvent) => {
      if (!isAuthStorageKey(event.key)) return;
      if (!authStore.hasSession()) {
        void resetQueries().finally(() => redirectToLogin(false));
      }
    };
    window.addEventListener("aioj:auth-expired", handleAuthExpired);
    window.addEventListener("aioj:auth-changed", handleAuthChanged);
    window.addEventListener("storage", handleStorage);
    return () => {
      window.removeEventListener("aioj:auth-expired", handleAuthExpired);
      window.removeEventListener("aioj:auth-changed", handleAuthChanged);
      window.removeEventListener("storage", handleStorage);
    };
  }, [queryClient]);

  return <RouterProvider router={router} context={{ auth }} />;
}
