package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetAllocatorTest {

    private final ContextBudgetAllocator allocator = new ContextBudgetAllocator();

    @Test
    void underBudgetPassesThroughUnchanged() {
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));
        ContextBudgetAllocator.BudgetResult result = allocator.enforce(sections, 6000);
        assertThat(result.sections()).isEqualTo(sections);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void overBudgetDropsOldestRecentTurnsFirst() {
        String longText = "x".repeat(4000); // ~1000 tokens each
        List<GatewayMessage> turns = List.of(
                GatewayMessage.user("oldest " + longText),
                GatewayMessage.assistant("oldest answer " + longText, List.of()),
                GatewayMessage.user("newest question"));
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.conversation(ContextSectionType.RECENT_TURNS, 40, TrustLevel.USER_PROVIDED, turns, List.of()),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "current"));
        ContextBudgetAllocator.BudgetResult result = allocator.enforce(sections, 1200);

        ContextSection recent = result.sections().stream()
                .filter(section -> section.type() == ContextSectionType.RECENT_TURNS)
                .findFirst()
                .orElseThrow();
        assertThat(recent.messages()).hasSize(1);
        assertThat(recent.messages().get(0).content()).isEqualTo("newest question");
        assertThat(result.warnings()).contains("recent_turns_trimmed");
        // Atomic sections are never dropped.
        assertThat(result.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(ContextSectionType.SYSTEM_POLICY);
        });
        assertThat(result.sections()).anySatisfy(section -> {
            assertThat(section.type()).isEqualTo(ContextSectionType.CURRENT_USER_REQUEST);
        });
    }

    @Test
    void extremeOverflowDropsWholeRecentSectionAndWarns() {
        List<GatewayMessage> turns = List.of(GatewayMessage.user("x".repeat(8000)));
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.conversation(ContextSectionType.RECENT_TURNS, 40, TrustLevel.USER_PROVIDED, turns, List.of()),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "current"));
        ContextBudgetAllocator.BudgetResult result = allocator.enforce(sections, 600);
        assertThat(result.sections()).noneMatch(section -> section.type() == ContextSectionType.RECENT_TURNS);
        assertThat(result.warnings()).contains("recent_turns_dropped");
    }

    @Test
    void overBudgetDropsFocusBeforeTrimmingRecentTurns() {
        String longText = "x".repeat(4000); // ~1000 tokens each
        List<GatewayMessage> turns = List.of(
                GatewayMessage.user("oldest " + longText),
                GatewayMessage.assistant("oldest answer " + longText, List.of()),
                GatewayMessage.user("newest question"));
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.conversation(ContextSectionType.RECENT_TURNS, 40, TrustLevel.USER_PROVIDED, turns, List.of()),
                ContextSection.text(ContextSectionType.CONVERSATION_FOCUS, 60, false, TrustLevel.DERIVED_SUMMARY,
                        "focus " + longText),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "current"));
        // Total ~3000 tokens; budget 2100 fits once the ~1000-token focus is gone.
        ContextBudgetAllocator.BudgetResult result = allocator.enforce(sections, 2100);

        assertThat(result.sections()).noneMatch(section -> section.type() == ContextSectionType.CONVERSATION_FOCUS);
        assertThat(result.warnings()).contains("conversation_focus_dropped");
        // Recent turns survive untouched: focus is always the first casualty.
        ContextSection recent = result.sections().stream()
                .filter(section -> section.type() == ContextSectionType.RECENT_TURNS)
                .findFirst()
                .orElseThrow();
        assertThat(recent.messages()).hasSize(3);
        assertThat(result.warnings()).doesNotContain("recent_turns_trimmed", "recent_turns_dropped");
    }
}
