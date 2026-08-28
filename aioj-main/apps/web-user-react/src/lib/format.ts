import type { Difficulty, SubmissionStatus } from "@aioj/api-client";

export function cxValue(value: string | number | null | undefined, fallback = "--") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

export function formatDateTime(value?: string | number | null) {
  if (!value) return "--";
  const date = typeof value === "number" ? new Date(value) : new Date(value);
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

export function formatMemory(kb?: number | null) {
  if (kb === null || kb === undefined) return "--";
  if (kb >= 1024) return `${(kb / 1024).toFixed(1)} MB`;
  return `${kb} KB`;
}

export function difficultyTone(difficulty?: Difficulty | string) {
  if (difficulty === "EASY") return "green";
  if (difficulty === "MEDIUM") return "blue";
  if (difficulty === "HARD") return "amber";
  return "red";
}

export function statusTone(status?: SubmissionStatus | string) {
  if (status === "ACCEPTED") return "green";
  if (status === "RUNNING") return "blue";
  if (status === "QUEUED") return "neutral";
  if (status === "RUNTIME_ERROR" || status === "SYSTEM_ERROR") return "red";
  return "amber";
}

export const languageTemplates: Record<string, string> = {
  cpp: `#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    return 0;
}
`,
  python: `import sys

def main():
    data = sys.stdin.read().strip().split()

if __name__ == "__main__":
    main()
`,
  java: `import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    }
}
`
};
