package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiAfterTurnMemoryProfileEventServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void recordsCompletedTurnWithSafePayloadAndIdempotency() {
        AiMemoryEventService eventService = mock(AiMemoryEventService.class);
        AiAfterTurnMemoryProfileEventService service = new AiAfterTurnMemoryProfileEventService(eventService);
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId("c-safe");
        conversation.setUserId(7L);
        conversation.setProblemId(99L);
        AiChatRequest request = new AiChatRequest(
                "c-safe",
                99L,
                "user text with password=secret",
                "assist",
                null,
                null,
                null,
                "client-1",
                null,
                new AiChatRequest.ContestContext(11L, 22L, 33L),
                new AiChatRequest.SubmissionContext(300L, "analyze", true, "note")
        );
        AiChatMessageResponse user = message(100L, "user", "user text with password=secret", "client-1");
        AiChatMessageResponse assistant = message(200L, "assistant", """
                ```cpp
                int main() { return 0; }
                ```
                model answer
                """, "client-1:assistant");

        service.recordCompletedTurn(7L, conversation, request, user, assistant);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<List<AiMemoryEventService.EventJobSpec>> jobsCaptor = ArgumentCaptor.forClass(List.class);
        verify(eventService).recordEvent(
                eq(AiMemoryJobTypes.EVENT_AI_CHAT_TURN_COMPLETED),
                eq(7L),
                eq("ai_chat_turn"),
                eq("200"),
                eq("ai-chat-turn-completed:c-safe:200"),
                payloadCaptor.capture(),
                eq(AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE),
                jobsCaptor.capture()
        );
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("userId", 7L)
                .containsEntry("conversationId", "c-safe")
                .containsEntry("userMessageId", 100L)
                .containsEntry("assistantMessageId", 200L)
                .containsEntry("submissionId", 300L);
        assertThat(payload.toString()).doesNotContain("user text", "model answer", "password", "int main");
        assertThat(jobsCaptor.getValue()).singleElement().satisfies(job -> {
            assertThat(job.jobType()).isEqualTo(AiMemoryJobTypes.JOB_AI_AFTER_TURN_MEMORY_PROFILE);
            assertThat(job.idempotencyKey()).isEqualTo("ai-chat-turn-completed:c-safe:200:memory-profile");
            assertThat(job.payload().toString()).doesNotContain("user text", "model answer", "int main");
        });
    }

    private static AiChatMessageResponse message(Long id, String role, String content, String clientMessageId) {
        Instant now = Instant.now();
        return new AiChatMessageResponse(
                id,
                "c-safe",
                99L,
                clientMessageId,
                role,
                content,
                role.equals("assistant") ? "mock-model" : null,
                "COMPLETED",
                null,
                now,
                now
        );
    }
}
