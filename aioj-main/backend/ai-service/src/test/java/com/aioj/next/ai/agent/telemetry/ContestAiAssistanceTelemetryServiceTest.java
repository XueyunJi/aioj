package com.aioj.next.ai.agent.telemetry;

import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.guard.GuardVerdict;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.policy.PolicySnapshotService;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceModelUsageEntity;
import com.aioj.next.ai.persistence.entity.AiContestAssistanceTurnEntity;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceModelUsageMapper;
import com.aioj.next.ai.persistence.mapper.AiContestAssistanceTurnMapper;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.contest.ContestAiPolicyMode;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemOccurrence;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.aioj.next.contract.problem.ProblemVisibility;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestAiAssistanceTelemetryServiceTest {
    @Mock private AiContestAssistanceTurnMapper turnMapper;
    @Mock private AiContestAssistanceModelUsageMapper usageMapper;
    @Mock private ContestAssistanceIntentJudge intentJudge;

    private ContestAiAssistanceTelemetryService service;

    @BeforeEach
    void setUp() {
        service = new ContestAiAssistanceTelemetryService(turnMapper, usageMapper, intentJudge);
    }

    @Test
    void beginCreatesOneCanonicalLedgerRowOnlyForTrustedAttributedRun() {
        AiTurnEntity turn = turn("turn-1", LocalDateTime.now());
        AiConversationEntity conversation = conversation();
        RunningContestParticipation participation = new RunningContestParticipation(
                9L, 3L, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        when(turnMapper.insert(any(AiContestAssistanceTurnEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiContestAssistanceTurnEntity.class).setId(77L);
            return 1;
        });

        ContestAiAssistanceTelemetryService.TrackingContext context = service.begin(turn, conversation, participation);

        assertThat(context).isEqualTo(new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1"));
        ArgumentCaptor<AiContestAssistanceTurnEntity> entity = ArgumentCaptor.forClass(AiContestAssistanceTurnEntity.class);
        verify(turnMapper).insert(entity.capture());
        assertThat(entity.getValue().getContestId()).isEqualTo(9L);
        assertThat(entity.getValue().getContestRunId()).isEqualTo(3L);
        assertThat(entity.getValue().getConversationId()).isEqualTo("c-1");
        assertThat(entity.getValue().getIntentStatus()).isEqualTo(ContestAssistanceIntentJudge.Status.PENDING.name());

        assertThat(service.begin(turn, conversation, null).active()).isFalse();
        verify(turnMapper, times(1)).insert(any(AiContestAssistanceTurnEntity.class));
    }

    @Test
    void duplicateTurnReusesExistingLedgerInsteadOfCreatingAnotherTurn() {
        AiContestAssistanceTurnEntity existing = new AiContestAssistanceTurnEntity();
        existing.setId(88L);
        existing.setTurnId("turn-1");
        when(turnMapper.insert(any(AiContestAssistanceTurnEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate turn"));
        when(turnMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ContestAiAssistanceTelemetryService.TrackingContext context = service.begin(
                turn("turn-1", LocalDateTime.now()), conversation(),
                new RunningContestParticipation(9L, 3L, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60)));

        assertThat(context).isEqualTo(new ContestAiAssistanceTelemetryService.TrackingContext(88L, "turn-1"));
        verify(usageMapper, never()).insert(any(AiContestAssistanceModelUsageEntity.class));
    }

    @Test
    void successfulTurnRecordsIntentUsageThenOneTerminalInterceptionStatus() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");
        when(intentJudge.assess(any(), any())).thenReturn(new ContestAssistanceIntentJudge.Judgement(
                ContestAssistanceIntentJudge.InterceptType.PUBLIC_FULL_CODE_REQUEST,
                ContestAssistanceIntentJudge.Status.COMPLETED,
                new ModelUsage("deepseek", "intent", 4, 2)));

        service.finishSuccessful(context, AiTurnService.STATUS_COMPLETED, "give full code", List.of(
                new ContestAssistanceIntentJudge.Candidate("PUBLIC", "MESSAGE_OR_CONTEXT_FINGERPRINT")
        ));

        ArgumentCaptor<AiContestAssistanceModelUsageEntity> usage =
                ArgumentCaptor.forClass(AiContestAssistanceModelUsageEntity.class);
        verify(usageMapper).insert(usage.capture());
        assertThat(usage.getValue().getUsageSource())
                .isEqualTo(ContestAiAssistanceTelemetryService.SOURCE_INTENT_JUDGE);
        assertThat(usage.getValue().getPromptTokens()).isEqualTo(4);
        verify(turnMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void trustedPrivateMessageFingerprintCorrectsCompletedIntentFalseNegativeOnTheSameLedgerRow() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");
        when(intentJudge.assess(any(), any())).thenReturn(new ContestAssistanceIntentJudge.Judgement(
                ContestAssistanceIntentJudge.InterceptType.NONE,
                ContestAssistanceIntentJudge.Status.COMPLETED,
                new ModelUsage("deepseek", "intent", 4, 2)));

        service.finishSuccessful(context, AiTurnService.STATUS_COMPLETED, "private question", List.of(
                new ContestAssistanceIntentJudge.Candidate("PRIVATE",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_MESSAGE_FINGERPRINT)
        ));

        ArgumentCaptor<UpdateWrapper> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(turnMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().values())
                .contains(ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION.name(),
                        ContestAssistanceIntentJudge.Status.COMPLETED.name());
        verify(usageMapper).insert(any(AiContestAssistanceModelUsageEntity.class));
    }

    @Test
    void trustedPrivateMessageFingerprintKeepsPrivatePriorityOverACompletedPublicCodeClassification() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");
        when(intentJudge.assess(any(), any())).thenReturn(new ContestAssistanceIntentJudge.Judgement(
                ContestAssistanceIntentJudge.InterceptType.PUBLIC_FULL_CODE_REQUEST,
                ContestAssistanceIntentJudge.Status.COMPLETED,
                new ModelUsage("deepseek", "intent", 4, 2)));

        service.finishSuccessful(context, AiTurnService.STATUS_COMPLETED, "private question", List.of(
                new ContestAssistanceIntentJudge.Candidate("PRIVATE",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_MESSAGE_FINGERPRINT)
        ));

        ArgumentCaptor<UpdateWrapper> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(turnMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().values())
                .contains(ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION.name())
                .doesNotContain(ContestAssistanceIntentJudge.InterceptType.PUBLIC_FULL_CODE_REQUEST.name());
    }

    @Test
    void privateContextMatchDoesNotUpgradeACompletedIntentResult() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");
        when(intentJudge.assess(any(), any())).thenReturn(new ContestAssistanceIntentJudge.Judgement(
                ContestAssistanceIntentJudge.InterceptType.NONE,
                ContestAssistanceIntentJudge.Status.COMPLETED,
                new ModelUsage("deepseek", "intent", 4, 2)));

        service.finishSuccessful(context, AiTurnService.STATUS_COMPLETED, "unrelated follow-up", List.of(
                new ContestAssistanceIntentJudge.Candidate("PRIVATE",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_CONTEXT_FINGERPRINT)
        ));

        ArgumentCaptor<UpdateWrapper> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(turnMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().values())
                .contains(ContestAssistanceIntentJudge.InterceptType.NONE.name(),
                        ContestAssistanceIntentJudge.Status.COMPLETED.name())
                .doesNotContain(ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION.name());
    }

    @Test
    void unavailableIntentJudgeIsNotUpgradedByTrustedPrivateMessageFingerprint() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");
        when(intentJudge.assess(any(), any())).thenReturn(ContestAssistanceIntentJudge.Judgement.unavailable());

        service.finishSuccessful(context, AiTurnService.STATUS_COMPLETED, "private question", List.of(
                new ContestAssistanceIntentJudge.Candidate("PRIVATE",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_MESSAGE_FINGERPRINT)
        ));

        ArgumentCaptor<UpdateWrapper> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(turnMapper, times(2)).update(isNull(), update.capture());
        assertThat(update.getAllValues().get(1).getParamNameValuePairs().values())
                .contains(ContestAssistanceIntentJudge.InterceptType.UNAVAILABLE.name(),
                        ContestAssistanceIntentJudge.Status.UNAVAILABLE.name())
                .doesNotContain(ContestAssistanceIntentJudge.InterceptType.PRIVATE_CONTEST_QUESTION.name());
    }

    @Test
    void missingProviderUsageIsStoredAsMissingAndMarksTheTurnPartial() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");

        service.recordUsage(context, "AGENT_PRIMARY_1",
                ContestAiAssistanceTelemetryService.SOURCE_AGENT_PRIMARY,
                new ModelUsage("deepseek", "deepseek-v4-pro", 0, 0, false));

        ArgumentCaptor<AiContestAssistanceModelUsageEntity> usage =
                ArgumentCaptor.forClass(AiContestAssistanceModelUsageEntity.class);
        verify(usageMapper).insert(usage.capture());
        assertThat(usage.getValue().getUsageStatus())
                .isEqualTo(ContestAiAssistanceTelemetryService.USAGE_MISSING);
        verify(turnMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void failedProviderTurnNeverInvokesIntentJudgeAndMarksTokenAccountingPartial() {
        ContestAiAssistanceTelemetryService.TrackingContext context =
                new ContestAiAssistanceTelemetryService.TrackingContext(77L, "turn-1");

        service.finishFailed(context, AiTurnService.STATUS_FAILED_RETRYABLE);

        verify(intentJudge, never()).assess(any(), any());
        verify(turnMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void clientProblemHintBecomesIntentCandidateOnlyAfterServerSnapshotMatchInAttributedRun() {
        RunningContestParticipation attributed = new RunningContestParticipation(
                9L, 3L, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        RunningContestProblemStatement publicProblem = new RunningContestProblemStatement(
                101L, "server-only statement", 9L, 3L, 301L, ProblemVisibility.PUBLIC,
                ContestAiPolicyMode.DEFAULT, null,
                List.of(new RunningContestProblemOccurrence(9L, 3L, 301L)));
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", null, List.of(3L), "{}", "", List.of(publicProblem));
        AiConversationEntity conversation = conversation();
        AiChatRequest trustedHint = new AiChatRequest("c-1", 101L, "give full code", null, null, null, null,
                "client-1", null, null, null);
        AiChatRequest unknownHint = new AiChatRequest("c-1", 999L, "give full code", null, null, null, null,
                "client-2", null, null, null);

        assertThat(service.candidates(trustedHint, conversation, snapshot, attributed,
                GuardVerdict.pass(), GuardVerdict.pass()))
                .containsExactly(new ContestAssistanceIntentJudge.Candidate("PUBLIC",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_TRUSTED_ENTRY_CONTEXT));
        assertThat(service.candidates(unknownHint, conversation, snapshot, attributed,
                GuardVerdict.pass(), GuardVerdict.pass())).isEmpty();
    }

    @Test
    void directMessageAndRuntimeFingerprintCandidatesKeepTheirEvidenceSourcesSeparate() {
        RunningContestParticipation attributed = new RunningContestParticipation(
                9L, 3L, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        GuardVerdict privateMessageMatch = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(101L, 9L, 3L, 301L, "PRIVATE", "DEFAULT")), 1.0);
        GuardVerdict publicRuntimeMatch = GuardVerdict.constrain(List.of(
                new GuardDecisionRecorder.MatchedProblemRef(102L, 9L, 3L, 302L, "PUBLIC", "DEFAULT")), 1.0);
        PolicySnapshotService.PolicySnapshot snapshot = new PolicySnapshotService.PolicySnapshot(
                "ps-1", null, List.of(3L), "{}", "", List.of());

        assertThat(service.candidates(null, conversation(), snapshot, attributed,
                privateMessageMatch, publicRuntimeMatch)).containsExactly(
                new ContestAssistanceIntentJudge.Candidate("PRIVATE",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_MESSAGE_FINGERPRINT),
                new ContestAssistanceIntentJudge.Candidate("PUBLIC",
                        ContestAssistanceIntentJudge.Candidate.SOURCE_CONTEXT_FINGERPRINT)
        );
    }

    private AiTurnEntity turn(String id, LocalDateTime createdAt) {
        AiTurnEntity entity = new AiTurnEntity();
        entity.setId(id);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private AiConversationEntity conversation() {
        AiConversationEntity entity = new AiConversationEntity();
        entity.setId("c-1");
        entity.setUserId(7L);
        return entity;
    }
}
