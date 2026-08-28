package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSubmissionContextResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AiProblemContextResolver problemContextResolver = mock(AiProblemContextResolver.class);
    private final AiSubmissionContextResolver resolver = new AiSubmissionContextResolver(
            mock(ProblemServiceClient.class),
            problemContextResolver
    );

    @Test
    void safeSummaryExcludesSourceRawOutputAndFullProblemStatement() throws Exception {
        Map<String, Object> summary = resolver.safeSummary(practiceContext());
        String json = objectMapper.writeValueAsString(summary);

        assertThat(summary)
                .containsEntry("submissionId", "123")
                .containsEntry("problemId", "99")
                .containsEntry("status", "WRONG_ANSWER")
                .containsEntry("codeAllowedToModel", true)
                .containsEntry("source", "resolved.submissionContext");
        assertThat(json)
                .contains("case 2 failed")
                .doesNotContain("int main")
                .doesNotContain("stdout secret")
                .doesNotContain("stderr secret")
                .doesNotContain("full statement secret");
    }

    @Test
    void activeContestContextBlockKeepsSourceRedacted() {
        when(problemContextResolver.contextBlock(any())).thenReturn("");

        String block = resolver.contextBlock(activeContestContext());

        assertThat(block)
                .contains("[Selected Submission Context]")
                .contains("status: WRONG_ANSWER")
                .contains("codeAllowedToModel: false")
                .contains("[Submission Code Redacted]")
                .doesNotContain("int main");
    }

    @Test
    void contextBlockOmitsRawOutputEvenWhenPracticeCodeIsAllowed() {
        when(problemContextResolver.contextBlock(any())).thenReturn("");

        String block = resolver.contextBlock(practiceContext());

        assertThat(block)
                .contains("[Selected Submission Context]")
                .contains("status: WRONG_ANSWER")
                .contains("rawOutputPolicy: raw program output is omitted")
                .contains("<CURRENT_SUBMISSION_CODE language=\"cpp\">")
                .contains("int main() { return 0; }")
                .doesNotContain("stdoutExcerpt", "stderrExcerpt", "stdout secret", "stderr secret");
    }

    private AiSubmissionContextResponse practiceContext() {
        return new AiSubmissionContextResponse(
                123L,
                7L,
                99L,
                null,
                null,
                null,
                "PRACTICE",
                false,
                "cpp",
                "WRONG_ANSWER",
                "Wrong answer on case 2",
                "stdout secret",
                "stderr secret",
                0,
                12,
                2048,
                0.0,
                100.0,
                true,
                "int main() { return 0; }",
                "sha256-submission",
                List.of(new AiSubmissionCaseContext(1, "case 2", "WRONG_ANSWER", 0.0, 10.0, 12, 2048, "case 2 failed")),
                problemContext(),
                Instant.parse("2026-06-10T09:30:00Z"),
                Instant.parse("2026-06-10T09:31:00Z"),
                null
        );
    }

    private AiSubmissionContextResponse activeContestContext() {
        AiSubmissionContextResponse practice = practiceContext();
        return new AiSubmissionContextResponse(
                practice.submissionId(),
                practice.ownerUserId(),
                practice.problemId(),
                301L,
                302L,
                401L,
                "CONTEST",
                true,
                practice.language(),
                practice.status(),
                practice.judgeMessage(),
                null,
                null,
                practice.exitStatus(),
                practice.runTimeMillis(),
                practice.memoryKb(),
                practice.score(),
                practice.maxScore(),
                false,
                practice.codeText(),
                practice.codeHash(),
                practice.caseResults(),
                practice.problemContext(),
                practice.submittedAt(),
                practice.judgedAt(),
                "contest policy"
        );
    }

    private AiProblemContextResponse problemContext() {
        return new AiProblemContextResponse(
                99L,
                null,
                null,
                null,
                "Binary Search Practice",
                "MEDIUM",
                "full statement secret",
                "Binary search summary",
                List.of("binary_search"),
                List.of("n <= 100000"),
                List.of(),
                1000,
                262144,
                "PROBLEM",
                Instant.parse("2026-06-10T09:00:00Z")
        );
    }
}
