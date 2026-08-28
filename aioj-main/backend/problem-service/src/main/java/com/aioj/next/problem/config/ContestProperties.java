package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.contest")
public class ContestProperties {
    /**
     * Extra seconds after a run's endAt during which the AI contest guard still
     * treats the run as active. Lets the guard cover late AI turns right after
     * the contest ends.
     */
    private long aiGuardGraceSeconds = 600;

    public long getAiGuardGraceSeconds() {
        return aiGuardGraceSeconds;
    }

    public void setAiGuardGraceSeconds(long aiGuardGraceSeconds) {
        this.aiGuardGraceSeconds = aiGuardGraceSeconds;
    }
}
