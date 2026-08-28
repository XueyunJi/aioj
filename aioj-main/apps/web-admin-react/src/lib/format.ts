import type { Difficulty, TestcasePackageStatus } from "@aioj/api-client";

export function formatDateTime(value?: string | number | null) {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return new Intl.DateTimeFormat(undefined, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    hourCycle: "h23"
  }).format(date);
}

export function formatBytes(value?: number | null) {
  if (value === null || value === undefined) return "--";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

export function shortId(value: string | number) {
  const text = String(value);
  return text.length > 10 ? text.slice(-10) : text;
}

export function difficultyTone(difficulty?: Difficulty | string): "green" | "blue" | "amber" | "red" {
  if (difficulty === "EASY") return "green";
  if (difficulty === "MEDIUM") return "blue";
  if (difficulty === "HARD") return "amber";
  return "red";
}

export function packageStatusTone(status?: TestcasePackageStatus | string): "green" | "blue" | "amber" | "red" | "neutral" {
  if (status === "READY") return "green";
  if (status === "UPLOADING") return "blue";
  if (status === "PROCESSING") return "amber";
  if (status === "FAILED") return "red";
  return "neutral";
}
