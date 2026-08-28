import * as React from "react";
import { cn } from "@aioj/ui-react";

const MarkdownRenderer = React.lazy(() => import("./MarkdownRenderer"));

/**
 * Thin lazy shell so the heavy markdown/katex chunk only loads when markdown is
 * actually rendered, keeping it out of the eager entry chunk.
 */
export function MarkdownView({ content, className }: { content: string; className?: string }) {
  return (
    <React.Suspense fallback={<div className={cn("min-w-0 animate-pulse rounded-lg bg-[var(--oj-surface-muted)] p-3", className)} aria-hidden="true" />}>
      <MarkdownRenderer content={content} className={className} />
    </React.Suspense>
  );
}
