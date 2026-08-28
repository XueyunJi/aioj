import * as React from "react";

function canPageScroll(deltaY: number) {
  if (typeof document === "undefined") return false;
  const page = document.scrollingElement;
  if (!page) return false;
  const maxTop = page.scrollHeight - page.clientHeight;
  if (maxTop <= 0) return false;
  if (deltaY > 0) return page.scrollTop < maxTop;
  if (deltaY < 0) return page.scrollTop > 0;
  return false;
}

export function passWheelToPageAtScrollBoundary(event: React.WheelEvent<HTMLElement>) {
  if (
    event.defaultPrevented ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.shiftKey ||
    !event.deltaY
  ) {
    return;
  }

  const container = event.currentTarget;
  const canScroll = container.scrollHeight > container.clientHeight + 1;
  const atTop = container.scrollTop <= 0;
  const atBottom = container.scrollTop + container.clientHeight >= container.scrollHeight - 1;
  const shouldPassToPage = !canScroll || (event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom);

  if (!shouldPassToPage || !canPageScroll(event.deltaY)) return;

  event.preventDefault();
  event.stopPropagation();
  window.scrollBy({ top: event.deltaY, left: 0, behavior: "auto" });
}
