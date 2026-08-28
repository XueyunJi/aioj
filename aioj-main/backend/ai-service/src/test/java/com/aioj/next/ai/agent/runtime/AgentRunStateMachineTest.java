package com.aioj.next.ai.agent.runtime;

import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRunStateMachineTest {

    private final AiAgentRunMapper mapper = mock(AiAgentRunMapper.class);
    private final AgentRunStateMachine stateMachine = new AgentRunStateMachine(mapper, new ObjectMapper());

    @Test
    void startPersistsRunRowWithBudgetAndInitialState() {
        AiAgentRunEntity run = stateMachine.start("turn-1", "c1", 7L, "deepseek", "deepseek-v4-pro",
                "ps-1", "STREAM", new LoopBudget(8, 6, 3, 3));

        ArgumentCaptor<AiAgentRunEntity> captor = ArgumentCaptor.forClass(AiAgentRunEntity.class);
        verify(mapper).insert(captor.capture());
        AiAgentRunEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(run.getId());
        assertThat(entity.getTurnId()).isEqualTo("turn-1");
        assertThat(entity.getStatus()).isEqualTo(AgentRunStateMachine.STATUS_RECEIVED);
        assertThat(entity.getBudgetJson()).contains("maxAgentSteps").contains("8");
        assertThat(entity.getPolicySnapshotId()).isEqualTo("ps-1");
        assertThat(entity.getOutputMode()).isEqualTo("STREAM");
        assertThat(entity.getStartedAt()).isNotNull();
    }

    @Test
    void persistenceFailureStillReturnsUsableRunHandle() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(AiAgentRunEntity.class));
        AiAgentRunEntity run = stateMachine.start("turn-2", "c1", 7L, "kimi", "kimi-k3",
                "ps-2", "STREAM", new LoopBudget(8, 6, 3, 3));
        assertThat(run.getId()).isNotNull();
        // subsequent updates must not throw either
        stateMachine.advance(run.getId(), AgentRunStateMachine.STATUS_GENERATING);
        stateMachine.complete(run.getId(), 1, 0);
    }
}
