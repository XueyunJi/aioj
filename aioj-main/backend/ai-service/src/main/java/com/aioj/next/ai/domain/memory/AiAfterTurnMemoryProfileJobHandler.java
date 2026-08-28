package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiMemoryUpdatePlanner;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class AiAfterTurnMemoryProfileJobHandler implements AiMemoryJobHandler {
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final AiMessageMapper messageMapper;
    private final AiLearningProfileService learningProfileService;
    private final AiMemoryService memoryService;
    private final AiMemoryUpdatePlanner memoryUpdatePlanner;
    private final ObjectMapper objectMapper;

    public AiAfterTurnMemoryProfileJobHandler(
            AiMessageMapper messageMapper,
            AiLearningProfileService learningProfileService,
            AiMemoryService memoryService,
            AiMemoryUpdatePlanner memoryUpdatePlanner,
            ObjectMapper objectMapper
    ) {
        this.messageMapper = messageMapper;
        this.learningProfileService = learningProfileService;
        this.memoryService = memoryService;
        this.memoryUpdatePlanner = memoryUpdatePlanner;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return AiMemoryJobTypes.JOB_AI_AFTER_TURN_MEMORY_PROFILE;
    }

    @Override
    public void handle(AiMemoryJobEntity job) {
        Payload payload = readPayload(job);
        AiMessageEntity userMessage = requireMessage(payload.userMessageId(), payload.userId(), payload.conversationId(), ROLE_USER);
        AiMessageEntity assistantMessage = requireMessage(payload.assistantMessageId(), payload.userId(), payload.conversationId(), ROLE_ASSISTANT);
        if (!AiConversationService.MESSAGE_STATUS_COMPLETED.equals(assistantMessage.getStatus())) {
            throw new AiMemoryJobPermanentFailure("Assistant message is not completed: messageId=" + payload.assistantMessageId());
        }
        String userContent = normalizeContent(userMessage.getContent());
        if (userContent.isBlank()) {
            throw new AiMemoryJobPermanentFailure("User message is empty: messageId=" + payload.userMessageId());
        }
        String assistantContent = normalizeContent(assistantMessage.getContent());
        AiChatRequest request = request(payload, userMessage, assistantMessage, userContent);
        AiCompletion completion = new AiCompletion(
                assistantContent,
                "stored-message",
                normalizeModel(assistantMessage.getModel()),
                0,
                0
        );
        AiLearningProfileService.SubmissionAnalysisSignal submissionSignal =
                learningProfileService.recordSubmissionAnalysis(payload.userId(), request, completion, assistantMessage.getId());
        memoryService.extractAndSave(
                payload.userId(),
                payload.conversationId(),
                assistantMessage.getId(),
                userContent,
                assistantContent
        );
        memoryUpdatePlanner.afterTurn(
                payload.userId(),
                payload.conversationId(),
                assistantMessage.getId(),
                request,
                completion,
                submissionSignal
        );
    }

    private Payload readPayload(AiMemoryJobEntity job) {
        if (job == null || job.getPayloadJson() == null || job.getPayloadJson().isBlank()) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job payload is empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(job.getPayloadJson());
        } catch (Exception ex) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job payload is malformed", ex);
        }
        Payload payload = new Payload(
                requiredLong(root, "userId"),
                requiredText(root, "conversationId"),
                requiredLong(root, "userMessageId"),
                requiredLong(root, "assistantMessageId"),
                optionalLong(root, "problemId"),
                optionalLong(root, "contestId"),
                optionalLong(root, "contestRunId"),
                optionalLong(root, "contestProblemId"),
                optionalLong(root, "submissionId")
        );
        if (payload.conversationId().isBlank()) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job conversationId is empty");
        }
        return payload;
    }

    private AiMessageEntity requireMessage(Long id, Long userId, String conversationId, String role) {
        AiMessageEntity message = messageMapper.selectById(id);
        if (message == null) {
            throw new AiMemoryJobPermanentFailure("AI message not found: messageId=" + id);
        }
        if (!userId.equals(message.getUserId())) {
            throw new AiMemoryJobPermanentFailure("AI message belongs to another user: messageId=" + id);
        }
        if (!conversationId.equals(message.getConversationId())) {
            throw new AiMemoryJobPermanentFailure("AI message conversation mismatch: messageId=" + id);
        }
        if (!role.equalsIgnoreCase(normalizeContent(message.getRole()))) {
            throw new AiMemoryJobPermanentFailure("AI message role mismatch: messageId=" + id);
        }
        return message;
    }

    private AiChatRequest request(Payload payload, AiMessageEntity userMessage, AiMessageEntity assistantMessage, String userContent) {
        Long problemId = firstNonNull(payload.problemId(), firstNonNull(assistantMessage.getProblemId(), userMessage.getProblemId()));
        AiChatRequest.ContestContext contestContext = hasContestContext(payload)
                ? new AiChatRequest.ContestContext(payload.contestId(), payload.contestRunId(), payload.contestProblemId())
                : null;
        AiChatRequest.SubmissionContext submissionContext = payload.submissionId() == null
                ? null
                : new AiChatRequest.SubmissionContext(
                payload.submissionId(),
                "AI_AFTER_TURN_MEMORY_PROFILE",
                Boolean.TRUE,
                "Background after-turn memory/profile processing"
        );
        return new AiChatRequest(
                payload.conversationId(),
                problemId,
                userContent,
                "assist",
                null,
                null,
                null,
                null,
                null,
                contestContext,
                submissionContext
        );
    }

    private boolean hasContestContext(Payload payload) {
        return payload.contestId() != null || payload.contestRunId() != null || payload.contestProblemId() != null;
    }

    private Long firstNonNull(Long left, Long right) {
        return left == null ? right : left;
    }

    private Long requiredLong(JsonNode root, String field) {
        Long value = optionalLong(root, field);
        if (value == null) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job missing " + field);
        }
        return value;
    }

    private Long optionalLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        String text = value.asText("");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job has invalid " + field, ex);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job missing " + field);
        }
        String text = value.asText("");
        if (text.isBlank()) {
            throw new AiMemoryJobPermanentFailure("AI after-turn memory job empty " + field);
        }
        return text;
    }

    private String normalizeContent(String value) {
        return value == null ? "" : value.strip();
    }

    private String normalizeModel(String value) {
        return value == null || value.isBlank() ? "stored-message" : value.strip();
    }

    private record Payload(
            Long userId,
            String conversationId,
            Long userMessageId,
            Long assistantMessageId,
            Long problemId,
            Long contestId,
            Long contestRunId,
            Long contestProblemId,
            Long submissionId
    ) {
    }
}
