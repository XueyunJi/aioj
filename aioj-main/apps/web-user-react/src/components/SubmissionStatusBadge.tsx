import type { SubmissionStatus } from "@aioj/api-client";
import { Badge, cn } from "@aioj/ui-react";
import { statusTone } from "../lib/format";

type StatusLike = SubmissionStatus | string | null | undefined;

const liveStatuses = new Set<StatusLike>(["QUEUED", "RUNNING"]);

const dotClass: Partial<Record<SubmissionStatus, string>> = {
  QUEUED: "bg-slate-500",
  RUNNING: "bg-blue-500",
  ACCEPTED: "bg-emerald-500",
  WRONG_ANSWER: "bg-amber-500",
  COMPILE_ERROR: "bg-amber-500",
  RUNTIME_ERROR: "bg-red-500",
  TIME_LIMIT_EXCEEDED: "bg-amber-500",
  MEMORY_LIMIT_EXCEEDED: "bg-amber-500",
  OUTPUT_LIMIT_EXCEEDED: "bg-amber-500",
  SYSTEM_ERROR: "bg-red-500"
};

const detailClass: Partial<Record<SubmissionStatus, string>> = {
  QUEUED: "border-slate-200 bg-slate-50",
  RUNNING: "border-blue-200 bg-blue-50",
  ACCEPTED: "border-emerald-200 bg-emerald-50",
  WRONG_ANSWER: "border-amber-200 bg-amber-50",
  COMPILE_ERROR: "border-amber-200 bg-amber-50",
  RUNTIME_ERROR: "border-red-200 bg-red-50",
  TIME_LIMIT_EXCEEDED: "border-amber-200 bg-amber-50",
  MEMORY_LIMIT_EXCEEDED: "border-amber-200 bg-amber-50",
  OUTPUT_LIMIT_EXCEEDED: "border-amber-200 bg-amber-50",
  SYSTEM_ERROR: "border-red-200 bg-red-50"
};

export function isLiveSubmissionStatus(status: StatusLike) {
  return liveStatuses.has(status);
}

export function hasLiveSubmissions(records?: Array<{ status?: StatusLike }>) {
  return records?.some((record) => isLiveSubmissionStatus(record.status)) ?? false;
}

export function submissionStatusDetailClass(status: StatusLike) {
  return detailClass[status as SubmissionStatus] ?? "border-[var(--oj-border-soft)] bg-[var(--oj-surface-muted)]";
}

export function SubmissionStatusBadge({
  status,
  label,
  className
}: {
  status: StatusLike;
  label: string;
  className?: string;
}) {
  return (
    <Badge tone={statusTone(status ?? undefined)} className={cn("w-fit gap-1.5 pl-2", className)}>
      <span className={cn("size-1.5 rounded-full", dotClass[status as SubmissionStatus] ?? "bg-slate-400")} aria-hidden="true" />
      {label}
    </Badge>
  );
}
