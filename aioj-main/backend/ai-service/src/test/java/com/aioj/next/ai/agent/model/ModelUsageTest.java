package com.aioj.next.ai.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageTest {

    @Test
    void zeroCountersAreConservativelyMarkedAsMissingProviderUsage() {
        ModelUsage usage = ModelUsage.from(new GatewayResponse(
                "answer", List.of(), "stop", 0, 0, 0, "deepseek", "deepseek-v4-pro"));

        assertThat(usage).isNotNull();
        assertThat(usage.reported()).isFalse();
        assertThat(usage.promptTokens()).isZero();
        assertThat(usage.completionTokens()).isZero();
    }
}
