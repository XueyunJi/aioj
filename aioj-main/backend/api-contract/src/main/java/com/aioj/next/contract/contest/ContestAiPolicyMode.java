package com.aioj.next.contract.contest;

/**
 * Per-run AI guard policy for contest problems during a running contest run.
 * Configured on the contest blueprint and snapshotted into the run at publish
 * time.
 *
 * <ul>
 *   <li>DEFAULT: private problems are refused; public problems get idea-level
 *       guidance only (no complete submittable code).</li>
 *   <li>STRICT: any message materially referencing a problem of this run is
 *       refused, regardless of problem visibility.</li>
 *   <li>DISABLED: problems of this run are excluded from AI interception.</li>
 * </ul>
 */
public enum ContestAiPolicyMode {
    DEFAULT,
    STRICT,
    DISABLED
}
