package com.aioj.next.judge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "aioj.judge")
public class JudgeWorkerProperties {
    private String sandboxEndpoint = "http://localhost:8090/execute";
    private String sandboxToken = "";
    private Duration sandboxTimeout = Duration.ofSeconds(10);
    private List<String> languageWhitelist = List.of("java", "cpp", "python");
    private String cacheRoot = System.getProperty("user.home") + "/.ai-oj-next/judge-cache";
    private String problemServiceBaseUrl = "http://problem-service:8202";
    private String internalApiToken = "";
    private int stdoutBaseCollectLimitBytes = 64 * 1024;
    private int stdoutExtraCollectBytes = 64 * 1024;
    private int stdoutMaxCollectLimitBytes = 4 * 1024 * 1024;
    private int stderrCollectLimitBytes = 64 * 1024;

    public String getSandboxEndpoint() {
        return sandboxEndpoint;
    }

    public void setSandboxEndpoint(String sandboxEndpoint) {
        this.sandboxEndpoint = sandboxEndpoint;
    }

    public String getSandboxToken() {
        return sandboxToken;
    }

    public void setSandboxToken(String sandboxToken) {
        this.sandboxToken = sandboxToken;
    }

    public Duration getSandboxTimeout() {
        return sandboxTimeout;
    }

    public void setSandboxTimeout(Duration sandboxTimeout) {
        this.sandboxTimeout = sandboxTimeout;
    }

    public List<String> getLanguageWhitelist() {
        return languageWhitelist;
    }

    public void setLanguageWhitelist(List<String> languageWhitelist) {
        this.languageWhitelist = languageWhitelist;
    }

    public String getCacheRoot() {
        return cacheRoot;
    }

    public void setCacheRoot(String cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public String getProblemServiceBaseUrl() {
        return problemServiceBaseUrl;
    }

    public void setProblemServiceBaseUrl(String problemServiceBaseUrl) {
        this.problemServiceBaseUrl = problemServiceBaseUrl;
    }

    public String getInternalApiToken() {
        return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    public int getStdoutBaseCollectLimitBytes() {
        return stdoutBaseCollectLimitBytes;
    }

    public void setStdoutBaseCollectLimitBytes(int stdoutBaseCollectLimitBytes) {
        this.stdoutBaseCollectLimitBytes = stdoutBaseCollectLimitBytes;
    }

    public int getStdoutExtraCollectBytes() {
        return stdoutExtraCollectBytes;
    }

    public void setStdoutExtraCollectBytes(int stdoutExtraCollectBytes) {
        this.stdoutExtraCollectBytes = stdoutExtraCollectBytes;
    }

    public int getStdoutMaxCollectLimitBytes() {
        return stdoutMaxCollectLimitBytes;
    }

    public void setStdoutMaxCollectLimitBytes(int stdoutMaxCollectLimitBytes) {
        this.stdoutMaxCollectLimitBytes = stdoutMaxCollectLimitBytes;
    }

    public int getStderrCollectLimitBytes() {
        return stderrCollectLimitBytes;
    }

    public void setStderrCollectLimitBytes(int stderrCollectLimitBytes) {
        this.stderrCollectLimitBytes = stderrCollectLimitBytes;
    }
}
