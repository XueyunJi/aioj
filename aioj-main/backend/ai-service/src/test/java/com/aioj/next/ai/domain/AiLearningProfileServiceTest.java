package com.aioj.next.ai.domain;

import com.aioj.next.ai.agent.profile.ProfileAggregateJobProducer;
import com.aioj.next.ai.agent.profile.ProfileSignalIngestionService;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiSubmissionAnalysisEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiSubmissionAnalysisMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.ai.AiLearningProfileUpdateRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLearningProfileServiceTest {

    private ProfileSignalIngestionService signalIngestion;
    private ProfileAggregateJobProducer aggregateProducer;

    @Test
    void recordSubmissionAnalysisOmitsUnsafeTextAndCreatesCandidateProfile() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));

        AiCompletion completion = new AiCompletion("""
                主要问题是二分边界没有收缩到正确区间。

                ```cpp
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                    int n;
                    cin >> n;
                    return 0;
                }
                ```

                建议先用最小反例检查 check(mid) 的单调性。
                password: should-not-persist
                stdout:
                hidden output line
                token: should-not-persist
                """, "mock", "mock-model", 10, 20);

        service.recordSubmissionAnalysis(7L, request(), completion, 555L);

        ArgumentCaptor<AiLearningProfileEntity> profileCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        ArgumentCaptor<AiSubmissionAnalysisEntity> analysisCaptor = ArgumentCaptor.forClass(AiSubmissionAnalysisEntity.class);
        ArgumentCaptor<AiLearningProfileEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiLearningProfileEvidenceEntity.class);
        verify(profileMapper).insert(profileCaptor.capture());
        verify(analysisMapper).insert(analysisCaptor.capture());
        verify(evidenceMapper).insert(evidenceCaptor.capture());

        assertThat(profileCaptor.getValue().state).isEqualTo("CANDIDATE");
        assertEvidenceSafe(analysisCaptor.getValue().summary);
        assertEvidenceSafe(evidenceCaptor.getValue().summary);
        assertThat(analysisCaptor.getValue().codeHash).isEqualTo("sha256-submission");
        assertThat(evidenceCaptor.getValue().codeHash).isEqualTo("sha256-submission");
        verify(retrievalService).indexChunk(eq(7L), eq("submission_analysis"), eq("123"),
                argThat(this::isRetrievalTextSafe),
                any(AiRetrievalService.AiRetrievalChunkMetadata.class));
        verify(retrievalService).indexChunk(eq(7L), eq("profile_evidence"), eq("123"),
                argThat(this::isRetrievalTextSafe),
                any(AiRetrievalService.AiRetrievalChunkMetadata.class));
        verify(retrievalService).indexChunk(eq(7L), eq("learning_profile"), eq("101"),
                argThat(this::isRetrievalTextSafe),
                any(AiRetrievalService.AiRetrievalChunkMetadata.class));
    }

    @Test
    void listHidesDisabledProfilesEvenIfMapperReturnsThem() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity disabled = profile(10L, "wrong_answer_debugging", "DISABLED");
        disabled.disabledAt = LocalDateTime.now();
        AiLearningProfileEntity candidate = profile(11L, "time_limit_debugging", "CANDIDATE");
        AiLearningProfileEntity deleted = profile(12L, "deleted_debugging", "CANDIDATE");
        deleted.deletedAt = LocalDateTime.now();
        when(profileMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(disabled, candidate, deleted));
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThat(service.list(7L, null, null))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo("11");
                    assertThat(item.state()).isEqualTo("CANDIDATE");
                });
        assertThat(service.list(7L, null, "DISABLED")).isEmpty();
    }

    @Test
    void deleteSoftDeletesProfileAndRemovesRetrievalOwners() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), retrievalService);
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "CANDIDATE");
        AiLearningProfileEvidenceEntity evidence = evidence(202L, 10L, "123");
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(evidence));

        service.delete(7L, 10L);

        ArgumentCaptor<AiLearningProfileEntity> captor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertThat(captor.getValue().deletedAt).isNotNull();
        assertThat(captor.getValue().updatedAt).isEqualTo(captor.getValue().deletedAt);
        verify(retrievalService).deleteOwner(7L, "learning_profile", "10");
        verify(retrievalService).deleteOwner(7L, "profile_evidence", "202");
    }

    @Test
    void deleteMissingOrCrossUserProfileReturnsNotFound() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileService service = service(profileMapper, mock(AiLearningProfileEvidenceMapper.class), mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.delete(7L, 10L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Learning profile not found");
    }

    @Test
    void disableSetsStateDisabledAndTimestamp() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "CANDIDATE");
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.disable(7L, 10L);

        ArgumentCaptor<AiLearningProfileEntity> captor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertThat(captor.getValue().state).isEqualTo("DISABLED");
        assertThat(captor.getValue().disabledAt).isNotNull();
    }

    @Test
    void updateRejectsInvalidState() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileService service = service(profileMapper, mock(AiLearningProfileEvidenceMapper.class), mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile(10L, "wrong_answer_debugging", "CANDIDATE"));

        assertThatThrownBy(() -> service.update(7L, 10L, new AiLearningProfileUpdateRequest("BROKEN", null, null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Unsupported learning profile state");
    }

    @Test
    void disableLowersConfidenceByFactor() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "CANDIDATE");
        profile.confidence = BigDecimal.valueOf(0.8);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.disable(7L, 10L);

        // V3 P2-7: user distrust lowers profile confidence ×0.85 (merge WEAKEN semantics).
        ArgumentCaptor<AiLearningProfileEntity> captor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertThat(captor.getValue().state).isEqualTo("DISABLED");
        assertThat(captor.getValue().disabledAt).isNotNull();
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.6800");
    }

    @Test
    void updateToDisabledLowersConfidenceByFactor() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "ACTIVE");
        profile.confidence = BigDecimal.valueOf(0.8);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.update(7L, 10L, new AiLearningProfileUpdateRequest("DISABLED", null, "user distrust"));

        ArgumentCaptor<AiLearningProfileEntity> captor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertThat(captor.getValue().state).isEqualTo("DISABLED");
        assertThat(captor.getValue().disabledAt).isNotNull();
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.6800");
    }

    @Test
    void updateToActiveClearsDisabledAtAndBoostsConfidence() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "DISABLED");
        profile.disabledAt = LocalDateTime.now().minusDays(1);
        profile.confidence = BigDecimal.valueOf(0.5);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.update(7L, 10L, new AiLearningProfileUpdateRequest("ACTIVE", null, "user re-accepts"));

        // V3 P2-7: explicit re-activation clears the distrust marker (disabledAt — which also
        // restores list/recall visibility) and boosts confidence by +0.1 from its old value.
        // The disabled_at clear must go through UpdateWrapper.set(null) — updateById skips
        // null fields and would leave the column set in DB.
        ArgumentCaptor<UpdateWrapper<AiLearningProfileEntity>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(profileMapper).update(isNull(), wrapperCaptor.capture());
        verify(profileMapper, never()).updateById(any(AiLearningProfileEntity.class));
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("disabled_at = NULL");
        assertThat(profile.state).isEqualTo("ACTIVE");
        assertThat(profile.disabledAt).isNull();
        assertThat(profile.confidence).isEqualByComparingTo("0.6000");
    }

    @Test
    void updateToActiveBoostIsCappedAtOne() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "DISABLED");
        profile.disabledAt = LocalDateTime.now().minusDays(1);
        profile.confidence = BigDecimal.valueOf(0.95);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.update(7L, 10L, new AiLearningProfileUpdateRequest("ACTIVE", null, null));

        ArgumentCaptor<UpdateWrapper<AiLearningProfileEntity>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(profileMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("disabled_at = NULL");
        assertThat(profile.confidence).isEqualByComparingTo("1.0000");
    }

    @Test
    void updateToActiveWithoutDistrustKeepsConfidenceUntouched() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity profile = profile(10L, "wrong_answer_debugging", "ACTIVE");
        profile.confidence = BigDecimal.valueOf(0.7);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(profile);
        when(evidenceMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.update(7L, 10L, new AiLearningProfileUpdateRequest("ACTIVE", null, null));

        ArgumentCaptor<AiLearningProfileEntity> captor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertThat(captor.getValue().confidence).isEqualByComparingTo("0.7");
    }

    @Test
    void recordSubmissionAnalysisDoesNotDowngradeResolvedProfile() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        AiLearningProfileEntity resolved = profile(10L, "wrong_answer_binary_search", "RESOLVED");
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(resolved);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        verify(profileMapper, never()).updateById(any(AiLearningProfileEntity.class));
        verify(retrievalService).indexChunk(eq(7L), eq("submission_analysis"), eq("123"), any(), any(AiRetrievalService.AiRetrievalChunkMetadata.class));
    }

    @Test
    void recordSubmissionAnalysisDoesNotReuseDisabledProfileForEvidence() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        AiLearningProfileEntity disabled = profile(10L, "wrong_answer_binary_search", "DISABLED");
        disabled.disabledAt = LocalDateTime.now();
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(disabled);

        service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        verify(profileMapper, never()).updateById(any(AiLearningProfileEntity.class));
        verify(retrievalService).indexChunk(eq(7L), eq("submission_analysis"), eq("123"), any(), any(AiRetrievalService.AiRetrievalChunkMetadata.class));
    }

    @Test
    void recordSubmissionAnalysisDeduplicatesEvidenceForSameSubmission() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        AiLearningProfileEntity existingProfile = profile(10L, "wrong_answer_binary_search", "CANDIDATE");
        AiLearningProfileEvidenceEntity existingEvidence = evidence(202L, 10L, "123");
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingProfile);
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingAnalysis());
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingEvidence);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        verify(evidenceMapper).updateById(argThat((AiLearningProfileEvidenceEntity item) -> item.id.equals(202L) && item.sourceId.equals("123")));
        verify(retrievalService).deleteOwner(7L, "profile_evidence", "202");
        assertThat(existingProfile.evidenceCount).isEqualTo(1);
        assertThat(existingProfile.lastEvidenceAt).isEqualTo(existingEvidence.createdAt);
    }

    @Test
    void timeLimitAnalysisPersistsComplexityProfileEvidenceAndMetadata() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("time_limit_exceeded_complexity", true, false, List.of("time_limit_exceeded"), List.of("binary_search"), List.of(), List.of("time_complexity")));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext("TIME_LIMIT_EXCEEDED", "Time limit exceeded", List.of("binary_search")));
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));

        service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        ArgumentCaptor<AiSubmissionAnalysisEntity> analysisCaptor = ArgumentCaptor.forClass(AiSubmissionAnalysisEntity.class);
        ArgumentCaptor<AiRetrievalService.AiRetrievalChunkMetadata> metadataCaptor =
                ArgumentCaptor.forClass(AiRetrievalService.AiRetrievalChunkMetadata.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        verify(evidenceMapper).insert(any(AiLearningProfileEvidenceEntity.class));
        verify(retrievalService).indexChunk(eq(7L), eq("submission_analysis"), eq("123"), any(), metadataCaptor.capture());
        assertThat(analysisCaptor.getValue().rootCauseTags).contains("time_complexity");
        assertThat(metadataCaptor.getValue().algorithmKey()).isEqualTo("binary_search");
        assertThat(metadataCaptor.getValue().profileKey()).isEqualTo("time_limit_exceeded_complexity");
        assertThat(metadataCaptor.getValue().attributes())
                .containsEntry("masteryEvidence", false)
                .containsEntry("profileEligible", true);
        assertThat(metadataCaptor.getValue().attributes().get("complexityTags").toString()).contains("time_complexity");
    }

    @Test
    void compileErrorAnalysisDoesNotCreateProfileEvidence() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("compile_error_diagnostics", false, false, List.of("compile_error"), List.of(), List.of("syntax_error"), List.of()));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext("COMPILE_ERROR", "Compilation failed", List.of("binary_search")));

        AiLearningProfileService.SubmissionAnalysisSignal signal =
                service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        verify(analysisMapper).insert(any(AiSubmissionAnalysisEntity.class));
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        verify(profileMapper, never()).updateById(any(AiLearningProfileEntity.class));
        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        assertThat(signal.profileEligible()).isFalse();
        assertThat(signal.profileId()).isNull();
    }

    @Test
    void acceptedAnalysisStoresMasterySignalWithoutCreatingWeaknessProfile() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("accepted_binary_search", false, true, List.of("accepted"), List.of("binary_search"), List.of(), List.of()));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext("ACCEPTED", "Accepted", List.of("binary_search")));

        AiLearningProfileService.SubmissionAnalysisSignal signal =
                service.recordSubmissionAnalysis(7L, request(), completionWithoutCode(), 555L);

        ArgumentCaptor<AiSubmissionAnalysisEntity> analysisCaptor = ArgumentCaptor.forClass(AiSubmissionAnalysisEntity.class);
        verify(analysisMapper).insert(analysisCaptor.capture());
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        assertThat(signal.masteryEvidence()).isTrue();
        assertThat(signal.profileEligible()).isFalse();
        assertThat(analysisCaptor.getValue().summary).contains("masteryEvidence=true");
    }

    @Test
    void judgedSubmissionAnalysisDoesNotOverwriteManualAnalysis() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        AiSubmissionAnalysisEntity manual = existingAnalysis();
        manual.aiMessageId = 555L;
        manual.summary = "manual analysis should stay";
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(manual);

        AiLearningProfileService.SubmissionAnalysisSignal signal =
                service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.WRONG_ANSWER));

        assertThat(signal.safeSummary()).isEqualTo("manual analysis should stay");
        verify(analysisMapper, never()).updateById(any(AiSubmissionAnalysisEntity.class));
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        verify(retrievalService, never()).indexChunk(any(), any(), any(), any(), any());
        // P2-6: the manual-analysis skip path must not write sidecar signals either.
        verify(signalIngestion, never()).recordJudgedSubmissionSignals(any(), any(), any());
        verify(aggregateProducer, never()).enqueueForUser(any());
    }

    @Test
    void judgedWrongAnswerRecordsWeaknessSignalAndEnqueuesAggregation() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(signalIngestion.recordJudgedSubmissionSignals(any(), any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));

        AiLearningProfileService.SubmissionAnalysisSignal signal =
                service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.WRONG_ANSWER));

        assertThat(signal).isNotNull();
        ArgumentCaptor<List> proposalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(signalIngestion).recordJudgedSubmissionSignals(eq(7L), eq(123L), proposalsCaptor.capture());
        List<ProfileSignalIngestionService.SignalProposal> proposals = proposalsCaptor.getValue();
        assertThat(proposals).hasSize(1);
        ProfileSignalIngestionService.SignalProposal proposal = proposals.get(0);
        assertThat(proposal.signalType()).isEqualTo("WEAKNESS");
        assertThat(proposal.polarity()).isEqualTo("NEGATIVE");
        assertThat(proposal.knowledgeNode()).isEqualTo("binary_search");
        assertThat(proposal.score()).isEqualTo(0.62);
        assertThat(proposal.signal()).contains("wrong_answer on「Binary Search Practice」").contains("wrong_answer");
        verify(aggregateProducer).enqueueForUser(7L);
    }

    @Test
    void judgedSignalKnowledgeNodeFallsBackToProfileKeySubject() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("time_limit_exceeded_complexity", true, false, List.of("time_limit_exceeded"), List.of(), List.of(), List.of("time_complexity")));
        when(problemServiceClient.aiSubmissionContext(any()))
                .thenReturn(submissionContext("TIME_LIMIT_EXCEEDED", "Time limit exceeded", List.of()));
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(signalIngestion.recordJudgedSubmissionSignals(any(), any(), any())).thenReturn(1);
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));

        service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.TIME_LIMIT_EXCEEDED));

        ArgumentCaptor<List> proposalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(signalIngestion).recordJudgedSubmissionSignals(eq(7L), eq(123L), proposalsCaptor.capture());
        List<ProfileSignalIngestionService.SignalProposal> proposals = proposalsCaptor.getValue();
        assertThat(proposals).hasSize(1);
        // No algorithm/problem tag: profileKey "time_limit_exceeded_complexity" minus the status prefix.
        assertThat(proposals.get(0).knowledgeNode()).isEqualTo("complexity");
    }

    @Test
    void judgedAcceptedMasteryRecordsMasterySignal() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("accepted_binary_search", false, true, List.of("accepted"), List.of("binary_search"), List.of(), List.of()));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext("ACCEPTED", "Accepted", List.of("binary_search")));
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(signalIngestion.recordJudgedSubmissionSignals(any(), any(), any())).thenReturn(1);

        service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.ACCEPTED));

        ArgumentCaptor<List> proposalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(signalIngestion).recordJudgedSubmissionSignals(eq(7L), eq(123L), proposalsCaptor.capture());
        List<ProfileSignalIngestionService.SignalProposal> proposals = proposalsCaptor.getValue();
        assertThat(proposals).hasSize(1);
        ProfileSignalIngestionService.SignalProposal proposal = proposals.get(0);
        assertThat(proposal.signalType()).isEqualTo("MASTERY");
        assertThat(proposal.polarity()).isEqualTo("POSITIVE");
        assertThat(proposal.knowledgeNode()).isEqualTo("binary_search");
        assertThat(proposal.score()).isEqualTo(0.62);
        assertThat(proposal.signal()).contains("accepted on「Binary Search Practice」");
        verify(aggregateProducer).enqueueForUser(7L);
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
    }

    @Test
    void judgedCompileErrorRecordsNoSignal() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService,
                analysis("compile_error_diagnostics", false, false, List.of("compile_error"), List.of(), List.of("syntax_error"), List.of()));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext("COMPILE_ERROR", "Compilation failed", List.of("binary_search")));
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.COMPILE_ERROR));

        verify(analysisMapper).insert(any(AiSubmissionAnalysisEntity.class));
        verify(signalIngestion, never()).recordJudgedSubmissionSignals(any(), any(), any());
        verify(aggregateProducer, never()).enqueueForUser(any());
    }

    @Test
    void judgedSignalSidecarFailureDoesNotBreakMainFlow() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiRetrievalService retrievalService = mock(AiRetrievalService.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService);
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(submissionContext());
        when(analysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(signalIngestion.recordJudgedSubmissionSignals(any(), any(), any()))
                .thenThrow(new RuntimeException("signal db down"));
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));

        AiLearningProfileService.SubmissionAnalysisSignal signal =
                service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.WRONG_ANSWER));

        assertThat(signal).isNotNull();
        assertThat(signal.profileKey()).isEqualTo("wrong_answer_binary_search");
        assertThat(signal.profileId()).isEqualTo(101L);
        verify(analysisMapper).insert(any(AiSubmissionAnalysisEntity.class));
        verify(aggregateProducer, never()).enqueueForUser(any());
    }

    @Test
    void judgedSubmissionAnalysisRejectsUnsafeActiveContestContext() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
        AiSubmissionAnalysisMapper analysisMapper = mock(AiSubmissionAnalysisMapper.class);
        ProblemServiceClient problemServiceClient = mock(ProblemServiceClient.class);
        AiLearningProfileService service = service(profileMapper, evidenceMapper, analysisMapper,
                problemServiceClient, mock(AiRetrievalService.class));
        when(problemServiceClient.aiSubmissionContext(any())).thenReturn(unsafeActiveContestContext());

        assertThatThrownBy(() -> service.recordJudgedSubmissionAnalysis(judgedEvent(SubmissionStatus.WRONG_ANSWER)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("unsafe fields");
        verify(analysisMapper, never()).insert(any(AiSubmissionAnalysisEntity.class));
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
    }

    @Test
    void filterRecallableRetrievalHitsDropsResolvedDisabledAndUnknownProfiles() {
        AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
        AiLearningProfileService service = service(profileMapper, mock(AiLearningProfileEvidenceMapper.class), mock(AiSubmissionAnalysisMapper.class), mock(ProblemServiceClient.class), mock(AiRetrievalService.class));
        AiLearningProfileEntity resolved = profile(10L, "wrong_answer_binary_search", "RESOLVED");
        AiLearningProfileEntity active = profile(11L, "time_limit_debugging", "ACTIVE");
        AiLearningProfileEntity disabled = profile(12L, "compile_error_debugging", "DISABLED");
        disabled.disabledAt = LocalDateTime.now();
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(resolved, active, disabled, null);

        List<AiRetrievalService.AiRetrievalHit> filtered = service.filterRecallableRetrievalHits(7L, List.of(
                hit("learning_profile", "10", "wrong_answer_binary_search"),
                hit("profile_evidence", "202", "time_limit_debugging"),
                hit("submission_analysis", "303", "compile_error_debugging"),
                hit("submission_analysis", "404", "unknown_key"),
                hit("message", "505", null)
        ));

        assertThat(filtered).extracting(AiRetrievalService.AiRetrievalHit::ownerId)
                .containsExactly("202", "505");
    }

    private void assertEvidenceSafe(String summary) {
        assertThat(summary)
                .contains("submit-ready code omitted")
                .contains("raw output omitted")
                .contains("secret-like text omitted")
                .contains("二分边界")
                .contains("最小反例")
                .doesNotContain("```")
                .doesNotContain("#include")
                .doesNotContain("using namespace")
                .doesNotContain("int main")
                .doesNotContain("cin >>")
                .doesNotContain("return 0")
                .doesNotContain("hidden output line")
                .doesNotContain("should-not-persist");
    }

    private boolean isRetrievalTextSafe(String text) {
        return text.contains("submit-ready code omitted")
                && text.contains("raw output omitted")
                && text.contains("secret-like text omitted")
                && !text.contains("#include")
                && !text.contains("int main")
                && !text.contains("cin >>")
                && !text.contains("hidden output line")
                && !text.contains("should-not-persist");
    }

    private AiLearningProfileService service(AiLearningProfileMapper profileMapper,
                                             AiLearningProfileEvidenceMapper evidenceMapper,
                                             AiSubmissionAnalysisMapper analysisMapper,
                                             ProblemServiceClient problemServiceClient,
                                             AiRetrievalService retrievalService) {
        return service(profileMapper, evidenceMapper, analysisMapper, problemServiceClient, retrievalService, defaultAnalysis());
    }

    private AiLearningProfileService service(AiLearningProfileMapper profileMapper,
                                             AiLearningProfileEvidenceMapper evidenceMapper,
                                             AiSubmissionAnalysisMapper analysisMapper,
                                             ProblemServiceClient problemServiceClient,
                                             AiRetrievalService retrievalService,
                                             AiStructuredSubmissionAnalysisService.AnalysisResult analysis) {
        AiStructuredSubmissionAnalysisService structuredAnalysisService = mock(AiStructuredSubmissionAnalysisService.class);
        when(structuredAnalysisService.analyze(any(), any(), any(), any())).thenReturn(analysis);
        this.signalIngestion = mock(ProfileSignalIngestionService.class);
        this.aggregateProducer = mock(ProfileAggregateJobProducer.class);
        return new AiLearningProfileService(
                profileMapper,
                evidenceMapper,
                analysisMapper,
                problemServiceClient,
                retrievalService,
                structuredAnalysisService,
                new ObjectMapper(),
                signalIngestion,
                aggregateProducer
        );
    }

    private AiStructuredSubmissionAnalysisService.AnalysisResult defaultAnalysis() {
        return analysis("wrong_answer_binary_search", true, false, List.of("wrong_answer"), List.of("binary_search"), List.of("boundary", "monotonicity"), List.of());
    }

    private AiStructuredSubmissionAnalysisService.AnalysisResult analysis(
            String profileKey,
            boolean profileEligible,
            boolean masteryEvidence,
            List<String> rootCauseTags,
            List<String> algorithmTags,
            List<String> bugPatternTags,
            List<String> complexityTags
    ) {
        return new AiStructuredSubmissionAnalysisService.AnalysisResult(
                """
                        主要问题是二分边界没有收缩到正确区间。
                        ```cpp
                        int main() { return 0; }
                        ```
                        token: should-not-persist
                        stdout:
                        hidden output line
                        """,
                rootCauseTags,
                algorithmTags,
                bugPatternTags,
                complexityTags,
                List.of("用最小反例检查 check(mid) 的单调性。"),
                List.of("Wrong answer on sample 2"),
                0.62,
                profileKey,
                "提交分析候选弱点：WRONG_ANSWER / Binary Search Practice",
                profileEligible,
                masteryEvidence,
                false
        );
    }

    private AiChatRequest request() {
        return new AiChatRequest(
                "conversation-1",
                99L,
                "我提交了，答案错误",
                "assist",
                null,
                null,
                null,
                "client-1",
                null,
                null,
                new AiChatRequest.SubmissionContext(123L, "DEBUG", true, null)
        );
    }

    private AiCompletion completionWithoutCode() {
        return new AiCompletion("主要问题是二分边界没有收缩到正确区间。", "mock", "mock-model", 10, 20);
    }

    private AiLearningProfileEntity profile(Long id, String key, String state) {
        AiLearningProfileEntity profile = new AiLearningProfileEntity();
        profile.id = id;
        profile.userId = 7L;
        profile.category = "weakness";
        profile.profileKey = key;
        profile.label = "候选弱点";
        profile.state = state;
        profile.evidenceCount = 0;
        profile.createdAt = LocalDateTime.now().minusDays(1);
        profile.updatedAt = profile.createdAt;
        return profile;
    }

    private AiLearningProfileEvidenceEntity evidence(Long id, Long profileId, String sourceId) {
        AiLearningProfileEvidenceEntity evidence = new AiLearningProfileEvidenceEntity();
        evidence.id = id;
        evidence.userId = 7L;
        evidence.profileId = profileId;
        evidence.evidenceType = "SUBMISSION_ANALYSIS";
        evidence.sourceType = "SUBMISSION";
        evidence.sourceId = sourceId;
        evidence.summary = "safe summary";
        evidence.createdAt = LocalDateTime.now().minusMinutes(5);
        return evidence;
    }

    private AiSubmissionAnalysisEntity existingAnalysis() {
        AiSubmissionAnalysisEntity analysis = new AiSubmissionAnalysisEntity();
        analysis.id = 303L;
        analysis.userId = 7L;
        analysis.submissionId = 123L;
        return analysis;
    }

    private AiRetrievalService.AiRetrievalHit hit(String ownerType, String ownerId, String profileKey) {
        return new AiRetrievalService.AiRetrievalHit(
                ownerType,
                ownerId,
                "safe candidate weakness",
                1.0,
                List.of("semantic_match"),
                99L,
                123L,
                null,
                null,
                null,
                "binary_search",
                profileKey,
                AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE,
                profileKey == null ? Map.of() : Map.of("profileKey", profileKey)
        );
    }

    private AiJudgedSubmissionEventRequest judgedEvent(SubmissionStatus status) {
        return new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                status,
                "cpp",
                null,
                null,
                null,
                Instant.now()
        );
    }

    private AiSubmissionContextResponse unsafeActiveContestContext() {
        return new AiSubmissionContextResponse(
                123L,
                7L,
                99L,
                1L,
                2L,
                3L,
                "CONTEST",
                true,
                "cpp",
                "WRONG_ANSWER",
                "Wrong answer",
                "raw stdout should not reach AI",
                null,
                0,
                12,
                2048,
                0.0,
                100.0,
                false,
                "int main() { return 0; }",
                "sha256-submission",
                List.of(),
                new AiProblemContextResponse(
                        99L,
                        1L,
                        2L,
                        3L,
                        "Contest Problem",
                        "MEDIUM",
                        "Safe summary",
                        null,
                        List.of("binary_search"),
                        List.of("n <= 100000"),
                        List.of(),
                        1000,
                        262144,
                        "CONTEST",
                        Instant.now()
                ),
                Instant.now(),
                Instant.now(),
                "Active contest policy"
        );
    }

    private AiSubmissionContextResponse submissionContext() {
        return submissionContext("WRONG_ANSWER", "Wrong answer on sample 2", List.of("binary_search"));
    }

    private AiSubmissionContextResponse submissionContext(String status, String judgeMessage, List<String> tags) {
        return new AiSubmissionContextResponse(
                123L,
                7L,
                99L,
                null,
                null,
                null,
                "PRACTICE",
                false,
                "cpp",
                status,
                judgeMessage,
                null,
                null,
                0,
                12,
                2048,
                0.0,
                100.0,
                true,
                "int main() { return 0; }",
                "sha256-submission",
                List.of(),
                new AiProblemContextResponse(
                        99L,
                        null,
                        null,
                        null,
                        "Binary Search Practice",
                        "MEDIUM",
                        "Find the answer with binary search.",
                        null,
                        tags,
                        List.of("n <= 100000"),
                        List.of(),
                        1000,
                        262144,
                        "PROBLEM",
                        Instant.now()
                ),
                Instant.now(),
                Instant.now(),
                null
        );
    }
}
