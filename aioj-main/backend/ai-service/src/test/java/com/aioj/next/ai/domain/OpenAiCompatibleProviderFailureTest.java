package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1.6: previously silent provider failures must increment the in-process
 * failure counters while keeping the legacy degrade-to-empty behavior.
 */
class OpenAiCompatibleProviderFailureTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetCounters() {
        AiFailureMetrics.reset();
    }

    @Test
    void extractMemoriesFailureIsCountedAndStillReturnsEmpty() {
        AiProperties properties = new AiProperties();
        AiModelCompletionClient completionClient = mock(AiModelCompletionClient.class);
        when(completionClient.complete(any(), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenThrow(new RuntimeException("provider boom"));
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                properties,
                objectMapper,
                new ClarificationSchemaRepairer(),
                scope -> config(AiModelScope.MEMORY_EXTRACTION, "chat-model", null),
                completionClient,
                new AiCapacityService(properties)
        );

        assertThat(provider.extractMemories("请记住我习惯用 Java 刷题。", "好的，已记住。")).isEmpty();
        assertThat(AiFailureMetrics.memoryExtractionFailures()).isEqualTo(1);
    }

    @Test
    void embedFailureIsCountedAndStillReturnsEmpty() {
        AiProperties properties = new AiProperties();
        AiModelCompletionClient completionClient = mock(AiModelCompletionClient.class);
        // Loopback port 1 refuses immediately: a real HTTP failure without external egress.
        when(completionClient.normalizeEmbeddingUrl(anyString())).thenReturn("http://127.0.0.1:1/embeddings");
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                properties,
                objectMapper,
                new ClarificationSchemaRepairer(),
                scope -> config(AiModelScope.EMBEDDING, "embed-model", 1024),
                completionClient,
                new AiCapacityService(properties)
        );

        assertThat(provider.embed("测试文本")).isEmpty();
        assertThat(AiFailureMetrics.embeddingFailures()).isEqualTo(1);
    }

    private static AiModelEffectiveConfig config(AiModelScope scope, String model, Integer embeddingDimension) {
        return new AiModelEffectiveConfig(
                scope,
                true,
                false,
                "test",
                "mock",
                "http://127.0.0.1:1",
                "test-key",
                "",
                "",
                "",
                model,
                true,
                false,
                "high",
                null,
                null,
                embeddingDimension,
                Instant.now(),
                null
        );
    }
}
