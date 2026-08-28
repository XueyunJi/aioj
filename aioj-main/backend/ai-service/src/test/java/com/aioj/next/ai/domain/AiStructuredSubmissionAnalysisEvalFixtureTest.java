package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiStructuredSubmissionAnalysisEvalFixtureTest {
    private static final String FIXTURE_PATH = "ai-memory-eval-fixtures/structured-submission-analysis-fixtures.json";

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void structuredSubmissionAnalysisFixturesStayStable(SubmissionAnalysisFixture fixture) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiModelConfigResolver configResolver = mock(AiModelConfigResolver.class);
        AiModelCompletionClient completionClient = mock(AiModelCompletionClient.class);
        AiQuotaService quotaService = mock(AiQuotaService.class);
        AiStructuredSubmissionAnalysisService service = new AiStructuredSubmissionAnalysisService(
                objectMapper,
                configResolver,
                completionClient,
                quotaService,
                new AiCapacityService(new AiProperties())
        );

        boolean useModel = fixture.modelJson() != null || (fixture.modelContent() != null && !fixture.modelContent().isBlank());
        when(configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION)).thenReturn(config(true, useModel));
        if (useModel) {
            String content = fixture.modelJson() == null ? fixture.modelContent() : objectMapper.writeValueAsString(fixture.modelJson());
            when(completionClient.complete(any(), anyList(), anyDouble(), anyInt(), eq(true)))
                    .thenReturn(new AiModelCompletionClient.CompletionResult(content, "mock", "mock-16k", 17, 9));
        }

        AiStructuredSubmissionAnalysisService.AnalysisResult result = service.analyze(
                7L,
                request(fixture.userMessage()),
                completion(fixture.assistantAnswer()),
                context(fixture.status())
        );

        assertThat(result.profileEligible()).isEqualTo(fixture.expectedProfileEligible());
        assertThat(result.masteryEvidence()).isEqualTo(fixture.expectedMasteryEvidence());
        assertThat(result.modelGenerated()).isEqualTo(fixture.expectedModelGenerated());
        if (fixture.expectedProfileKey() != null) {
            assertThat(result.profileKey()).isEqualTo(fixture.expectedProfileKey());
        }
        assertThat(result.flatTags()).containsAll(fixture.expectedTags());
        for (String unsafe : fixture.expectedUnsafeAbsent()) {
            assertThat(result.summary()).doesNotContain(unsafe);
            assertThat(String.join("\n", result.evidenceItems())).doesNotContain(unsafe);
        }
        if (useModel) {
            ArgumentCaptor<List<Map<String, String>>> messages = ArgumentCaptor.forClass(List.class);
            verify(completionClient).complete(eq(config(true, true)), messages.capture(), anyDouble(), anyInt(), eq(true));
            String prompt = messages.getValue().get(1).get("content");
            assertThat(prompt)
                    .contains("codeHash=sha256-submission")
                    .doesNotContain("source should never be sent")
                    .doesNotContain("stdout secret should never be sent")
                    .doesNotContain("stderr secret should never be sent")
                    .doesNotContain("int main")
                    .doesNotContain("sk-proj-secret");
        }
    }

    static Stream<SubmissionAnalysisFixture> fixtures() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = AiStructuredSubmissionAnalysisEvalFixtureTest.class.getClassLoader().getResourceAsStream(FIXTURE_PATH)) {
            assertThat(input).as("fixture resource %s", FIXTURE_PATH).isNotNull();
            return objectMapper.readValue(input, new TypeReference<List<SubmissionAnalysisFixture>>() {}).stream();
        }
    }

    private static AiModelEffectiveConfig config(boolean enabled, boolean hasKey) {
        return new AiModelEffectiveConfig(
                AiModelScope.MEMORY_EXTRACTION,
                enabled,
                false,
                "test",
                "mock",
                "http://mock.test/v1/chat/completions",
                hasKey ? "sk-test" : "",
                hasKey ? "sk-***" : "",
                "env",
                "OPENAI_API_KEY",
                "mock-16k",
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static AiChatRequest request(String message) {
        return new AiChatRequest(
                "conversation-1",
                99L,
                message,
                "assist",
                null,
                null,
                null,
                "client-1",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "DEBUG", true, null)
        );
    }

    private static AiCompletion completion(String content) {
        return new AiCompletion(content, "mock", "mock-model", 10, 20);
    }

    private static AiSubmissionContextResponse context(String status) {
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
                status,
                "Wrong answer on sample 2",
                "stdout secret should never be sent",
                "stderr secret should never be sent",
                0,
                12,
                2048,
                0.0,
                100.0,
                true,
                "source should never be sent: int main(){return 0;} sk-proj-secret",
                "sha256-submission",
                List.of(new AiSubmissionCaseContext(2, "sample 2", status, 0.0, 1.0, 12, 2048, "check(mid) failed")),
                new AiProblemContextResponse(
                        99L,
                        null,
                        null,
                        null,
                        "Binary Search Practice",
                        "MEDIUM",
                        "Full statement not needed here",
                        "Find the answer with binary search.",
                        List.of("binary_search"),
                        List.of("n <= 100000"),
                        List.of(),
                        1000,
                        262144,
                        "PROBLEM",
                        Instant.now()
                ),
                Instant.now(),
                Instant.now(),
                null
        );
    }

    record SubmissionAnalysisFixture(
            String name,
            String status,
            String userMessage,
            String assistantAnswer,
            JsonNode modelJson,
            String modelContent,
            boolean expectedProfileEligible,
            boolean expectedMasteryEvidence,
            boolean expectedModelGenerated,
            List<String> expectedTags,
            String expectedProfileKey,
            List<String> expectedUnsafeAbsent
    ) {
        SubmissionAnalysisFixture {
            expectedTags = expectedTags == null ? List.of() : expectedTags;
            expectedUnsafeAbsent = expectedUnsafeAbsent == null ? List.of() : expectedUnsafeAbsent;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
