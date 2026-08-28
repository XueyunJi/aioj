import * as React from "react";
import { cn } from "@aioj/ui-react";
import { SkeletonBlock } from "./Common";

const MonacoEditor = React.lazy(() => import("@monaco-editor/react"));

type Disposable = { dispose: () => void };
type FocusAwareEditor = {
  onDidFocusEditorText: (listener: () => void) => Disposable;
  onDidBlurEditorText: (listener: () => void) => Disposable;
};

function monacoLanguage(language: string) {
  const normalized = language.toLowerCase();
  if (normalized === "python") return "python";
  if (normalized === "java") return "java";
  return "cpp";
}

function useDesktopEditor() {
  const [desktop, setDesktop] = React.useState(() => (
    typeof window === "undefined" ? true : window.matchMedia("(min-width: 1280px)").matches
  ));

  React.useEffect(() => {
    if (typeof window === "undefined") return undefined;
    const query = window.matchMedia("(min-width: 1280px)");
    const update = () => setDesktop(query.matches);
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);

  return desktop;
}

export function CodeEditor({
  value,
  language,
  onChange,
  className
}: {
  value: string;
  language: string;
  onChange: (value: string) => void;
  className?: string;
}) {
  const desktop = useDesktopEditor();
  const [editorFocused, setEditorFocused] = React.useState(false);
  const disposablesRef = React.useRef<Disposable[]>([]);

  const disposeEditorListeners = React.useCallback(() => {
    for (const disposable of disposablesRef.current) {
      disposable.dispose();
    }
    disposablesRef.current = [];
  }, []);

  React.useEffect(() => disposeEditorListeners, [disposeEditorListeners]);

  const handleEditorMount = React.useCallback((editor: FocusAwareEditor) => {
    disposeEditorListeners();
    disposablesRef.current = [
      editor.onDidFocusEditorText(() => setEditorFocused(true)),
      editor.onDidBlurEditorText(() => setEditorFocused(false))
    ];
  }, [disposeEditorListeners]);

  function handleWheelCapture(event: React.WheelEvent<HTMLDivElement>) {
    if (
      editorFocused ||
      event.defaultPrevented ||
      event.ctrlKey ||
      event.metaKey ||
      event.altKey ||
      event.shiftKey ||
      !event.deltaY
    ) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    window.scrollBy({ top: event.deltaY, left: 0, behavior: "auto" });
  }

  return (
    <div
      className={cn("min-h-0 overflow-hidden rounded-xl border border-[var(--oj-border)] bg-white", className)}
      onWheelCapture={handleWheelCapture}
    >
      <React.Suspense fallback={<SkeletonBlock className="h-full min-h-80 rounded-none" />}>
        <MonacoEditor
          height="100%"
          width="100%"
          language={monacoLanguage(language)}
          value={value}
          theme="light"
          loading={<SkeletonBlock className="h-full min-h-80 rounded-none" />}
          options={{
            automaticLayout: true,
            bracketPairColorization: { enabled: true },
            cursorSmoothCaretAnimation: "off",
            folding: true,
            fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
            fontSize: 13,
            formatOnPaste: false,
            formatOnType: false,
            guides: { bracketPairs: true, indentation: true },
            insertSpaces: true,
            lineHeight: 22,
            lineNumbers: "on",
            minimap: { enabled: desktop },
            overviewRulerBorder: false,
            padding: { top: 12, bottom: 12 },
            renderLineHighlight: "line",
            renderWhitespace: "boundary",
            scrollBeyondLastLine: false,
            smoothScrolling: true,
            tabSize: 4,
            wordWrap: "on"
          }}
          onMount={handleEditorMount}
          onChange={(nextValue) => onChange(nextValue ?? "")}
        />
      </React.Suspense>
    </div>
  );
}
