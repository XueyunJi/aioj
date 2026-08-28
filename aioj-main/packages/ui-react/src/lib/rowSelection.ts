import type { MouseEvent } from "react";

/**
 * Lets a selectable data row treat ordinary content as its selection target
 * without stealing clicks from controls embedded in the row.
 */
export function shouldToggleRowSelection(event: MouseEvent<HTMLElement>): boolean {
  const target = event.target;
  if (!(target instanceof Element)) return false;
  return !target.closest(
    "button, a, input, textarea, select, option, [role='button'], [data-row-selection-ignore='true']"
  );
}
