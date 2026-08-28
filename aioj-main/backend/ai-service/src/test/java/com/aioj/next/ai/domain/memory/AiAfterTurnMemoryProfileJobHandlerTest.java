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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAfterTurnMemoryProfileJobHandlerTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-after-turn";
    private static final Long USER_MESSAGE_ID = 100L;
    private static final Long ASSISTANT_MESSAGE_ID = 200L;

    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiLearningProfileService learningProfileService;
    @Mock
    private AiMemoryService memoryService;
    @Mock
    private AiMemoryUpdatePlanner memoryUpdatePlanner;

    private AiAfterTurnMemoryProfileJobHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AiAfterTurnMemoryProfileJobHandler(
                messageMapper,
                learningProfileService,
                memoryService,
                memoryUpdatePlanner,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void handlesSelectedSubmissionAfterTurnInOrder() {
        AiMessageEntity user = message(USER_MESSAGE_ID, "user", "我提交后 WA 了。", null, AiConversationService.MESSAGE_STATUS_COMPLETED);
        AiMessageEntity assistant = message(ASSISTANT_MESSAGE_ID, "assistant", "请先检查边界。", "mock-model", AiConversationService.MESSAGE_STATUS_COMPLETED);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectById(ASSISTANT_MESSAGE_ID)).thenReturn(assistant);
        AiLearningProfileService.SubmissionAnalysisSignal signal = new AiLearningProfileService.SubmissionAnalysisSignal(
                300L,
                99L,
                "WRONG_ANSWER",
                "cpp",
                "sha256-code",
                "wrong_answer_boundary",
                List.of("boundary"),
                "safe summary",
                500L
        );
        when(learningProfileService.recordSubmissionAnalysis(eq(USER_ID), any(AiChatRequest.class), any(AiCompletion.class), eq(ASSISTANT_MESSAGE_ID)))
                .thenReturn(signal);

        handler.handle(job(true));

        ArgumentCaptor<AiChatRequest> requestCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        ArgumentCaptor<AiCompletion> completionCaptor = ArgumentCaptor.forClass(AiCompletion.class);
        InOrder order = inOrder(learningProfileService, memoryService, memoryUpdatePlanner);
        order.verify(learningProfileService).recordSubmissionAnalysis(eq(USER_ID), requestCaptor.capture(), completionCaptor.capture(), eq(ASSISTANT_MESSAGE_ID));
        order.verify(memoryService).extractAndSave(USER_ID, CONVERSATION_ID, ASSISTANT_MESSAGE_ID, "我提交后 WA 了。", "请先检查边界。");
        order.verify(memoryUpdatePlanner).afterTurn(eq(USER_ID), eq(CONVERSATION_ID), eq(ASSISTANT_MESSAGE_ID), any(AiChatRequest.class), any(AiCompletion.class), eq(signal));

        AiChatRequest request = requestCaptor.getValue();
        assertThat(request.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(request.problemId()).isEqualTo(99L);
        assertThat(request.submissionContext()).isNotNull();
        assertThat(request.submissionContext().submissionId()).isEqualTo(300L);
        assertThat(request.contestContext()).isNotNull();
        assertThat(request.contestContext().contestId()).isEqualTo(11L);
        assertThat(completionCaptor.getValue().content()).isEqualTo("请先检查边界。");
        assertThat(completionCaptor.getValue().provider()).isEqualTo("stored-message");
    }

    @Test
    void handlesTurnWithoutSelectedSubmission() {
        AiMessageEntity user = message(USER_MESSAGE_ID, "user", "请记住我喜欢提示。", null, AiConversationService.MESSAGE_STATUS_COMPLETED);
        AiMessageEntity assistant = message(ASSISTANT_MESSAGE_ID, "assistant", "以后我会先给提示。", "mock-model", AiConversationService.MESSAGE_STATUS_COMPLETED);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectById(ASSISTANT_MESSAGE_ID)).thenReturn(assistant);
        when(learningProfileService.recordSubmissionAnalysis(eq(USER_ID), any(AiChatRequest.class), any(AiCompletion.class), eq(ASSISTANT_MESSAGE_ID)))
                .thenReturn(null);

        handler.handle(job(false));

        ArgumentCaptor<AiChatRequest> requestCaptor = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(learningProfileService).recordSubmissionAnalysis(eq(USER_ID), requestCaptor.capture(), any(AiCompletion.class), eq(ASSISTANT_MESSAGE_ID));
        verify(memoryService).extractAndSave(USER_ID, CONVERSATION_ID, ASSISTANT_MESSAGE_ID, "请记住我喜欢提示。", "以后我会先给提示。");
        verify(memoryUpdatePlanner).afterTurn(eq(USER_ID), eq(CONVERSATION_ID), eq(ASSISTANT_MESSAGE_ID), any(AiChatRequest.class), any(AiCompletion.class), eq(null));
        assertThat(requestCaptor.getValue().submissionContext()).isNull();
    }

    @Test
    void missingMessageIsPermanentFailure() {
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(null);

        assertThatThrownBy(() -> handler.handle(job(true)))
                .isInstanceOf(AiMemoryJobPermanentFailure.class)
                .hasMessageContaining("AI message not found");
        verify(learningProfileService, never()).recordSubmissionAnalysis(any(), any(), any(), any());
    }

    @Test
    void unfinishedAssistantIsPermanentFailure() {
        AiMessageEntity user = message(USER_MESSAGE_ID, "user", "hello", null, AiConversationService.MESSAGE_STATUS_COMPLETED);
        AiMessageEntity assistant = message(ASSISTANT_MESSAGE_ID, "assistant", "", "mock-model", AiConversationService.MESSAGE_STATUS_RUNNING);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectById(ASSISTANT_MESSAGE_ID)).thenReturn(assistant);

        assertThatThrownBy(() -> handler.handle(job(false)))
                .isInstanceOf(AiMemoryJobPermanentFailure.class)
                .hasMessageContaining("not completed");
        verify(memoryService, never()).extractAndSave(any(), any(), any(), any(), any());
    }

    @Test
    void memoryExtractionFailureIsRetryable() {
        AiMessageEntity user = message(USER_MESSAGE_ID, "user", "hello", null, AiConversationService.MESSAGE_STATUS_COMPLETED);
        AiMessageEntity assistant = message(ASSISTANT_MESSAGE_ID, "assistant", "hi", "mock-model", AiConversationService.MESSAGE_STATUS_COMPLETED);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectById(ASSISTANT_MESSAGE_ID)).thenReturn(assistant);
        when(memoryService.extractAndSave(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> handler.handle(job(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider unavailable");
    }

    private AiMemoryJobEntity job(boolean withSubmission) {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setId(1L);
        job.setJobType(AiMemoryJobTypes.JOB_AI_AFTER_TURN_MEMORY_PROFILE);
        job.setPayloadJson("""
                {
                  "userId": "7",
                  "conversationId": "c-after-turn",
                  "userMessageId": "100",
                  "assistantMessageId": "200",
                  "problemId": "99",
                  "contestId": "11",
                  "contestRunId": "22",
                  "contestProblemId": "33"%s
                }
                """.formatted(withSubmission ? ",\n  \"submissionId\": \"300\"" : ""));
        return job;
    }

    private AiMessageEntity message(Long id, String role, String content, String model, String status) {
        AiMessageEntity message = new AiMessageEntity();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setUserId(USER_ID);
        message.setProblemId(99L);
        message.setContestId(11L);
        message.setContestRunId(22L);
        message.setContestProblemId(33L);
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setStatus(status);
        message.setCreatedAt(LocalDateTime.now());
        message.setUpdatedAt(LocalDateTime.now());
        return message;
    }
}
