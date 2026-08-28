package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiAfterTurnMemoryProfileEventService {
    private static final String SOURCE_TYPE_AI_CHAT_TURN = "ai_chat_turn";

    private final AiMemoryEventService eventService;

    public AiAfterTurnMemoryProfileEventService(AiMemoryEventService eventService) {
        this.eventService = eventService;
    }

    public AiMemoryEventService.RecordedEvent recordCompletedTurn(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            AiChatMessageResponse userMessage,
            AiChatMessageResponse assistantMessage
    ) {
        if (userId == null || conversation == null || conversation.getId() == null
                || userMessage == null || userMessage.id() == null
                || assistantMessage == null || assistantMessage.id() == null) {
            throw new IllegalArgumentException("Completed AI turn identifiers are required");
        }
        String turnKey = completedTurnKey(conversation.getId(), assistantMessage.id());
        Map<String, Object> payload = payload(userId, conversation, request, userMessage, assistantMessage);
        return eventService.recordEvent(
                AiMemoryJobTypes.EVENT_AI_CHAT_TURN_COMPLETED,
                userId,
                SOURCE_TYPE_AI_CHAT_TURN,
                String.valueOf(assistantMessage.id()),
                turnKey,
                payload,
                AiMemoryEventService.SENSITIVITY_USER_PRIVATE_SAFE,
                List.of(new AiMemoryEventService.EventJobSpec(
                        AiMemoryJobTypes.JOB_AI_AFTER_TURN_MEMORY_PROFILE,
                        turnKey + ":memory-profile",
                        payload,
                        null,
                        null
                ))
        );
    }

    private Map<String, Object> payload(
            Long userId,
            AiConversationEntity conversation,
            AiChatRequest request,
            AiChatMessageResponse userMessage,
            AiChatMessageResponse assistantMessage
    ) {
        AiChatRequest.ContestContext contest = request == null ? null : request.contestContext();
        AiChatRequest.SubmissionContext submission = request == null ? null : request.submissionContext();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("conversationId", conversation.getId());
        payload.put("userMessageId", userMessage.id());
        payload.put("assistantMessageId", assistantMessage.id());
        payload.put("problemId", firstNonNull(request == null ? null : request.problemId(), conversation.getProblemId()));
        payload.put("contestId", contest == null ? conversation.getContestId() : firstNonNull(contest.contestId(), conversation.getContestId()));
        payload.put("contestRunId", contest == null ? conversation.getContestRunId() : firstNonNull(contest.contestRunId(), conversation.getContestRunId()));
        payload.put("contestProblemId", contest == null ? conversation.getContestProblemId() : firstNonNull(contest.contestProblemId(), conversation.getContestProblemId()));
        payload.put("submissionId", submission == null ? null : submission.submissionId());
        Instant completedAt = assistantMessage.completedAt() == null ? Instant.now() : assistantMessage.completedAt();
        payload.put("completedAt", completedAt.toString());
        return payload;
    }

    private Long firstNonNull(Long left, Long right) {
        return left == null ? right : left;
    }

    private String completedTurnKey(String conversationId, Long assistantMessageId) {
        return "ai-chat-turn-completed:" + conversationId + ":" + assistantMessageId;
    }
}
