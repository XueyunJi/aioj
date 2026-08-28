import * as Dialog from "@radix-ui/react-dialog";
import { Bot, X } from "lucide-react";
import { Button } from "@aioj/ui-react";
import type { AiContestContextPayload, ProblemResponse } from "@aioj/api-client";
import { AiTutorWorkspace } from "./AiTutorWorkspace";
import { useI18n } from "../lib/i18n";

export function AiAssistantDialog({
  problem,
  code,
  language,
  contestContext = null
}: {
  problem: ProblemResponse;
  code: string;
  language: string;
  contestContext?: AiContestContextPayload | null;
}) {
  const { t } = useI18n();
  return (
    <Dialog.Root>
      <Dialog.Trigger asChild>
        <Button variant="outline">
          <Bot className="size-4" aria-hidden="true" />
          {t("problems.openAiAssistant")}
        </Button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Close asChild>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-950/35" />
        </Dialog.Close>
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-[min(100vw,1120px)] flex-col border-l border-[var(--oj-border)] bg-[var(--oj-app-bg)] shadow-lg outline-none">
          <div className="flex items-center justify-between gap-3 border-b border-[var(--oj-border-soft)] bg-white px-4 py-3">
            <div className="min-w-0">
              <Dialog.Title className="truncate text-lg font-semibold text-[var(--oj-ink)]">
                {t("aiAssistant.title")}
              </Dialog.Title>
              <Dialog.Description className="mt-1 truncate text-sm text-[var(--oj-ink-muted)]">
                {problem.title}
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button
                type="button"
                className="grid size-9 shrink-0 place-items-center rounded-xl text-[var(--oj-ink-muted)] hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--oj-focus)]"
                aria-label={t("common.cancel")}
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            </Dialog.Close>
          </div>
          <div className="min-h-0 flex-1 p-3 md:p-4">
            <AiTutorWorkspace
              source="problem_detail"
              problem={problem}
              code={code}
              language={language}
              contestContext={contestContext}
              compact
              lockedProblem
            />
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
