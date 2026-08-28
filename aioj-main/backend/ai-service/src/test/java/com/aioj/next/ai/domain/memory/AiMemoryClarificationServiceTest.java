package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiMemoryCandidateActionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMemoryClarificationServiceTest {
    @Mock
    private AiMemoryCandidateService candidateService;

    private AiMemoryClarificationService service;

    @BeforeEach
    void setUp() {
        service = new AiMemoryClarificationService(
                candidateService,
                new AiMemoryEventPayloadSanitizer(),
                new MemoryQualityGate()
        );
    }

    @Test
    void planClarificationUsesSafePreviewAndExistingClarificationShape() {
        AiMemoryCandidateEntity candidate = candidate(701L);
        candidate.category = "WEAKNESS";
        candidate.canonicalText = """
                我总是忘记检查二分边界。
                ```cpp
                int main() { return 0; }
                ```
                token=sk-secret-value
                stdout: hidden output
                """;
        when(candidateService.nextClarificationCandidate(7L)).thenReturn(Optional.of(candidate));

        var plan = service.planClarification(7L, "c-chat");

        assertThat(plan).isPresent();
        assertThat(plan.get().candidateId()).isEqualTo(701L);
        assertThat(plan.get().clarification().id()).isEqualTo("memory_candidate_701");
        assertThat(plan.get().clarification().input().kind()).isEqualTo("mixed");
        assertThat(plan.get().clarification().input().allowCustom()).isTrue();
        assertThat(plan.get().clarification().options()).extracting("label")
                .containsExactly("记住", "不记", "稍后处理");
        assertThat(plan.get().clarification().prompt())
                .contains("我总是忘记检查二分边界")
                .doesNotContain("int main", "sk-secret-value", "token", "stdout", "hidden output");
    }

    @Test
    void markAskedDelegatesToCandidateService() {
        var plan = new AiMemoryClarificationService.PlannedClarification(
                701L,
                "c-chat",
                new AiCompletion.Clarification(
                        "memory_candidate_701",
                        "confirm",
                        "确认学习记忆",
                        "需要记住吗？",
                        AiCompletion.ClarificationInput.empty(),
                        List.of(),
                        "ask_user",
                        null
                )
        );

        service.markAsked(7L, plan, 900L);

        verify(candidateService).markAwaitingClarification(7L, 701L, "memory_candidate_701", "c-chat", 900L);
    }

    @Test
    void confirmAnswerAcceptsCandidate() {
        AiMemoryCandidateEntity candidate = candidate(702L);
        when(candidateService.findByClarificationRequest(7L, "memory_candidate_702"))
                .thenReturn(Optional.of(candidate));

        var result = service.applyAnswer(7L, "c-chat", answer("memory_candidate_702", "记住", List.of("记住"), ""));

        assertThat(result.action()).isEqualTo("CONFIRM");
        verify(candidateService).accept(eq(7L), eq(702L), any(AiMemoryCandidateActionRequest.class));
    }

    @Test
    void rejectAnswerRejectsCandidate() {
        AiMemoryCandidateEntity candidate = candidate(703L);
        when(candidateService.findByClarificationRequest(7L, "memory_candidate_703"))
                .thenReturn(Optional.of(candidate));

        var result = service.applyAnswer(7L, "c-chat", answer("memory_candidate_703", "不记", List.of("不记"), ""));

        assertThat(result.action()).isEqualTo("REJECT");
        verify(candidateService).reject(7L, 703L, "memory_clarification_rejected");
    }

    @Test
    void customAnswerAcceptsCandidateWithSafeEditedText() {
        AiMemoryCandidateEntity candidate = candidate(704L);
        when(candidateService.findByClarificationRequest(7L, "memory_candidate_704"))
                .thenReturn(Optional.of(candidate));

        var result = service.applyAnswer(7L, "c-chat", answer(
                "memory_candidate_704",
                "我希望你记住：我更喜欢先看边界条件提示",
                List.of(),
                "我更喜欢先看边界条件提示"
        ));

        assertThat(result.action()).isEqualTo("UPDATE");
        ArgumentCaptor<AiMemoryCandidateActionRequest> requestCaptor = ArgumentCaptor.forClass(AiMemoryCandidateActionRequest.class);
        verify(candidateService).accept(eq(7L), eq(704L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().canonicalText()).isEqualTo("我更喜欢先看边界条件提示");
    }

    @Test
    void unsafeCustomAnswerReturnsToManualReviewWithoutAccepting() {
        AiMemoryCandidateEntity candidate = candidate(705L);
        when(candidateService.findByClarificationRequest(7L, "memory_candidate_705"))
                .thenReturn(Optional.of(candidate));

        var result = service.applyAnswer(7L, "c-chat", answer(
                "memory_candidate_705",
                "token=sk-secret-value",
                List.of(),
                "token=sk-secret-value"
        ));

        assertThat(result.action()).isEqualTo("SKIP");
        verify(candidateService, never()).accept(any(), any(), any());
        verify(candidateService).returnToNeedsConfirmation(7L, 705L, "UNUSABLE", "memory_clarification_answer_blank");
    }

    @Test
    void skipAnswerReturnsCandidateToNeedsConfirmationWithoutRepeatAsk() {
        AiMemoryCandidateEntity candidate = candidate(706L);
        when(candidateService.findByClarificationRequest(7L, "memory_candidate_706"))
                .thenReturn(Optional.of(candidate));

        var result = service.applyAnswer(7L, "c-chat", answer("memory_candidate_706", "稍后处理", List.of("稍后处理"), ""));

        assertThat(result.action()).isEqualTo("SKIP");
        verify(candidateService).returnToNeedsConfirmation(7L, 706L, "SKIPPED", "memory_clarification_skipped");
    }

    private static AiMemoryCandidateEntity candidate(Long id) {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = id;
        candidate.userId = 7L;
        candidate.category = "PREFERENCE";
        candidate.memoryKey = "guidance_preference";
        candidate.canonicalText = "先给提示";
        candidate.valueJson = "{}";
        candidate.scopeType = "GLOBAL";
        candidate.evidenceType = "EXPLICIT";
        candidate.extractionConfidence = BigDecimal.valueOf(0.82);
        candidate.writeScore = BigDecimal.valueOf(0.82);
        candidate.isLongTerm = Boolean.TRUE;
        candidate.isProblemSpecific = Boolean.FALSE;
        candidate.isHypothetical = Boolean.FALSE;
        candidate.isQuoted = Boolean.FALSE;
        candidate.needsConfirmation = Boolean.TRUE;
        candidate.qualityFlags = "[]";
        candidate.ambiguityFlags = "[]";
        candidate.sourceConversationId = "c-chat";
        candidate.sourceMessageId = 200L;
        candidate.status = "NEEDS_CONFIRMATION";
        candidate.createdAt = LocalDateTime.now();
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }

    private static AiChatRequest.ClarificationAnswer answer(
            String requestId,
            String answerText,
            List<String> selected,
            String customText
    ) {
        return new AiChatRequest.ClarificationAnswer(
                requestId,
                "需要记住吗？",
                answerText,
                selected,
                customText
        );
    }
}
