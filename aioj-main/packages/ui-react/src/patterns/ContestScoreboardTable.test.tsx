import * as React from "react";
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ContestScoreboardTable } from "./ContestScoreboardTable";

let container: HTMLDivElement | null = null;
let root: Root | null = null;

const labels = {
  rank: "Rank",
  participant: "Participant",
  solved: "Solved",
  penalty: "Penalty",
  empty: "Empty",
  pending: "Pending",
  solvedStatus: "Solved",
  attempted: "Attempted",
  unsolved: "Unsolved"
};

describe("ContestScoreboardTable responsive layout", () => {
  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterEach(async () => {
    if (root) {
      await act(async () => {
        root?.unmount();
      });
    }
    root = null;
    container?.remove();
    container = null;
  });

  it("keeps frozen participant columns desktop-only and preserves problem column widths", async () => {
    const problems = Array.from({ length: 7 }, (_, index) => ({
      contestProblemId: `problem-${index + 1}`,
      label: String.fromCharCode(65 + index)
    }));

    await act(async () => {
      root?.render(
        <ContestScoreboardTable
          problems={problems}
          rows={[{
            rank: 1,
            participantId: "participant-1",
            accountSnapshot: "student01",
            displayNameSnapshot: "Student One",
            solvedCount: 1,
            penaltyMinutes: 20,
            cells: problems.map((problem) => ({
              contestProblemId: problem.contestProblemId,
              status: "UNSOLVED" as const,
              attempts: 0,
              wrongAttempts: 0,
              pendingAttempts: 0,
              penaltyMinutes: 0
            }))
          }]}
          labels={labels}
        />
      );
    });

    const table = container?.querySelector("table");
    expect(table?.style.minWidth).toBe("1248px");

    const rankHeader = container?.querySelector("th");
    const participantHeader = container?.querySelectorAll("th")[1];
    expect(rankHeader?.className.split(" ")).toContain("md:sticky");
    expect(rankHeader?.className.split(" ")).not.toContain("sticky");
    expect(participantHeader?.className.split(" ")).toContain("md:left-16");
  });
});
