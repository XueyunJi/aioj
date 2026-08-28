import * as React from "react";
import {
  createRootRouteWithContext,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  RouterProvider
} from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { authStore, isAuthenticationError, isAuthStorageKey, type AuthChangedDetail, type Role, type UserProfileResponse } from "@aioj/api-client";
import { AdminShell } from "./components/AdminShell";
import { AuthProvider, useAuth } from "./lib/auth";
import { useI18n } from "./lib/i18n";

type RouterContext = {
  auth: ReturnType<typeof useAuth>;
};

type LoginSearch = { redirect?: string; expired?: string; registered?: string; account?: string };
type AiDraftsSearch = { draftId?: string; tab?: "generate" | "jobs" | "box" };

const ADMIN_APP_ROLES: readonly Role[] = ["TEACHER", "ADMIN"];
const ADMIN_ONLY_ROLES: readonly Role[] = ["ADMIN"];

const LoginRouteView = lazyRoute(() => import("./views/AuthViews").then((module) => ({ default: module.LoginView })));
const ForcePasswordChangeRouteView = lazyRoute(() => import("./views/ForcePasswordChangeView").then((module) => ({ default: module.ForcePasswordChangeView })));
const BlockedRouteView = lazyRoute(() => import("./views/BlockedView").then((module) => ({ default: module.BlockedView })));
const DashboardRouteView = lazyRoute(() => import("./views/DashboardView").then((module) => ({ default: module.DashboardView })));
const UsersRouteView = lazyRoute(() => import("./views/UsersView").then((module) => ({ default: module.UsersView })));
const RolesRouteView = lazyRoute(() => import("./views/RolesView").then((module) => ({ default: module.RolesView })));
const ClassesRouteView = lazyRoute(() => import("./views/ClassesView").then((module) => ({ default: module.ClassesView })));
const ProblemsRouteView = lazyRoute(() => import("./views/ProblemsView").then((module) => ({ default: module.ProblemsView })));
const ContestsRouteView = lazyRoute(() => import("./views/ContestsView").then((module) => ({ default: module.ContestsView })));
const AiDraftsRouteView = lazyRoute(() => import("./views/AiDraftsView").then((module) => ({ default: module.AiDraftsView })));
const AiModelConfigsRouteView = lazyRoute(() => import("./views/AiModelConfigsView").then((module) => ({ default: module.AiModelConfigsView })));
const OperationAuditRouteView = lazyRoute(() => import("./views/OperationAuditView").then((module) => ({ default: module.OperationAuditView })));

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
    if (typeof search.registered === "string") next.registered = search.registered;
    if (typeof search.account === "string") next.account = search.account;
    return next;
  },
  beforeLoad: async ({ context }) => {
    if (!authStore.accessToken) return;
    let profile;
    try {
      profile = await context.auth.ensureProfile();
    } catch (error) {
      if (isAuthenticationError(error)) context.auth.clearLocal();
      return;
    }
    if (profile.passwordResetRequired) throw redirect({ to: "/force-password-change" });
    throw redirect({ to: hasAnyRole(profile, ADMIN_APP_ROLES) ? "/dashboard" : "/blocked" });
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
    if (!profile?.passwordResetRequired) {
      throw redirect({ to: profile && hasAnyRole(profile, ADMIN_APP_ROLES) ? "/dashboard" : "/blocked" });
    }
  },
  component: ForcePasswordChangeRouteView
});

const blockedRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/blocked",
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
  component: BlockedRouteView
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
      return;
    }
    if (profile.passwordResetRequired) {
      throw redirect({ to: "/force-password-change", search: { redirect: location.href } });
    }
    if (!hasAnyRole(profile, ADMIN_APP_ROLES)) throw redirect({ to: "/blocked", replace: true });
  },
  component: AdminShell
});

const indexRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/dashboard" });
  }
});

const dashboardRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/dashboard",
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: DashboardRouteView
});

const usersRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/users",
  beforeLoad: requireRoles(ADMIN_ONLY_ROLES),
  component: UsersRouteView
});

const rolesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/roles",
  beforeLoad: requireRoles(ADMIN_ONLY_ROLES),
  component: RolesRouteView
});

const classesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/classes",
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: ClassesRouteView
});

const problemsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/problems",
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: ProblemsRouteView
});

const contestsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/contests",
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: ContestsRouteView
});

const aiDraftsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/ai-drafts",
  validateSearch: (search: Record<string, unknown>): AiDraftsSearch => {
    const next: AiDraftsSearch = {};
    if (typeof search.draftId === "string") next.draftId = search.draftId;
    if (search.tab === "generate" || search.tab === "jobs" || search.tab === "box") next.tab = search.tab;
    return next;
  },
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: AiDraftsRouteView
});

const aiDraftJobsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/ai-draft-jobs",
  beforeLoad: () => {
    throw redirect({ to: "/ai-drafts", search: { tab: "jobs" } });
  }
});

const aiModelConfigsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/ai-model-configs",
  beforeLoad: requireRoles(ADMIN_ONLY_ROLES),
  component: AiModelConfigsRouteView
});

const operationsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/operations",
  beforeLoad: requireRoles(ADMIN_APP_ROLES),
  component: OperationAuditRouteView
});

const routeTree = rootRoute.addChildren([
  loginRoute,
  forcePasswordChangeRoute,
  blockedRoute,
  appRoute.addChildren([indexRoute, dashboardRoute, usersRoute, rolesRoute, classesRoute, problemsRoute, contestsRoute, aiDraftsRoute, aiDraftJobsRoute, aiModelConfigsRoute, operationsRoute])
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

function hasAnyRole(profile: UserProfileResponse, roles: readonly Role[]) {
  return roles.some((role) => profile.roles.includes(role));
}

function requireRoles(roles: readonly Role[]) {
  return async ({ context }: { context: RouterContext }) => {
    let profile;
    try {
      profile = await context.auth.ensureProfile();
    } catch (error) {
      if (isAuthenticationError(error)) throw redirect({ to: "/login", search: { expired: "1" } });
      return;
    }
    if (!hasAnyRole(profile, roles)) throw redirect({ to: "/blocked", replace: true });
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
