package com.aioj.next.judge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxClientTest {
    @Test
    void stdoutCollectLimitGrowsForLargeExpectedOutput() {
        int limit = SandboxClient.stdoutCollectLimitBytes(100_002L, 65_536, 65_536, 4_194_304);

        assertThat(limit).isEqualTo(165_538);
        assertThat(limit).isGreaterThan(100_002);
    }

    @Test
    void stdoutCollectLimitKeepsBaseForSmallExpectedOutput() {
        int limit = SandboxClient.stdoutCollectLimitBytes(1_024L, 65_536, 65_536, 4_194_304);

        assertThat(limit).isEqualTo(65_536);
    }

    @Test
    void stdoutCollectLimitIsClampedByConfiguredMaximum() {
        int limit = SandboxClient.stdoutCollectLimitBytes(10_000_000L, 65_536, 65_536, 4_194_304);

        assertThat(limit).isEqualTo(4_194_304);
    }

    @Test
    void stderrCollectLimitUsesIndependentConfiguredLimit() {
        int limit = SandboxClient.stderrCollectLimitBytes(32_768);

        assertThat(limit).isEqualTo(32_768);
    }
}
