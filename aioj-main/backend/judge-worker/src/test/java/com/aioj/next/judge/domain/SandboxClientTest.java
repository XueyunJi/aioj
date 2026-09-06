package com.aioj.next.judge.domain;

import com.aioj.next.contract.submission.SubmissionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxClientTest {
    private static final long MB = 1024L * 1024L;

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

    @Test
    void compileMemoryLimitExceededKeepsResourceStatus() {
        assertThat(SandboxClient.compileFailureStatus("Memory Limit Exceeded"))
                .isEqualTo(SubmissionStatus.MEMORY_LIMIT_EXCEEDED);
    }

    @Test
    void compileTimeLimitExceededKeepsResourceStatus() {
        assertThat(SandboxClient.compileFailureStatus("Time Limit Exceeded"))
                .isEqualTo(SubmissionStatus.TIME_LIMIT_EXCEEDED);
    }

    @Test
    void compilerNonzeroExitRemainsCompileError() {
        assertThat(SandboxClient.compileFailureStatus("Nonzero Exit Status"))
                .isEqualTo(SubmissionStatus.COMPILE_ERROR);
    }

    @Test
    void compileSignalIsNotMisreportedAsCompileError() {
        assertThat(SandboxClient.compileFailureStatus("Signalled"))
                .isEqualTo(SubmissionStatus.RUNTIME_ERROR);
    }

    @Test
    void compileInternalErrorIsSystemError() {
        assertThat(SandboxClient.compileFailureStatus("Internal Error"))
                .isEqualTo(SubmissionStatus.SYSTEM_ERROR);
    }

    @Test
    void phaseMessageDistinguishesCompileAndRunMemoryLimit() {
        assertThat(SandboxClient.phaseMessage("Compile", "Memory Limit Exceeded", null, 9))
                .isEqualTo("Compile phase: Memory Limit Exceeded");
        assertThat(SandboxClient.phaseMessage("Run", "Memory Limit Exceeded", null, 9))
                .isEqualTo("Run phase: Memory Limit Exceeded");
    }

    @Test
    void signalElevenIsReportedAsSegmentationFaultSignal() {
        assertThat(SandboxClient.phaseMessage("Run", "Signalled", null, 11))
                .isEqualTo("Run phase: SIGSEGV (signal 11)");
    }

    @Test
    void cppCompileMemoryHasIndependent256MbMinimum() {
        assertThat(SandboxClient.compileMemoryLimitBytes("cpp", 32L * MB, 2, 262_144, 524_288))
                .isEqualTo(256L * MB);
    }

    @Test
    void javaCompileMemoryHasIndependent512MbMinimum() {
        assertThat(SandboxClient.compileMemoryLimitBytes("java", 128L * MB, 2, 262_144, 524_288))
                .isEqualTo(512L * MB);
    }

    @Test
    void compileMemoryStillScalesAboveConfiguredMinimum() {
        assertThat(SandboxClient.compileMemoryLimitBytes("cpp", 256L * MB, 2, 262_144, 524_288))
                .isEqualTo(512L * MB);
    }

    @Test
    void compileTimeUsesMultiplierAndMaximum() {
        assertThat(SandboxClient.compileCpuLimitNs(1_000, 10, 60_000)).isEqualTo(10_000_000_000L);
        assertThat(SandboxClient.compileCpuLimitNs(10_000, 10, 60_000)).isEqualTo(60_000_000_000L);
    }
}
