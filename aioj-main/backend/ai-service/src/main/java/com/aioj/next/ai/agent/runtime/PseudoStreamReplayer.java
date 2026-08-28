package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P3-5 (design doc §5.4, Q2): slices an L4-verified complete reply into SSE
 * {@code delta} payloads ({@code {"text":"..."}}) so a restricted turn still
 * feels streamed after the buffered guard check. Pure function over the input
 * text — no state, no side effects.
 *
 * <p>Transport reality (P3-5 实施补充): the current frontend ignores unknown
 * SSE events, so these delta events are a server-side addition for forward
 * compatibility; the unchanged full {@code message} event remains the content
 * source of truth and always follows the delta sequence.</p>
 *
 * <p>Slicing is by UTF-16 code units of the JSON text with one safety rule:
 * a surrogate pair (emoji etc.) is never split across chunks, since a lone
 * surrogate would produce invalid JSON string content.</p>
 */
@Component
public class PseudoStreamReplayer {

    private final int chunkSize;
    private final ObjectMapper objectMapper;

    @Autowired
    public PseudoStreamReplayer(AiProperties properties, ObjectMapper objectMapper) {
        this(Math.max(50, properties.getAgentCore().getPseudoStream().getChunkSize()), objectMapper);
    }

    PseudoStreamReplayer(int chunkSize, ObjectMapper objectMapper) {
        this.chunkSize = Math.max(50, chunkSize);
        this.objectMapper = objectMapper;
    }

    /**
     * @return one JSON payload ({@code {"text":"..."}) per chunk, in order;
     *         empty for null/blank input. Concatenating every chunk's {@code text}
     *         reproduces the input exactly.
     */
    public List<String> deltaPayloads(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> payloads = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + chunkSize, text.length());
            // Never split a surrogate pair across two chunks.
            if (end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end -= 1;
            }
            payloads.add(toDeltaJson(text.substring(offset, end)));
            offset = end;
        }
        return payloads;
    }

    private String toDeltaJson(String chunk) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("text", chunk));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Map-of-string serialization cannot realistically fail; keep the
            // replayer total by falling back to a minimal escaped payload.
            return "{\"text\":\"\"}";
        }
    }
}
