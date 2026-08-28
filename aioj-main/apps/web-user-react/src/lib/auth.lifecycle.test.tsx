import * as React from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, authStore, type TokenResponse, type UserProfileResponse } from "@aioj/api-client";
import { AuthProvider, useAuth } from "./auth";

const cachedTokens = tokens("cached-access", "cached-refresh", "u1");
const verifiedProfile: UserProfileResponse = {
  userId: "u1",
  account: "alice",
  displayName: "Verified Alice",
  email: "alice@example.com",
  roles: ["STUDENT"],
  passwordResetRequired: false
};

let latestAuth: ReturnType<typeof useAuth> | null = null;
let root: Root | null = null;
let container: HTMLDivElement | null = null;

function tokens(accessToken: string, refreshToken: string, userId: string): TokenResponse {
  return {
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresAt: "2099-01-01T00:00:00Z",
    userId,
    account: "alice",
    displayName: "Cached Alice",
    roles: ["STUDENT"],
    passwordResetRequired: false
  };
}

function apiResponse<T>(data: T, status = 200) {
  return new Response(JSON.stringify({
    code: 0,
    message: "ok",
    data,
    details: null,
    traceId: "trace-test",
    timestamp: "2026-01-01T00:00:00Z"
  }), { status, headers: { "Content-Type": "application/json" } });
}

function apiError(status: number, code: number, errorKey: string | null, message = "error") {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    details: null,
    traceId: "trace-test",
    timestamp: "2026-01-01T00:00:00Z",
    errorKey,
    errorParams: null
  }), { status, headers: { "Content-Type": "application/json" } });
}

function mockFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  return vi.spyOn(globalThis, "fetch").mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    return Promise.resolve(handler(url, init ?? {}));
  });
}

function Probe() {
  latestAuth = useAuth();
  return <div data-testid="profile">{latestAuth.profile?.displayName ?? "none"}</div>;
}

async function renderAuthProvider() {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => {
    root?.render(<AuthProvider><Probe /></AuthProvider>);
  });
}

describe("React auth provider lifecycle", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    latestAuth = null;
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount();
      });
    }
    root = null;
    container?.remove();
    container = null;
  });

  it("treats cached users as unverified until /me succeeds", async () => {
    authStore.save(cachedTokens);
    let meCalls = 0;
    mockFetch((url) => {
      if (url.endsWith("/api/v1/users/me")) {
        meCalls += 1;
        return apiResponse(verifiedProfile);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await renderAuthProvider();

    expect(latestAuth?.profile?.displayName).toBe("Cached Alice");
    await act(async () => {
      await latestAuth?.ensureProfile();
    });

    expect(meCalls).toBe(1);
    expect(latestAuth?.profile?.displayName).toBe("Verified Alice");
  });

  it("keeps tokens after /me service failures and retries instead of marking the profile fresh", async () => {
    authStore.save(cachedTokens);
    let meCalls = 0;
    mockFetch((url) => {
      if (url.endsWith("/api/v1/users/me")) {
        meCalls += 1;
        return meCalls === 1
          ? new Response("<html>bad gateway</html>", { status: 502, headers: { "Content-Type": "text/html" } })
          : apiResponse(verifiedProfile);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await renderAuthProvider();
    let failure: unknown;
    await act(async () => {
      try {
        await latestAuth?.ensureProfile();
      } catch (error) {
        failure = error;
      }
    });

    expect(failure).toBeInstanceOf(ApiError);
    expect(authStore.accessToken).toBe("cached-access");
    expect(authStore.refreshToken).toBe("cached-refresh");

    await act(async () => {
      await latestAuth?.ensureProfile();
    });

    expect(meCalls).toBe(2);
    expect(latestAuth?.profile?.displayName).toBe("Verified Alice");
  });

  it("clears local auth state when /me returns an authentication error", async () => {
    authStore.save(cachedTokens);
    mockFetch((url) => {
      if (url.endsWith("/api/v1/users/me")) {
        return apiError(401, 40100, "auth.required");
      }
      if (url.endsWith("/api/v1/auth/refresh")) {
        return apiError(401, 40100, "auth.refreshTokenInvalid");
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await renderAuthProvider();
    await act(async () => {
      await expect(latestAuth?.ensureProfile()).rejects.toBeInstanceOf(ApiError);
    });

    expect(authStore.accessToken).toBeNull();
    expect(authStore.refreshToken).toBeNull();
    expect(authStore.user).toBeNull();
  });

  it("restores the profile from /me when tokens exist but cached user is missing", async () => {
    authStore.save(cachedTokens);
    localStorage.removeItem("aioj.user");
    mockFetch((url) => {
      if (url.endsWith("/api/v1/users/me")) {
        return apiResponse(verifiedProfile);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await renderAuthProvider();

    expect(latestAuth?.profile).toBeNull();
    expect(latestAuth?.isAuthenticated).toBe(true);
    await act(async () => {
      await latestAuth?.ensureProfile();
    });

    expect(latestAuth?.profile?.displayName).toBe("Verified Alice");
  });
});
