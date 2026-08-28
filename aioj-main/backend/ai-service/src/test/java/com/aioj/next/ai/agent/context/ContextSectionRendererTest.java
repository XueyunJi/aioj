package com.aioj.next.ai.agent.context;

import com.aioj.next.ai.agent.model.GatewayMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSectionRendererTest {

    private final ContextSectionRenderer renderer = new ContextSectionRenderer();

    @Test
    void focusRendersAsUserMessageBetweenRecentTurnsAndCurrentRequest() {
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.conversation(ContextSectionType.RECENT_TURNS, 40, TrustLevel.USER_PROVIDED,
                        List.of(GatewayMessage.user("m1"), GatewayMessage.assistant("a1", List.of())), List.of()),
                ContextSection.text(ContextSectionType.CONVERSATION_FOCUS, 60, false, TrustLevel.DERIVED_SUMMARY,
                        "[Conversation Focus]\nOpen tasks:\n- 实现第二题代码\n[/Conversation Focus]"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "继续"));

        List<GatewayMessage> rendered = renderer.render(sections);

        assertThat(rendered).extracting(GatewayMessage::role)
                .containsExactly("system", "user", "assistant", "user", "user");
        assertThat(rendered.get(3).content()).contains("Open tasks:");
        assertThat(rendered.get(4).content()).isEqualTo("继续");
    }

    @Test
    void missingFocusKeepsP0Shape() {
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));

        List<GatewayMessage> rendered = renderer.render(sections);

        assertThat(rendered).extracting(GatewayMessage::role).containsExactly("system", "user");
    }

    @Test
    void guardMatchRendersInsideSystemTextRightAfterPolicySnapshot() {
        // P3-4 fix: the layer-1 CONTEST_GUARD_MATCH annotation is server-authoritative
        // policy and must actually reach the model inside the combined system text.
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.ACTIVE_POLICY_SNAPSHOT, 20, true, TrustLevel.SYSTEM_POLICY,
                        "policy"),
                ContextSection.text(ContextSectionType.CONTEST_GUARD_MATCH, 25, true, TrustLevel.SYSTEM_POLICY,
                        "match-block"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));

        List<GatewayMessage> rendered = renderer.render(sections);

        assertThat(rendered).extracting(GatewayMessage::role).containsExactly("system", "user");
        assertThat(rendered.get(0).content()).isEqualTo("sys\n\npolicy\n\nmatch-block");
    }

    @Test
    void outputGuardRetryRendersInsideSystemTextAfterGuardMatch() {
        // P3-5: the one-shot L4 retry directive is server-authoritative and rides
        // inside the combined system text, right after any L3 match block.
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CONTEST_GUARD_MATCH, 25, true, TrustLevel.SYSTEM_POLICY,
                        "match-block"),
                ContextSection.text(ContextSectionType.CONTEST_OUTPUT_GUARD_RETRY, 26, true,
                        TrustLevel.SERVER_AUTHORITATIVE, "retry-directive"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));

        List<GatewayMessage> rendered = renderer.render(sections);

        assertThat(rendered).extracting(GatewayMessage::role).containsExactly("system", "user");
        assertThat(rendered.get(0).content()).isEqualTo("sys\n\nmatch-block\n\nretry-directive");
    }

    @Test
    void entryAndSelectedContextRenderInsideSystemTextInPriorityOrder() {
        // F1/F2: entry metadata and the user-selection block take the same system-text
        // path as CONTEST_GUARD_MATCH, ordered by priority (25 < 30 < 35).
        List<ContextSection> sections = List.of(
                ContextSection.text(ContextSectionType.SYSTEM_POLICY, 10, true, TrustLevel.SYSTEM_POLICY, "sys"),
                ContextSection.text(ContextSectionType.CONTEST_GUARD_MATCH, 25, true, TrustLevel.SYSTEM_POLICY,
                        "match-block"),
                ContextSection.text(ContextSectionType.ENTRY_CONTEXT, 30, false, TrustLevel.SERVER_AUTHORITATIVE,
                        "entry-block"),
                ContextSection.text(ContextSectionType.SELECTED_CONTEXT, 35, false, TrustLevel.USER_PROVIDED,
                        "selected-block"),
                ContextSection.text(ContextSectionType.CURRENT_USER_REQUEST, 90, true, TrustLevel.USER_PROVIDED, "hi"));

        List<GatewayMessage> rendered = renderer.render(sections);

        assertThat(rendered).extracting(GatewayMessage::role).containsExactly("system", "user");
        assertThat(rendered.get(0).content()).isEqualTo("sys\n\nmatch-block\n\nentry-block\n\nselected-block");
    }
}
