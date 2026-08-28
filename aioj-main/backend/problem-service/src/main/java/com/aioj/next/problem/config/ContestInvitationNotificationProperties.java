package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aioj.contest-invitation-dispatch")
public class ContestInvitationNotificationProperties {
    private int concurrency = 2;
    private int batchSize = 25;
    private long pollMillis = 5000;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }
}
