package com.aioj.next.judge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxExecutionClientTest {
    @Test
    void generatedFilesCollectorFallsBackToWorkspaceAndEmitsDiagnostics() {
        String collector = SandboxExecutionClient.generatedFilesCollector(7);

        assertThat(collector).contains("target_case_count = 7");
        assertThat(collector).contains("collect_mode = 'PAIRED'");
        assertThat(collector).contains("collect(Path('testcases'), Path('testcases'))");
        assertThat(collector).contains("collect(Path('.'), Path('.'))");
        assertThat(collector).contains("'generatedPairCount': len(pair_names)");
        assertThat(collector).contains("'generatedInputCount': input_count");
        assertThat(collector).contains("'generatedOutputCount': output_count");
        assertThat(collector).contains("stress_small_");
        assertThat(collector).contains("'crossCheckInputs': cross_check_inputs");
    }

    @Test
    void officialInputCollectorCollectsInputsWithoutRequiringPairs() {
        String collector = SandboxExecutionClient.generatedFilesCollector(5, "OFFICIAL_INPUTS");

        assertThat(collector).contains("collect_mode = 'OFFICIAL_INPUTS'");
        assertThat(collector).contains("official_inputs = [name for name in input_names if not is_stress_case(name)]");
        assertThat(collector).contains("for suffix in ('.in', '.out'):");
        assertThat(collector).contains("if suffix not in parts[name]:");
        assertThat(collector).contains("'manifestJson': manifest_json");
        assertThat(collector).contains("'scanRoot': scan_root");
    }
}
