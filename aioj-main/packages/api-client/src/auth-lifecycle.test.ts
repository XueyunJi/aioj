import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  ProblemDraftGenerateStreamError,
  SessionChangedError,
  api,
  authStore,
  isAuthenticationError,
  isPermissionError,
  subscribeUserNotifications,
  streamAi,
  type TokenResponse,
  type UserProfileResponse
} from "./index";

const oldTokens = tokens("old-access", "old-refresh", "u1");
const newTokens = tokens("new-access", "new-refresh", "u1");
const oldProfile: UserProfileResponse = {
  userId: "u1",
  account: "alice",
  displayName: "Alice",
  roles: ["STUDENT"],
  passwordResetRequired: false
};

function tokens(accessToken: string, refreshToken: string, userId: string): TokenResponse {
  return {
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresAt: "2099-01-01T00:00:00Z",
    userId,
    account: userId === "u1" ? "alice" : "bob",
    displayName: userId === "u1" ? "Alice" : "Bob",
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

describe("auth lifecycle in api-client", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("drops malformed cached users without clearing usable tokens", () => {
    localStorage.setItem("aioj.accessToken", "access");
    localStorage.setItem("aioj.refreshToken", "refresh");
    localStorage.setItem("aioj.user", JSON.stringify({ accessToken: "access", roles: "ADMIN" }));

    expect(authStore.user).toBeNull();
    expect(localStorage.getItem("aioj.user")).toBeNull();
    expect(authStore.accessToken).toBe("access");
    expect(authStore.refreshToken).toBe("refresh");
  });

  it("refreshes an expired access token once and replays concurrent requests", async () => {
    authStore.save(oldTokens);
    let refreshCalls = 0;
    mockFetch((url, init) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        refreshCalls += 1;
        expect(new Headers(init.headers).get("Authorization")).toBe("Bearer old-refresh");
        return apiResponse(newTokens);
      }
      if (url.endsWith("/api/v1/users/me")) {
        const authorization = new Headers(init.headers).get("Authorization");
        return authorization === "Bearer old-access"
          ? apiError(401, 40100, "auth.required")
          : apiResponse(oldProfile);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    const [first, second] = await Promise.all([api.me(), api.me()]);

    expect(first.displayName).toBe("Alice");
    expect(second.displayName).toBe("Alice");
    expect(refreshCalls).toBe(1);
    expect(authStore.accessToken).toBe("new-access");
  });

  it("does not expire the session if another tab already refreshed the token", async () => {
    authStore.save(oldTokens);
    mockFetch((url, init) => {
      if (url.endsWith("/api/v1/auth/refresh")) {
        authStore.save(newTokens, "refresh");
        return apiError(401, 40100, "auth.refreshTokenInvalid");
      }
      if (url.endsWith("/api/v1/users/me")) {
        const authorization = new Headers(init.headers).get("Authorization");
        return authorization === "Bearer old-access"
          ? apiError(401, 40100, "auth.required")
          : apiResponse(oldProfile);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(api.me()).resolves.toEqual(oldProfile);
    expect(authStore.accessToken).toBe("new-access");
    expect(authStore.refreshToken).toBe("new-refresh");
  });

  it("keeps authentication and permission failures separate", async () => {
    const authError = new ApiError(40100, "please login", null, null, "auth.required");
    const forbidden = new ApiError(40300, "forbidden", null, null, "auth.forbidden");

    expect(isAuthenticationError(authError)).toBe(true);
    expect(isPermissionError(authError)).toBe(false);
    expect(isAuthenticationError(forbidden)).toBe(false);
    expect(isPermissionError(forbidden)).toBe(true);
  });

  it("does not refresh or expire on forbidden responses", async () => {
    authStore.save(oldTokens);
    const fetchMock = mockFetch((url) => {
      if (url.includes("/api/v1/admin/roles")) {
        return apiError(403, 40300, "auth.forbidden");
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(api.roles()).rejects.toMatchObject({ code: 40300, errorKey: "auth.forbidden" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(authStore.accessToken).toBe("old-access");
  });

  it("rejects a response that completes after the user has logged out", async () => {
    authStore.save(oldTokens);
    let release!: (response: Response) => void;
    mockFetch(() => new Promise<Response>((resolve) => {
      release = resolve;
    }));

    const pending = api.me();
    authStore.clear();
    release(apiResponse(oldProfile));

    await expect(pending).rejects.toBeInstanceOf(SessionChangedError);
  });

  it("does not clear tokens for network or server failures", async () => {
    authStore.save(oldTokens);
    mockFetch((url) => {
      if (url.endsWith("/api/v1/users/me")) {
        return new Response("<html>bad gateway</html>", { status: 502, headers: { "Content-Type": "text/html" } });
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(api.me()).rejects.toMatchObject({ code: 50200 });
    expect(authStore.accessToken).toBe("old-access");
    expect(authStore.refreshToken).toBe("old-refresh");
  });

  it("handles stream auth errors like normal requests and leaves forbidden alone", async () => {
    authStore.save(oldTokens);
    const fetchMock = mockFetch((url) => {
      if (url.endsWith("/api/v1/ai/chat/stream")) {
        return apiError(403, 40300, "auth.forbidden");
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(streamAi({ message: "hello" }, () => undefined)).rejects.toMatchObject({ code: 40300 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(authStore.accessToken).toBe("old-access");
  });

  it("uses an Authorization header for notification SSE and never places the token in the URL", async () => {
    authStore.save(oldTokens);
    const controller = new AbortController();
    const events: Array<{ id: string; type: string; subjectId: string }> = [];
    const fetchMock = mockFetch((url, init) => {
      expect(url).toContain("/api/v1/notifications/stream");
      expect(url).not.toContain("old-access");
      expect(new Headers(init.headers).get("Authorization")).toBe("Bearer old-access");
      return new Response(
        "event: notification\n" +
        "data: {\"id\":207233818178818049,\"type\":\"CONTEST_INVITATION\",\"subjectType\":\"CONTEST_REGISTRATION\",\"subjectId\":207233818178818050}\n\n",
        { status: 200, headers: { "Content-Type": "text/event-stream" } }
      );
    });

    await subscribeUserNotifications((event) => {
      events.push({ id: event.id, type: event.type, subjectId: event.subjectId });
      controller.abort();
    }, { signal: controller.signal });

    expect(events).toEqual([{
      id: "207233818178818049",
      type: "CONTEST_INVITATION",
      subjectId: "207233818178818050"
    }]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not reconnect notification SSE after an authentication or permission failure", async () => {
    authStore.save(oldTokens);
    const fetchMock = mockFetch((url) => {
      if (url.endsWith("/api/v1/notifications/stream")) {
        return apiError(403, 40300, "auth.forbidden");
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(subscribeUserNotifications(() => undefined)).rejects.toMatchObject({ code: 40300 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(authStore.accessToken).toBe("old-access");
  });

  it("returns problem draft streams and dispatches heartbeat events", async () => {
    const events: string[] = [];
    mockFetch((url) => {
      if (url.endsWith("/api/v1/ai/problem-drafts/generate/stream")) {
        const draft = {
          id: "42",
          status: "PENDING_REVIEW",
          title: "Stream Draft",
          difficulty: "MEDIUM",
          statement: "Solve it.",
          notes: "notes",
          standardSolutionLanguage: "cpp",
          standardSolutionCode: "int main(){return 0;}",
          testcaseGeneratorPython: "print('ok')",
          generationPlan: "plan",
          tags: ["线段树"],
          validationStatus: "VALID",
          validationErrors: [],
          testCases: [],
          timeLimitMillis: 1000,
          memoryLimitKb: 262144,
          importedProblemId: null,
          model: "mock",
          promptTokens: 1,
          completionTokens: 2,
          createdAt: "2026-06-30T00:00:00Z",
          verificationStatus: null,
          verificationReport: null,
          repairAttemptCount: null,
          lastRepairReason: null
        };
        return new Response([
          "event: meta\n",
          "data: {\"heartbeatIntervalMillis\":1000}\n\n",
          "event: heartbeat\n",
          "data: {\"running\":true,\"elapsedMillis\":1000}\n\n",
          "event: draft\n",
          `data: ${JSON.stringify(draft)}\n\n`,
          "event: done\n",
          "data: [DONE]\n\n"
        ].join(""), { status: 200, headers: { "Content-Type": "text/event-stream" } });
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    const draft = await api.generateDraftStream({ topic: "线段树,排序", cfRating: 2000 }, {
      onEvent: (event) => events.push(event)
    });

    expect(draft.title).toBe("Stream Draft");
    expect(events).toContain("heartbeat");
    expect(events).toContain("draft");
  });

  it("throws structured problem draft stream errors with the server message", async () => {
    const events: string[] = [];
    mockFetch((url) => {
      if (url.endsWith("/api/v1/ai/problem-drafts/generate/stream")) {
        return new Response([
          "event: heartbeat\n",
          "data: {\"running\":true,\"elapsedMillis\":1000}\n\n",
          "event: error\n",
          "data: {\"code\":50000,\"message\":\"AI provider call timed out\",\"errorKey\":\"system.internal\",\"elapsedMillis\":1234}\n\n",
          "event: done\n",
          "data: [DONE]\n\n"
        ].join(""), { status: 200, headers: { "Content-Type": "text/event-stream" } });
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    await expect(api.generateDraftStream({ topic: "线段树,排序", cfRating: 2000 }, {
      onEvent: (event) => events.push(event)
    })).rejects.toMatchObject({
      name: "ProblemDraftGenerateStreamError",
      message: "AI provider call timed out",
      code: 50000,
      errorKey: "system.internal",
      elapsedMillis: 1234
    });
    await expect(api.generateDraftStream({ topic: "线段树,排序", cfRating: 2000 }))
      .rejects.toBeInstanceOf(ProblemDraftGenerateStreamError);
    expect(events).toEqual(["heartbeat", "error"]);
  });
});
