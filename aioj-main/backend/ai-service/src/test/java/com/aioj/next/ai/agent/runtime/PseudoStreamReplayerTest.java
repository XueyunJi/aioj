package com.aioj.next.ai.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-5 (Q2): delta slicing boundaries, exact-size multiples, surrogate-pair
 * (UTF-8) safety and the chunk-size clamp of {@link PseudoStreamReplayer}.
 */
class PseudoStreamReplayerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PseudoStreamReplayer replayer = new PseudoStreamReplayer(300, objectMapper);

    @Test
    void slicesTextIntoChunkSizedDeltasThatReassembleExactly() throws Exception {
        String text = "题".repeat(1000);

        List<String> payloads = replayer.deltaPayloads(text);

        assertThat(payloads).hasSize(4);
        StringBuilder reassembled = new StringBuilder();
        for (int i = 0; i < payloads.size(); i++) {
            JsonNode node = objectMapper.readTree(payloads.get(i));
            assertThat(node.has("text")).isTrue();
            String chunk = node.get("text").asText();
            assertThat(chunk).hasSize(i < 3 ? 300 : 100);
            reassembled.append(chunk);
        }
        assertThat(reassembled.toString()).isEqualTo(text);
    }

    @Test
    void exactMultipleOfChunkSizeHasNoTrailingEmptyDelta() {
        List<String> payloads = replayer.deltaPayloads("a".repeat(600));
        assertThat(payloads).hasSize(2);
    }

    @Test
    void neverSplitsSurrogatePairAcrossDeltas() throws Exception {
        // 299 ascii chars + one emoji (a surrogate pair) + tail: the naive 300-char
        // boundary would land between the high and low surrogate.
        String emoji = "\uD83D\uDE00";
        String text = "a".repeat(299) + emoji + "b".repeat(200);

        List<String> payloads = replayer.deltaPayloads(text);

        assertThat(payloads).hasSize(2);
        String first = objectMapper.readTree(payloads.get(0)).get("text").asText();
        String second = objectMapper.readTree(payloads.get(1)).get("text").asText();
        // Boundary moved one char back so the pair stays whole in the second chunk.
        assertThat(first).hasSize(299);
        assertThat(second).startsWith(emoji);
        assertThat(first + second).isEqualTo(text);
        // Every payload is well-formed JSON (no lone surrogates escaped mid-pair).
        for (String payload : payloads) {
            assertThat(objectMapper.readTree(payload).get("text").asText()).isNotEmpty();
        }
    }

    @Test
    void multiByteCjkSlicesWithoutCorruption() throws Exception {
        String text = "中文混合 English 混合内容，".repeat(50);
        List<String> payloads = replayer.deltaPayloads(text);
        StringBuilder reassembled = new StringBuilder();
        for (String payload : payloads) {
            reassembled.append(objectMapper.readTree(payload).get("text").asText());
        }
        assertThat(reassembled.toString()).isEqualTo(text);
    }

    @Test
    void emptyAndNullInputProduceNoDeltas() {
        assertThat(replayer.deltaPayloads(null)).isEmpty();
        assertThat(replayer.deltaPayloads("")).isEmpty();
    }

    @Test
    void chunkSizeIsClampedToAtLeastFifty() {
        PseudoStreamReplayer clamped = new PseudoStreamReplayer(10, objectMapper);
        assertThat(clamped.deltaPayloads("x".repeat(120))).hasSize(3);
    }

    @Test
    void jsonEscapingKeepsQuotesAndNewlinesIntact() throws Exception {
        String text = "第一行\"引号\"\n第二行\\反斜杠";
        List<String> payloads = replayer.deltaPayloads(text);
        assertThat(payloads).hasSize(1);
        assertThat(objectMapper.readTree(payloads.get(0)).get("text").asText()).isEqualTo(text);
    }
}
