package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AiJudgedSubmissionAnalysisJobHandler implements AiMemoryJobHandler {
    private final AiLearningProfileService learningProfileService;
    private final ObjectMapper objectMapper;

    public AiJudgedSubmissionAnalysisJobHandler(AiLearningProfileService learningProfileService,
                                                ObjectMapper objectMapper) {
        this.learningProfileService = learningProfileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String jobType() {
        return AiMemoryJobTypes.JOB_AI_JUDGED_SUBMISSION_ANALYSIS;
    }

    @Override
    public void handle(AiMemoryJobEntity job) {
        learningProfileService.recordJudgedSubmissionAnalysis(readPayload(job));
    }

    private AiJudgedSubmissionEventRequest readPayload(AiMemoryJobEntity job) {
        if (job == null || job.getPayloadJson() == null || job.getPayloadJson().isBlank()) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job payload is empty");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(job.getPayloadJson());
        } catch (Exception ex) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job payload is malformed", ex);
        }
        SubmissionStatus status = status(root);
        if (status == SubmissionStatus.QUEUED || status == SubmissionStatus.RUNNING) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job status is not terminal");
        }
        return new AiJudgedSubmissionEventRequest(
                requiredLong(root, "submissionId"),
                requiredLong(root, "problemId"),
                requiredLong(root, "userId"),
                status,
                optionalText(root, "language"),
                optionalLong(root, "contestId"),
                optionalLong(root, "contestRunId"),
                optionalLong(root, "contestProblemId"),
                optionalInstant(root, "judgedAt")
        );
    }

    private SubmissionStatus status(JsonNode root) {
        String value = requiredText(root, "status");
        try {
            return SubmissionStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job has invalid status", ex);
        }
    }

    private Long requiredLong(JsonNode root, String field) {
        Long value = optionalLong(root, field);
        if (value == null) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job missing " + field);
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
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job has invalid " + field, ex);
        }
    }

    private String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null || value.isBlank()) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job missing " + field);
        }
        return value;
    }

    private String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text.strip();
    }

    private Instant optionalInstant(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new AiMemoryJobPermanentFailure("AI judged submission analysis job has invalid " + field, ex);
        }
    }
}
