package com.aioj.next.ai.domain.context;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiContextReportBuilderTest {
    private final AiContextReportBuilder builder = new AiContextReportBuilder();

    @Test
    void safePreviewOmitsCodeRawOutputAndSecrets() {
        String preview = builder.safePreview("""
                {"submissionId":"123","codeText":"int main(){return 0;}","stdoutExcerpt":"stdout secret","stderrExcerpt":"stderr secret"}
                stdoutExcerpt:
                hidden stdout
                stderr:
                hidden stderr
                status: WRONG_ANSWER
                token=plain-secret-123
                ```cpp
                #include <bits/stdc++.h>
                int main(){return 0;}
                ```
                """, 700);

        assertThat(preview)
                .contains("[raw output omitted]", "status: WRONG_ANSWER", "token=***")
                .doesNotContain("int main", "#include", "stdout secret", "stderr secret", "hidden stdout", "hidden stderr", "plain-secret-123");
    }

    @Test
    void buildAggregatesRequiredAndOptionalSectionsBySource() {
        AiContextBuildReport report = builder.build(builder.mutableSections().stream().toList());
        assertThat(report.sections()).isEmpty();

        report = builder.build(java.util.List.of(
                builder.section("required", "conversation_state", "State", 90, "ai-service.state", "internal", true, "state", Map.of()),
                builder.section("optional", "long_term_memory", "Memory", 50, "ai-service.memory", "memory", false, "memory", Map.of())
        ));

        assertThat(report.requiredSectionCount()).isEqualTo(1);
        assertThat(report.optionalSectionCount()).isEqualTo(1);
        assertThat(report.sourceSummary()).containsEntry("ai-service.state", 1).containsEntry("ai-service.memory", 1);
    }
}
