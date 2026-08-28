package com.aioj.next.auth.domain;

/**
 * Throttles repeated login failures per account to blunt online brute-force attempts.
 * Implementations must be safe for concurrent use.
 */
public interface LoginAttemptGuard {
    /**
     * Throws {@link com.aioj.next.common.error.DomainException} with
     * {@link com.aioj.next.common.error.ErrorCode#TOO_MANY_REQUESTS} when the
     * account is currently locked after too many recent failures.
     */
    void ensureNotLocked(String account);

    void recordFailure(String account);

    void recordSuccess(String account);
}
