package com.aioj.next.judge.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Lightweight in-process judge health summary; intentionally avoids per-case writes. */
@Component
public class JudgeRuntimeMonitor {
    private static final Logger log = LoggerFactory.getLogger(JudgeRuntimeMonitor.class);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong systemErrors = new AtomicLong();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong queueWaitTotalMs = new AtomicLong();
    private final AtomicLong queueWaitSamples = new AtomicLong();
    private final AtomicReference<Instant> lastConsumedAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public void recordCompleted(String status) {
        completed.incrementAndGet();
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        lastConsumedAt.set(Instant.now());
        if ("SYSTEM_ERROR".equals(status)) systemErrors.incrementAndGet();
    }

    public void recordFailure(Throwable error) {
        failed.incrementAndGet();
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        lastConsumedAt.set(Instant.now());
        lastError.set(error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    public void recordStarted(Long queueWaitMs) {
        started.incrementAndGet(); inFlight.incrementAndGet();
        if (queueWaitMs != null && queueWaitMs >= 0) { queueWaitTotalMs.addAndGet(queueWaitMs); queueWaitSamples.incrementAndGet(); }
    }
    public Snapshot snapshot() {
        long n = queueWaitSamples.get();
        return new Snapshot(started.get(), completed.get(), failed.get(), systemErrors.get(), inFlight.get(), lastConsumedAt.get(), lastError.get(), n == 0 ? 0 : queueWaitTotalMs.get() / n);
    }
    public record Snapshot(long started,long completed,long failed,long systemErrors,long inFlight,Instant lastConsumedAt,String lastError,long averageQueueWaitMs) {}

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void logSummary() {
        log.info("Judge runtime summary completed={} failed={} systemErrors={} lastConsumedAt={} lastError={}",
                completed.get(), failed.get(), systemErrors.get(), lastConsumedAt.get(), lastError.get());
    }
}
