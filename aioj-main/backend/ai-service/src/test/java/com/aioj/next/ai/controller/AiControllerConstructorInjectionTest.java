package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.AgentChatFacade;
import com.aioj.next.ai.domain.AccountImportParseService;
import com.aioj.next.ai.domain.AiContextService;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.AiProvider;
import com.aioj.next.ai.domain.AiQuotaService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.AiMemoryDebugService;
import com.aioj.next.ai.domain.memory.AiMemoryReviewService;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiControllerConstructorInjectionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AiProvider.class, () -> mock(AiProvider.class))
            .withBean(AgentChatFacade.class, () -> mock(AgentChatFacade.class))
            .withBean(AiQuotaService.class, () -> mock(AiQuotaService.class))
            .withBean(AccountImportParseService.class, () -> mock(AccountImportParseService.class))
            .withBean(AiConversationService.class, () -> mock(AiConversationService.class))
            .withBean(AiContextService.class, () -> mock(AiContextService.class))
            .withBean(AiMemoryService.class, () -> mock(AiMemoryService.class))
            .withBean(AiLearningProfileService.class, () -> mock(AiLearningProfileService.class))
            .withBean(AiMemoryCandidateService.class, () -> mock(AiMemoryCandidateService.class))
            .withBean(AiMemoryDebugService.class, () -> mock(AiMemoryDebugService.class))
            .withBean(AiMemoryReviewService.class, () -> mock(AiMemoryReviewService.class))
            .withBean(ProblemDraftStore.class, () -> mock(ProblemDraftStore.class))
            .withBean(AiAssistantResponseNormalizer.class, () -> mock(AiAssistantResponseNormalizer.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean("aiProblemDraftExecutor", Executor.class, () -> Runnable::run)
            .withBean(AiController.class);

    @Test
    void springCanSelectAutowiredConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AiController.class));
    }
}
