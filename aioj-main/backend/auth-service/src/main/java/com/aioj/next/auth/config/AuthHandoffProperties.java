package com.aioj.next.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "aioj.auth.handoff")
public class AuthHandoffProperties {
    private boolean enabled = false;
    private Duration ttl = Duration.ofSeconds(60);
    private Duration maxTtl = Duration.ofSeconds(120);
    private int issueLimitPerMinute = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getMaxTtl() {
        return maxTtl;
    }

    public void setMaxTtl(Duration maxTtl) {
        this.maxTtl = maxTtl;
    }

    public int getIssueLimitPerMinute() {
        return issueLimitPerMinute;
    }

    public void setIssueLimitPerMinute(int issueLimitPerMinute) {
        this.issueLimitPerMinute = issueLimitPerMinute;
    }
}
