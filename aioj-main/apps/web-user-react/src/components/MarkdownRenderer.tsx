import * as React from "react";
import MarkdownPreview from "@uiw/react-markdown-preview";
import "@uiw/react-markdown-preview/markdown.css";
import "katex/dist/katex.min.css";
import rehypeKatex from "rehype-katex";
import remarkMath from "remark-math";
import { Check, Copy } from "lucide-react";
import { cn } from "@aioj/ui-react";
import { copyToClipboard } from "../lib/clipboard";

export default function MarkdownRenderer({ content, className }: { content: string; className?: string }) {
  return (
    <div className={cn("min-w-0", className)}>
      <MarkdownPreview
        source={content}
        remarkPlugins={[remarkMath]}
        rehypePlugins={[rehypeKatex]}
        skipHtml
        disableCopy
        components={{ pre: CodePre }}
        wrapperElement={{ "data-color-mode": "light" }}
        className="max-w-none bg-transparent"
      />
    </div>
  );
}

function CodePre(rawProps: React.HTMLAttributes<HTMLPreElement> & { node?: unknown }) {
  const { children, className, ...props } = rawProps;
  delete props.node;
  const [copied, setCopied] = React.useState(false);
  const timerRef = React.useRef<number | null>(null);
  const codeRef = React.useRef<HTMLElement | null>(null);
  const { codeClassName, codeText } = extractCode(children);

  React.useEffect(() => {
    return () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    };
  }, []);

  async function copyCode(event: React.MouseEvent<HTMLButtonElement>) {
    event.preventDefault();
    event.stopPropagation();
    if (!codeText) return;
    try {
      await copyToClipboard(codeText, codeRef.current);
      setCopied(true);
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
      timerRef.current = window.setTimeout(() => setCopied(false), 1200);
    } catch {
      selectCodeElement(codeRef.current);
    }
  }

  return (
    <div className="group relative">
      {codeText ? (
        <button
          type="button"
          className="absolute right-2 top-2 z-10 grid size-8 place-items-center rounded-md border border-slate-200 bg-white/90 text-slate-600 shadow-sm transition hover:bg-white hover:text-slate-950 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
          aria-label={copied ? "Copied" : "Copy code"}
          onClick={copyCode}
        >
          {copied ? <Check className="size-4" aria-hidden="true" /> : <Copy className="size-4" aria-hidden="true" />}
        </button>
      ) : null}
      <pre {...props} className={cn("relative", codeText && "pr-12", className)}>
        {codeText ? <code ref={codeRef} className={codeClassName}>{codeText}</code> : children}
      </pre>
    </div>
  );
}

function extractCode(children: React.ReactNode) {
  const child = React.Children.toArray(children)[0];
  if (React.isValidElement<{ className?: string; children?: React.ReactNode }>(child) && child.type === "code") {
    return {
      codeClassName: child.props.className,
      codeText: textFromChildren(child.props.children)
    };
  }
  return {
    codeClassName: undefined,
    codeText: textFromChildren(children)
  };
}

function textFromChildren(children: React.ReactNode): string {
  return React.Children.toArray(children).map((child) => {
    if (typeof child === "string" || typeof child === "number") return String(child);
    if (React.isValidElement<{ children?: React.ReactNode }>(child)) return textFromChildren(child.props.children);
    return "";
  }).join("");
}

function selectCodeElement(codeElement: HTMLElement | null) {
  if (!codeElement) return;
  const selection = window.getSelection();
  const range = document.createRange();
  range.selectNodeContents(codeElement);
  selection?.removeAllRanges();
  selection?.addRange(range);
}
