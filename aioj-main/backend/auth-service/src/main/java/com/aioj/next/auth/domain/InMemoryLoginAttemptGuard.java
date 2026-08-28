package com.aioj.next.auth.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-process sliding-window login throttle. The deployment runs a single
 * auth-service instance, so process-local state is sufficient; restarts reset
 * the counters, which is acceptable because locks are a deceleration measure.
 */
public class InMemoryLoginAttemptGuard implements LoginAttemptGuard {
    private final int maxFailures;
    private final Duration window;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    public InMemoryLoginAttemptGuard(int maxFailures, Duration window, Duration lockDuration) {
        this(maxFailures, window, lockDuration, Clock.systemUTC());
    }

    public InMemoryLoginAttemptGuard(int maxFailures, Duration window, Duration lockDuration, Clock clock) {
        this.maxFailures = Math.max(1, maxFailures);
        this.window = window;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    @Override
    public void ensureNotLocked(String account) {
        String key = normalize(account);
        Instant until = lockedUntil.get(key);
        if (until == null) {
            return;
        }
        if (clock.instant().isBefore(until)) {
            throw new DomainException(ErrorCode.TOO_MANY_REQUESTS,
                    "Too many failed login attempts. The account is temporarily locked.");
        }
        lockedUntil.remove(key);
    }

    @Override
    public void recordFailure(String account) {
        String key = normalize(account);
        Instant now = clock.instant();
        Deque<Instant> attempts = failures.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        attempts.addLast(now);
        purgeExpired(attempts, now);
        if (attempts.size() >= maxFailures) {
            lockedUntil.put(key, now.plus(lockDuration));
            failures.remove(key);
        }
    }

    @Override
    public void recordSuccess(String account) {
        String key = normalize(account);
        failures.remove(key);
        lockedUntil.remove(key);
    }

    private void purgeExpired(Deque<Instant> attempts, Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Instant> iterator = attempts.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isBefore(cutoff)) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private String normalize(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }
}
