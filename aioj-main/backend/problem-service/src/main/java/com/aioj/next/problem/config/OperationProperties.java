package com.aioj.next.problem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "aioj.operations")
public class OperationProperties {
    private Path artifactRoot = Path.of(System.getProperty("user.home"), ".ai-oj-next", "operation-artifacts");
    private Duration leaseDuration = Duration.ofMinutes(5);
    private long pollMillis = 5000L;
    private int executorPoolSize = 2;
    private int pollBatchSize = 3;
    private boolean workerEnabled = true;

    public Path getArtifactRoot() {
        return artifactRoot;
    }

    public void setArtifactRoot(Path artifactRoot) {
        this.artifactRoot = artifactRoot;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public long getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }

    public int getPollBatchSize() {
        return pollBatchSize;
    }

    public void setPollBatchSize(int pollBatchSize) {
        this.pollBatchSize = pollBatchSize;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }
}
