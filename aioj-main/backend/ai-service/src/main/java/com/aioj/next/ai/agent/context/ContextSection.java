package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.tool.SourceRef;

import java.util.List;

/**
 * One atomic unit of the assembled prompt (design doc §6.7). Either plain
 * {@code content} (rendered into a system/user message by type) or structured
 * conversational {@code messages} (RECENT_TURNS).
 */
public record ContextSection(
        ContextSectionType type,
        int priority,
        boolean atomic,
        int tokenEstimate,
        TrustLevel trustLevel,
        List<SourceRef> sources,
        String content,
        List<GatewayMessage> messages
) {
    public ContextSection {
        sources = sources == null ? List.of() : List.copyOf(sources);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static ContextSection text(ContextSectionType type, int priority, boolean atomic,
                                      TrustLevel trustLevel, String content) {
        return new ContextSection(type, priority, atomic, estimate(content), trustLevel, List.of(), content, List.of());
    }

    public static ContextSection conversation(ContextSectionType type, int priority, TrustLevel trustLevel,
                                              List<GatewayMessage> messages, List<SourceRef> sources) {
        int estimate = messages.stream().mapToInt(m -> estimate(m.content())).sum();
        return new ContextSection(type, priority, false, estimate, trustLevel, sources, null, messages);
    }

    private static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.max(1, (text.length() + 3L) / 4L);
    }
}
