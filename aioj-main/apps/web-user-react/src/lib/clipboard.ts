export async function copyToClipboard(value: string, sourceElement?: HTMLElement | null) {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value);
      return;
    } catch {
      // Fall through to the legacy copy path for restricted clipboard permissions.
    }
  }
  if (copyWithCopyEvent(value)) return;
  if (copyWithTextarea(value)) return;
  if (sourceElement && copySelectedElement(sourceElement, false)) return;
  throw new Error("Copy failed");
}

function copyWithCopyEvent(value: string) {
  let handled = false;
  const listener = (event: ClipboardEvent) => {
    event.clipboardData?.setData("text/plain", value);
    event.preventDefault();
    handled = true;
  };
  document.addEventListener("copy", listener);
  try {
    return document.execCommand("copy") && handled;
  } finally {
    document.removeEventListener("copy", listener);
  }
}

function copyWithTextarea(value: string) {
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.top = "0";
  textarea.style.left = "0";
  textarea.style.width = "1px";
  textarea.style.height = "1px";
  textarea.style.opacity = "0";
  textarea.style.pointerEvents = "none";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  const ok = document.execCommand("copy");
  textarea.remove();
  return ok;
}

function copySelectedElement(element: HTMLElement, keepSelection: boolean) {
  const selection = window.getSelection();
  const range = document.createRange();
  range.selectNodeContents(element);
  selection?.removeAllRanges();
  selection?.addRange(range);
  const ok = document.execCommand("copy");
  if (ok && !keepSelection) selection?.removeAllRanges();
  return ok;
}
