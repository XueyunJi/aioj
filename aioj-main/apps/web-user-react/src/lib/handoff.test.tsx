import * as React from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, authStore, type TokenResponse, type UserProfileResponse } from "@aioj/api-client";
import {
  exchangeTutorHandoff,
  ticketFromFragment,
  TutorHandoffView,
  validServerNextPath
} from "../views/TutorHandoffView";

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

const profile: UserProfileResponse = {
  userId: "2",
  account: "student2",
  displayName: "Student 2",
  roles: ["STUDENT"],
  passwordResetRequired: false
};

let root: Root | null = null;
let container: HTMLDivElement | null = null;

beforeEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/auth/tutor-handoff");
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(async () => {
  if (root) await act(async () => root?.unmount());
  root = null;
  container?.remove();
  container = null;
});

async function renderView() {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => root?.render(<TutorHandoffView />));
}

describe("Tutor handoff URL boundary", () => {
  it("reads only the ticket from the fragment", () => {
    expect(ticketFromFragment("#ticket=opaque&next=%2Fproblems%2F999")).toBe("opaque");
    expect(ticketFromFragment("#next=%2Fproblems%2F999")).toBeNull();
  });

  it("accepts only a server-returned problem path", () => {
    expect(validServerNextPath("/problems/123")).toBe(true);
    expect(validServerNextPath("https://evil.example/problems/123")).toBe(false);
    expect(validServerNextPath("//evil.example/problems/123")).toBe(false);
    expect(validServerNextPath("/problems/0")).toBe(false);
  });

  it("saves exchanged tokens, verifies the account, and uses only the server nextPath", async () => {
    vi.spyOn(api, "exchangeHandoff").mockResolvedValue({ tokens, nextPath: "/problems/123" });
    vi.spyOn(api, "me").mockResolvedValue(profile);
    const redirect = vi.fn();

    await exchangeTutorHandoff("opaque-ticket", redirect);

    expect(api.exchangeHandoff).toHaveBeenCalledWith("opaque-ticket");
    expect(api.me).toHaveBeenCalledOnce();
    expect(authStore.accessToken).toBe("new-access");
    expect(authStore.refreshToken).toBe("new-refresh");
    expect(redirect).toHaveBeenCalledWith("/problems/123");
  });

  it("clears all auth state when account verification fails", async () => {
    vi.spyOn(api, "exchangeHandoff").mockResolvedValue({ tokens, nextPath: "/problems/123" });
    vi.spyOn(api, "me").mockRejectedValue(new Error("verification failed"));

    await expect(exchangeTutorHandoff("opaque-ticket", vi.fn())).rejects.toThrow("verification failed");

    expect(authStore.accessToken).toBeNull();
    expect(authStore.refreshToken).toBeNull();
    expect(authStore.user).toBeNull();
  });

  it("rejects an invalid server nextPath before saving tokens", async () => {
    vi.spyOn(api, "exchangeHandoff").mockResolvedValue({ tokens, nextPath: "https://evil.example/" });
    vi.spyOn(api, "me");

    await expect(exchangeTutorHandoff("opaque-ticket", vi.fn())).rejects.toThrow("Invalid server nextPath");

    expect(api.me).not.toHaveBeenCalled();
    expect(authStore.hasSession()).toBe(false);
  });

  it("clears the fragment before starting the exchange", async () => {
    window.history.replaceState(null, "", "/auth/tutor-handoff#ticket=opaque-ticket&nextPath=%2Fproblems%2F999");
    let fragmentAtExchange = "not-called";
    vi.spyOn(api, "exchangeHandoff").mockImplementation(() => {
      fragmentAtExchange = window.location.hash;
      return new Promise(() => undefined);
    });

    await renderView();

    expect(fragmentAtExchange).toBe("");
    expect(window.location.hash).toBe("");
    expect(api.exchangeHandoff).toHaveBeenCalledOnce();
  });

  it("does not exchange when refreshed after the fragment was cleared", async () => {
    authStore.save(tokens);
    vi.spyOn(api, "exchangeHandoff");

    await renderView();

    expect(api.exchangeHandoff).not.toHaveBeenCalled();
    expect(authStore.hasSession()).toBe(false);
    expect(container?.textContent).toContain("切换链接无效或已过期");
  });
});
