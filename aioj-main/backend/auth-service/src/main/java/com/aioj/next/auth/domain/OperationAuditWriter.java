package com.aioj.next.auth.domain;

import com.aioj.next.auth.entity.OperationAuditEventEntity;
import com.aioj.next.auth.mapper.OperationAuditEventMapper;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.security.SecuritySupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class OperationAuditWriter {
    private final OperationAuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    public OperationAuditWriter(OperationAuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(String action, String resourceType, Long resourceId, String status, Map<String, Object> summary) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setActorUserId(SecuritySupport.currentUserId());
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setStatus(status);
        event.setTraceId(TraceIds.current());
        event.setSummaryJson(toJson(summary));
        event.setCreatedAt(Instant.now());
        mapper.insert(event);
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
