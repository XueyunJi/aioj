import * as React from "react";
import { api, authStore, isAuthenticationError, isAuthStorageKey, type AuthChangedDetail, type Role, type TokenResponse, type UserProfileResponse } from "@aioj/api-client";

interface AuthContextValue {
  profile: UserProfileResponse | null;
  loading: boolean;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isTeacher: boolean;
  canUseAdminApp: boolean;
  displayName: string;
  hasRole: (role: Role) => boolean;
  hasAnyRole: (roles: readonly Role[]) => boolean;
  login: (account: string, password: string) => Promise<TokenResponse>;
  register: (payload: { account: string; password: string; displayName: string; email?: string }) => Promise<TokenResponse>;
  ensureProfile: (force?: boolean) => Promise<UserProfileResponse>;
  changePassword: (payload: { currentPassword: string; newPassword: string }) => Promise<TokenResponse>;
  logout: () => Promise<void>;
  clearLocal: () => void;
}

const AuthContext = React.createContext<AuthContextValue | null>(null);
const PROFILE_STALE_MS = 30_000;

function profileFromTokens(tokens: TokenResponse, email?: string): UserProfileResponse {
  return {
    userId: tokens.userId,
    account: tokens.account,
    displayName: tokens.displayName,
    email,
    roles: tokens.roles,
    passwordResetRequired: tokens.passwordResetRequired
  };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [profile, setProfile] = React.useState<UserProfileResponse | null>(() => {
    const user = authStore.user;
    return user ? profileFromTokens(user) : null;
  });
  const [profileFetchedAt, setProfileFetchedAt] = React.useState<number | null>(null);
  const [hasAccessToken, setHasAccessToken] = React.useState(() => Boolean(authStore.accessToken));
  const [loading, setLoading] = React.useState(false);

  const syncFromStore = React.useCallback((verified = false) => {
    const user = authStore.user;
    setHasAccessToken(Boolean(authStore.accessToken));
    if (user) {
      setProfile(profileFromTokens(user));
      setProfileFetchedAt(verified ? Date.now() : null);
      return;
    }
    setProfile(null);
    setProfileFetchedAt(null);
  }, []);

  const clearLocal = React.useCallback(() => {
    authStore.clear();
    setHasAccessToken(false);
    setProfile(null);
    setProfileFetchedAt(null);
  }, []);

  const ensureProfile = React.useCallback(async (force = false) => {
    if (!authStore.accessToken) {
      clearLocal();
      throw new Error("Login required");
    }
    const fresh = profile && profileFetchedAt && Date.now() - profileFetchedAt < PROFILE_STALE_MS;
    if (fresh && !force) return profile;
    setLoading(true);
    try {
      const nextProfile = await api.me();
      setProfile(nextProfile);
      setProfileFetchedAt(Date.now());
      return nextProfile;
    } catch (error) {
      setProfileFetchedAt(null);
      if (isAuthenticationError(error)) {
        clearLocal();
      }
      throw error;
    } finally {
      setLoading(false);
    }
  }, [clearLocal, profile, profileFetchedAt]);

  const login = React.useCallback(async (account: string, password: string) => {
    const tokens = await api.login(account, password);
    authStore.save(tokens);
    setHasAccessToken(true);
    setProfile(profileFromTokens(tokens));
    setProfileFetchedAt(Date.now());
    return tokens;
  }, []);

  const register = React.useCallback(async (payload: { account: string; password: string; displayName: string; email?: string }) => {
    authStore.clear();
    const tokens = await api.register(payload);
    authStore.save(tokens);
    try {
      await api.logout();
    } catch {
      // Public registration intentionally returns to login even if logout is already invalid.
    }
    authStore.clear();
    setHasAccessToken(false);
    setProfile(null);
    setProfileFetchedAt(null);
    return tokens;
  }, []);

  const changePassword = React.useCallback(async (payload: { currentPassword: string; newPassword: string }) => {
    const tokens = await api.changePassword(payload);
    authStore.save(tokens, "refresh");
    setHasAccessToken(true);
    setProfile(profileFromTokens(tokens));
    setProfileFetchedAt(Date.now());
    await ensureProfile(true);
    return tokens;
  }, [ensureProfile]);

  const logout = React.useCallback(async () => {
    void api.logout().catch(() => undefined);
    clearLocal();
  }, [clearLocal]);

  React.useEffect(() => {
    const handleAuthChanged = (event: Event) => {
      const reason = (event as CustomEvent<AuthChangedDetail>).detail?.reason;
      if (reason === "refresh") {
        setHasAccessToken(Boolean(authStore.accessToken));
        syncFromStore(false);
        return;
      }
      syncFromStore(false);
    };
    const handleAuthExpired = () => clearLocal();
    const handleStorage = (event: StorageEvent) => {
      if (isAuthStorageKey(event.key)) syncFromStore(false);
    };
    window.addEventListener("aioj:auth-changed", handleAuthChanged);
    window.addEventListener("aioj:auth-expired", handleAuthExpired);
    window.addEventListener("storage", handleStorage);
    return () => {
      window.removeEventListener("aioj:auth-changed", handleAuthChanged);
      window.removeEventListener("aioj:auth-expired", handleAuthExpired);
      window.removeEventListener("storage", handleStorage);
    };
  }, [clearLocal, syncFromStore]);

  const value = React.useMemo<AuthContextValue>(() => {
    const roles = profile?.roles ?? [];
    const hasRole = (role: Role) => roles.includes(role);
    const hasAnyRole = (allowedRoles: readonly Role[]) => allowedRoles.some((role) => hasRole(role));
    return {
      profile,
      loading,
      isAuthenticated: hasAccessToken,
      isAdmin: hasRole("ADMIN"),
      isTeacher: hasRole("TEACHER"),
      canUseAdminApp: hasAnyRole(["TEACHER", "ADMIN"]),
      displayName: profile?.displayName || "",
      hasRole,
      hasAnyRole,
      login,
      register,
      ensureProfile,
      changePassword,
      logout,
      clearLocal
    };
  }, [changePassword, clearLocal, ensureProfile, hasAccessToken, loading, login, logout, profile, register]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = React.useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
