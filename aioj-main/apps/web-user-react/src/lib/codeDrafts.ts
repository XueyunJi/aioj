/**
 * Per-user code draft storage keys.
 *
 * Drafts live in localStorage, which is shared by every account that signs in on
 * the same browser profile. Every key MUST include the owning user id so one
 * account can never load or overwrite another account's unsaved code.
 */

export function practiceDraftKey(userId: string | null | undefined, problemId: string, language: string): string | null {
  if (!userId) return null;
  return `aioj.react.draft.${userId}.${problemId}.${language}`;
}

export function contestDraftKey(
  userId: string | null | undefined,
  contestRunId: string,
  contestProblemId: string,
  language: string
): string | null {
  if (!userId) return null;
  return `aioj.react.contestDraft.${userId}.${contestRunId}.${contestProblemId}.${language}`;
}

export function readCodeDraft(key: string | null): string | null {
  if (!key) return null;
  return localStorage.getItem(key);
}

export function writeCodeDraft(key: string | null, code: string) {
  if (!key) return;
  localStorage.setItem(key, code);
}
