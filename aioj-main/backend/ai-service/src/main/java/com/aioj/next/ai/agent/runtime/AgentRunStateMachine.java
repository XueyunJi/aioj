package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Persists the agent run lifecycle (design doc §4.2) into ai_agent_runs.
 * One row per turn (uk_agent_run_turn). Persistence failure is logged and
 * tolerated: a lost run row must never break a user-facing turn.
 */
@Service
public class AgentRunStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStateMachine.class);

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_CONTEXT_PLANNED = "CONTEXT_PLANNED";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_TOOL_CALLING = "TOOL_CALLING";
    public static final String STATUS_FINAL_DRAFTED = "FINAL_DRAFTED";
    public static final String STATUS_OUTPUT_CHECKED = "OUTPUT_CHECKED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final AiAgentRunMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentRunStateMachine(AiAgentRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public AiAgentRunEntity start(String turnId, String conversationId, long userId, String provider, String model,
                                  String policySnapshotId, String outputMode, LoopBudget budget) {
        AiAgentRunEntity entity = new AiAgentRunEntity();
        entity.setId(IdWorker.getId());
        entity.setTurnId(turnId);
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setProvider(provider == null ? "unknown" : provider);
        entity.setModel(model == null ? "unknown" : model);
        entity.setStatus(STATUS_RECEIVED);
        entity.setStepCount(0);
        entity.setToolCallCount(0);
        try {
            entity.setBudgetJson(objectMapper.writeValueAsString(budget));
        } catch (Exception ex) {
            entity.setBudgetJson("{}");
        }
        entity.setPolicySnapshotId(policySnapshotId);
        entity.setOutputMode(outputMode);
        entity.setStartedAt(LocalDateTime.now());
        try {
            mapper.insert(entity);
        } catch (RuntimeException ex) {
            log.warn("AI agent run persist failed turn={} error={}", turnId, ex.toString());
        }
        return entity;
    }

    public void advance(long runId, String status) {
        update(runId, new UpdateWrapper<AiAgentRunEntity>()
                .eq("id", runId)
                .set("status", status));
    }

    public void recordProgress(long runId, int steps, int toolCalls) {
        update(runId, new UpdateWrapper<AiAgentRunEntity>()
                .eq("id", runId)
                .set("step_count", steps)
                .set("tool_call_count", toolCalls));
    }

    public void complete(long runId, int steps, int toolCalls) {
        update(runId, new UpdateWrapper<AiAgentRunEntity>()
                .eq("id", runId)
                .set("status", STATUS_COMPLETED)
                .set("step_count", steps)
                .set("tool_call_count", toolCalls)
                .set("completed_at", LocalDateTime.now()));
    }

    public void fail(long runId, String errorCode, int steps, int toolCalls) {
        update(runId, new UpdateWrapper<AiAgentRunEntity>()
                .eq("id", runId)
                .set("status", STATUS_FAILED)
                .set("error_code", errorCode == null ? "AGENT_RUN_FAILURE" : errorCode)
                .set("step_count", steps)
                .set("tool_call_count", toolCalls)
                .set("completed_at", LocalDateTime.now()));
    }

    private void update(long runId, UpdateWrapper<AiAgentRunEntity> wrapper) {
        try {
            mapper.update(null, wrapper);
        } catch (RuntimeException ex) {
            log.warn("AI agent run update failed run={} error={}", runId, ex.toString());
        }
    }
}
