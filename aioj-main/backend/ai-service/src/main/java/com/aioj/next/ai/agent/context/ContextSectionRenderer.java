package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders sections into provider messages in a fixed, prefix-cache-friendly
 * order (design doc §6.7/§6.9): stable system text first, conversational
 * recent turns next, the current request last.
 *
 * <p>CONTEST_GUARD_MATCH (priority 25) rides inside the combined system text,
 * right after the policy snapshot: it is server-authoritative policy, not
 * conversation. It only exists for participant turns with L3 message-layer hits.
 * ENTRY_CONTEXT (30) and SELECTED_CONTEXT (35) take the same system-text path:
 * the first is server-resolved metadata, the second a delimited user-data block.</p>
 */
@Component
public class ContextSectionRenderer {

    private static final List<ContextSectionType> SYSTEM_TEXT_TYPES = List.of(
            ContextSectionType.SYSTEM_POLICY,
            ContextSectionType.ACTIVE_POLICY_SNAPSHOT,
            ContextSectionType.CONTEST_GUARD_MATCH,
            ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY,
            ContextSectionType.ENTRY_CONTEXT,
            ContextSectionType.SELECTED_CONTEXT,
            ContextSectionType.BOOTSTRAP_CONTEXT
    );

    public List<GatewayMessage> render(List<ContextSection> sections) {
        List<ContextSection> ordered = sections.stream()
                .sorted(Comparator.comparingInt(ContextSection::priority))
                .toList();
        List<GatewayMessage> messages = new ArrayList<>();
        StringBuilder systemText = new StringBuilder();
        List<GatewayMessage> conversational = new ArrayList<>();
        String focusText = null;
        String currentRequest = null;
        for (ContextSection section : ordered) {
            if (SYSTEM_TEXT_TYPES.contains(section.type())) {
                if (section.content() != null && !section.content().isBlank()) {
                    if (!systemText.isEmpty()) {
                        systemText.append("\n\n");
                    }
                    systemText.append(section.content());
                }
                continue;
            }
            if (section.type() == ContextSectionType.RECENT_TURNS) {
                conversational.addAll(section.messages());
                continue;
            }
            if (section.type() == ContextSectionType.CONVERSATION_FOCUS) {
                // Derived digest rides as a user message after recent turns: close to the
                // current request, and data-by-role rather than instruction.
                focusText = section.content();
                continue;
            }
            if (section.type() == ContextSectionType.CURRENT_USER_REQUEST) {
                currentRequest = section.content();
            }
        }
        if (!systemText.isEmpty()) {
            messages.add(GatewayMessage.system(systemText.toString()));
        }
        messages.addAll(conversational);
        if (focusText != null && !focusText.isBlank()) {
            messages.add(GatewayMessage.user(focusText));
        }
        if (currentRequest != null) {
            messages.add(GatewayMessage.user(currentRequest));
        }
        return messages;
    }
}
