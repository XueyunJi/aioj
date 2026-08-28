import * as React from "react";
import {
  BookOpen,
  GraduationCap,
  LayoutDashboard,
  MessageCircle,
  Send,
  Settings,
  ShieldCheck,
  Trophy
} from "lucide-react";
import { cn } from "../lib/cn";
import { Badge } from "../primitives/Badge";

export interface AppShellNavItem {
  label: string;
  active?: boolean;
  href?: string;
  icon?: "home" | "problems" | "ai" | "admin" | "submissions" | "profile" | "contests";
  notificationDot?: boolean;
  notificationLabel?: string;
}

const icons = {
  home: LayoutDashboard,
  problems: GraduationCap,
  ai: MessageCircle,
  admin: ShieldCheck,
  submissions: Send,
  profile: Settings,
  contests: Trophy
};

export interface AppShellProps {
  title: string;
  subtitle: string;
  navItems: AppShellNavItem[];
  children: React.ReactNode;
  badge?: string;
  topSlot?: React.ReactNode;
  mobileSlot?: React.ReactNode;
  navLabel?: string;
  onNavigate?: (href: string) => void;
}

export function AppShell({ title, subtitle, navItems, children, badge, topSlot, mobileSlot, navLabel, onNavigate }: AppShellProps) {
  return (
    <div className="min-h-dvh bg-[var(--oj-app-bg)] text-[var(--oj-ink)]">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 border-r border-[var(--oj-border)] bg-white px-4 py-5 lg:block">
        <div className="flex items-center gap-3 px-2">
          <div className="grid size-11 place-items-center rounded-2xl bg-[var(--oj-primary)] text-white">
            <BookOpen className="size-5" aria-hidden="true" />
          </div>
          <div>
            <div className="text-sm font-semibold">{title}</div>
            <div className="text-xs text-[var(--oj-ink-muted)]">{subtitle}</div>
          </div>
        </div>
        <nav className="mt-8 space-y-1" aria-label={navLabel}>
          {navItems.map((item) => {
            const Icon = item.icon ? icons[item.icon] : LayoutDashboard;
            return (
              <a
                key={item.label}
                className={cn(
                  "relative flex h-10 items-center gap-3 rounded-xl px-3 pr-8 text-sm font-medium text-[var(--oj-ink-muted)] outline-none transition-colors hover:bg-[var(--oj-surface-muted)] hover:text-[var(--oj-ink)] focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]",
                  item.active && "bg-blue-50 text-[var(--oj-primary)]"
                )}
                href={item.href ?? "#"}
                onClick={(event) => {
                  if (
                    !onNavigate
                    || !item.href
                    || event.defaultPrevented
                    || event.button !== 0
                    || event.metaKey
                    || event.ctrlKey
                    || event.shiftKey
                    || event.altKey
                  ) {
                    return;
                  }
                  event.preventDefault();
                  onNavigate(item.href);
                }}
                aria-current={item.active ? "page" : undefined}
                aria-label={item.notificationDot && item.notificationLabel
                  ? `${item.label}，${item.notificationLabel}`
                  : undefined}
              >
                <Icon className="size-4" aria-hidden="true" />
                {item.label}
                {item.notificationDot ? (
                  <span className="absolute right-3 top-2 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />
                ) : null}
              </a>
            );
          })}
        </nav>
        {badge ? <Badge tone="blue" className="absolute bottom-5 left-6">{badge}</Badge> : null}
      </aside>
      <main className="min-h-dvh lg:pl-64">
        <header className="sticky top-0 z-20 border-b border-[var(--oj-border-soft)] bg-[var(--oj-app-bg)]/95 px-4 py-3 backdrop-blur supports-[backdrop-filter]:bg-[var(--oj-app-bg)]/80 lg:px-8">
          <div className="mx-auto flex max-w-[1500px] items-center justify-between gap-3">
            <div className="min-w-0 lg:hidden">
              <div className="text-sm font-semibold">{title}</div>
              <div className="truncate text-xs text-[var(--oj-ink-muted)]">{subtitle}</div>
            </div>
            <div className="hidden min-w-0 lg:block" />
            <div className="flex min-w-0 items-center gap-2">{mobileSlot}{topSlot}</div>
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}
