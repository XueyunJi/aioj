import * as React from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "./AppShell";

let container: HTMLDivElement | null = null;
let root: Root | null = null;

describe("AppShell navigation", () => {
  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
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

  it("uses client navigation for an unmodified primary click", async () => {
    const onNavigate = vi.fn();
    await act(async () => {
      root?.render(
        <AppShell
          title="AI-OJ"
          subtitle="Student"
          navItems={[{ label: "Problems", href: "/problems", icon: "problems" }]}
          onNavigate={onNavigate}
        >
          <main>Content</main>
        </AppShell>
      );
    });

    const link = container?.querySelector<HTMLAnchorElement>('a[href="/problems"]');
    expect(link).not.toBeNull();
    const click = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 });

    await act(async () => {
      link?.dispatchEvent(click);
    });

    expect(click.defaultPrevented).toBe(true);
    expect(onNavigate).toHaveBeenCalledOnce();
    expect(onNavigate).toHaveBeenCalledWith("/problems");
  });

  it("preserves native link behavior for modified clicks", async () => {
    const onNavigate = vi.fn();
    await act(async () => {
      root?.render(
        <AppShell
          title="AI-OJ"
          subtitle="Student"
          navItems={[{ label: "Problems", href: "#problems", icon: "problems" }]}
          onNavigate={onNavigate}
        >
          <main>Content</main>
        </AppShell>
      );
    });

    const link = container?.querySelector<HTMLAnchorElement>('a[href="#problems"]');
    expect(link).not.toBeNull();
    const click = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0, ctrlKey: true });

    await act(async () => {
      link?.dispatchEvent(click);
    });

    expect(click.defaultPrevented).toBe(false);
    expect(onNavigate).not.toHaveBeenCalled();
  });
});
