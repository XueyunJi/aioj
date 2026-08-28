import { cn } from "@aioj/ui-react";
import { MarkdownView } from "../MarkdownView";

export function ProblemAwareMarkdown({ content, className }: { content: string; className?: string }) {
  return (
    <MarkdownView
      content={content}
      className={cn(
        "ai-message-markdown text-[var(--oj-ink)]",
        "[&_.wmde-markdown]:!bg-transparent [&_.wmde-markdown]:!text-[var(--oj-ink)]",
        "[&_.wmde-markdown_pre]:max-w-full [&_.wmde-markdown_pre]:overflow-x-auto",
        "[&_.wmde-markdown_table]:block [&_.wmde-markdown_table]:max-w-full [&_.wmde-markdown_table]:overflow-x-auto",
        "[&_.wmde-markdown_p:last-child]:mb-0",
        className
      )}
    />
  );
}
