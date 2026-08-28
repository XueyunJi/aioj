package com.aioj.next.ai.domain.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryQualityGateEvalFixtureTest {
    private static final String FIXTURE_PATH = "ai-memory-eval-fixtures/memory-quality-gate-fixtures.json";

    private final MemoryQualityGate gate = new MemoryQualityGate();

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void fixtureBoundariesStayStable(MemoryGateFixture fixture) {
        MemoryQualityGate.GateResult result = gate.evaluate(
                new MemoryQualityGate.MemoryCandidate(
                        fixture.category(),
                        fixture.memoryKey(),
                        fixture.canonicalText(),
                        "{}",
                        fixture.scopeType(),
                        null,
                        fixture.evidenceType(),
                        fixture.confidence(),
                        fixture.longTerm(),
                        Boolean.TRUE.equals(fixture.problemSpecific()),
                        Boolean.TRUE.equals(fixture.hypothetical()),
                        Boolean.TRUE.equals(fixture.quoted()),
                        Boolean.TRUE.equals(fixture.needsConfirmation())
                ),
                new MemoryQualityGate.MessageContext(fixture.userMessage(), fixture.assistantMessage())
        );

        assertThat(result.accepted()).isEqualTo(fixture.expectedAccepted());
        assertThat(result.status()).isEqualTo(fixture.expectedStatus());
        if (fixture.expectedReason() != null && !fixture.expectedReason().isBlank()) {
            assertThat(result.rejectedReason()).isEqualTo(fixture.expectedReason());
        }
        assertThat(combinedFlags(result)).containsAll(fixture.expectedFlags());
    }

    static Stream<MemoryGateFixture> fixtures() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream input = MemoryQualityGateEvalFixtureTest.class.getClassLoader().getResourceAsStream(FIXTURE_PATH)) {
            assertThat(input).as("fixture resource %s", FIXTURE_PATH).isNotNull();
            return objectMapper.readValue(input, new TypeReference<List<MemoryGateFixture>>() {}).stream();
        }
    }

    private static List<String> combinedFlags(MemoryQualityGate.GateResult result) {
        return Stream.concat(result.qualityFlags().stream(), result.ambiguityFlags().stream()).toList();
    }

    record MemoryGateFixture(
            String name,
            String category,
            String memoryKey,
            String canonicalText,
            String scopeType,
            String evidenceType,
            double confidence,
            boolean longTerm,
            Boolean problemSpecific,
            Boolean hypothetical,
            Boolean quoted,
            Boolean needsConfirmation,
            String userMessage,
            String assistantMessage,
            boolean expectedAccepted,
            String expectedStatus,
            String expectedReason,
            List<String> expectedFlags
    ) {
        @Override
        public String toString() {
            return name;
        }
    }
}
