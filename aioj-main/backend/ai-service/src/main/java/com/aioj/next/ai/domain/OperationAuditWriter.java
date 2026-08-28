package com.aioj.next.ai.domain;

import com.aioj.next.ai.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.ai.persistence.mapper.OperationAuditEventMapper;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.security.SecuritySupport;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class OperationAuditWriter {
    private static final String AI_PROBLEM_DRAFT_GENERATION_JOB = "AI_PROBLEM_DRAFT_GENERATION_JOB";

    private final OperationAuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    public OperationAuditWriter(OperationAuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String action, String resourceType, Long resourceId, String status, Map<String, Object> summary) {
        record(action, resourceType, resourceId, status, summary, SecuritySupport.currentUserId(), null, null, null);
    }

    public void record(String action, String resourceType, Long resourceId, String status, Map<String, Object> summary,
                       Long actorUserId, Long contestId, Long contestRunId, Long targetUserId) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setActorUserId(actorUserId);
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setContestId(contestId);
        event.setContestRunId(contestRunId);
        event.setTargetUserId(targetUserId);
        event.setStatus(status);
        event.setTraceId(TraceIds.current());
        event.setSummaryJson(toJson(summary));
        event.setCreatedAt(Instant.now());
        mapper.insert(event);
    }

    /**
     * AI problem draft generation uses one audit row per job. Unlike the generic operation-job audit trail,
     * this row mirrors the current lifecycle state so the operations page and the AI job page agree.
     */
    public int replaceProblemDraftGenerationJobLifecycle(Long jobId, String action, String status,
                                                          Map<String, Object> summary) {
        if (jobId == null) {
            return 0;
        }
        OperationAuditEventEntity update = new OperationAuditEventEntity();
        update.setAction(action);
        update.setStatus(status);
        update.setSummaryJson(toJson(summary));
        return mapper.update(update, new LambdaUpdateWrapper<OperationAuditEventEntity>()
                .eq(OperationAuditEventEntity::getResourceType, AI_PROBLEM_DRAFT_GENERATION_JOB)
                .eq(OperationAuditEventEntity::getResourceId, jobId));
    }

    private String toJson(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"summary serialization failed\"}";
        }
    }
}
