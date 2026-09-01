import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, authStore, type TokenResponse } from "./index";

const tokens: TokenResponse = {
  accessToken: "new-access",
  refreshToken: "new-refresh",
  tokenType: "Bearer",
  expiresAt: "2099-01-01T00:00:00Z",
  userId: "2",
  account: "student2",
  displayName: "Student 2",
  roles: ["STUDENT"],
  passwordResetRequired: false
};

describe("handoff API", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("exchanges anonymously even when another AIOJ account is logged in", async () => {
    authStore.save({ ...tokens, accessToken: "old-access", refreshToken: "old-refresh", userId: "1" });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: "ok",
      data: { tokens, nextPath: "/problems/123" }
    }), { status: 200, headers: { "Content-Type": "application/json" } }));

    const result = await api.exchangeHandoff("opaque-ticket");

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain("/api/v1/auth/handoff/exchange");
    expect(new Headers(init?.headers).has("Authorization")).toBe(false);
    expect(init?.body).toBe(JSON.stringify({ ticket: "opaque-ticket" }));
    expect(result.nextPath).toBe("/problems/123");
  });
});
