package com.aioj.next.contract.problem;

/**
 * Whether students can see a problem in the public catalog and practice mode.
 * PRIVATE problems stay usable inside contest runs; participants only see them
 * while the owning run window is active.
 */
public enum ProblemVisibility {
    PUBLIC,
    PRIVATE
}
