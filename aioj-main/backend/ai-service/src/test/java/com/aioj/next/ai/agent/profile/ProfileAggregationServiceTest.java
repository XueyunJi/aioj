package com.aioj.next.ai.agent.profile;

import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningProfileEvidenceEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileEvidenceMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileAggregationServiceTest {

    private final AiProfileSignalMapper signalMapper = mock(AiProfileSignalMapper.class);
    private final AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
    private final AiLearningProfileEvidenceMapper evidenceMapper = mock(AiLearningProfileEvidenceMapper.class);
    private final ProfileAggregationService service =
            new ProfileAggregationService(signalMapper, profileMapper, evidenceMapper, new ObjectMapper());

    @Test
    void mapsSignalTypesToCategoriesAndCreatesCandidateRows() {
        List<AiProfileSignalEntity> signals = List.of(
                signal(1L, "WEAKNESS", "Binary Search", 0.60, "CHAT_TURN", "t-1"),
                signal(2L, "MISCONCEPTION", "位运算", 0.70, "CHAT_TURN", "t-2"),
                signal(3L, "MASTERY", "prefix sum", 0.90, "JUDGED_SUBMISSION", "456"),
                signal(4L, "PROGRESS", "dp practice", 0.50, "CHAT_TURN", "t-4"),
                signal(5L, "GENERIC_OBSERVATION", "study habit", 0.40, "CHAT_TURN", "t-5"));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(signals, List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        assignProfileIds();

        int processed = service.aggregatePendingSignals(7L);

        assertThat(processed).isEqualTo(5);
        ArgumentCaptor<AiLearningProfileEntity> profileCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper, times(5)).insert(profileCaptor.capture());
        List<AiLearningProfileEntity> inserted = profileCaptor.getAllValues();
        assertThat(inserted).extracting(p -> p.category)
                .containsExactly("weakness", "weakness", "mastery", "progress", "observation");
        assertThat(inserted).extracting(p -> p.state).containsOnly("CANDIDATE");
        assertThat(inserted).extracting(p -> p.profileKey)
                .containsExactly("binary_search", "位运算", "prefix_sum", "dp_practice", "study_habit");
        assertThat(inserted).extracting(p -> p.label)
                .containsExactly("Binary Search", "位运算", "prefix sum", "dp practice", "study habit");
        assertThat(inserted.get(0).confidence).isEqualByComparingTo(new BigDecimal("0.6000"));
        assertThat(inserted.get(2).confidence).isEqualByComparingTo(new BigDecimal("0.9000"));
        // Every signal was closed out with the PENDING-guarded CAS update.
        ArgumentCaptor<UpdateWrapper> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(signalMapper, times(5)).update(isNull(), updateCaptor.capture());
        UpdateWrapper<?> wrapper = updateCaptor.getValue();
        assertThat(wrapper.getSqlSet()).contains("status=");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(ProfileSignalIngestionService.STATUS_AGGREGATED);
        // WHERE side guards on both the row id and the PENDING status (CAS).
        assertThat(wrapper.getCustomSqlSegment()).contains("id =").contains("status =");
    }

    @Test
    void blankKnowledgeNodeIsMarkedAggregatedWithoutProfileWork() {
        List<AiProfileSignalEntity> signals = List.of(
                signal(1L, "WEAKNESS", "   ", 0.6, "CHAT_TURN", "t-1"),
                signal(2L, "MASTERY", null, 0.8, "CHAT_TURN", "t-2"));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(signals, List.of());

        int processed = service.aggregatePendingSignals(7L);

        assertThat(processed).isEqualTo(2);
        verifyNoInteractions(profileMapper);
        verifyNoInteractions(evidenceMapper);
        ArgumentCaptor<UpdateWrapper> updateCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(signalMapper, times(2)).update(isNull(), updateCaptor.capture());
        UpdateWrapper<?> wrapper = updateCaptor.getValue();
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(ProfileSignalIngestionService.STATUS_AGGREGATED);
        assertThat(wrapper.getCustomSqlSegment()).contains("id =").contains("status =");
    }

    @Test
    void newProfileConfidenceIsGroupAverageAndEvidenceRowsCarrySignalText() {
        List<AiProfileSignalEntity> signals = List.of(
                signal(1L, "WEAKNESS", "binary search", 0.40, "CHAT_TURN", "t-1"),
                signal(2L, "WEAKNESS", "binary search", 0.80, "CHAT_TURN", "t-2"));
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(signals, List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);
        assignProfileIds();

        service.aggregatePendingSignals(7L);

        ArgumentCaptor<AiLearningProfileEntity> profileCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).insert(profileCaptor.capture());
        AiLearningProfileEntity inserted = profileCaptor.getValue();
        assertThat(inserted.state).isEqualTo("CANDIDATE");
        assertThat(inserted.confidence).isEqualByComparingTo(new BigDecimal("0.6000"));

        ArgumentCaptor<AiLearningProfileEvidenceEntity> evidenceCaptor =
                ArgumentCaptor.forClass(AiLearningProfileEvidenceEntity.class);
        verify(evidenceMapper, times(2)).insert(evidenceCaptor.capture());
        AiLearningProfileEvidenceEntity first = evidenceCaptor.getAllValues().get(0);
        assertThat(first.evidenceType).isEqualTo(ProfileAggregationService.EVIDENCE_TYPE_PROFILE_SIGNAL);
        assertThat(first.sourceType).isEqualTo("CHAT_TURN");
        assertThat(first.sourceId).isEqualTo("t-1");
        assertThat(first.summary).isEqualTo("signal text 1");
        assertThat(first.confidence).isEqualByComparingTo(new BigDecimal("0.4"));

        ArgumentCaptor<AiLearningProfileEntity> updateCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().evidenceCount).isEqualTo(2);
        assertThat(updateCaptor.getValue().updatedAt).isNotNull();
    }

    @Test
    void existingActiveProfileGetsRollingAverageConfidence() {
        AiLearningProfileEntity active = profile(101L, "ACTIVE", "0.8000", 3);
        LocalDateTime latestEvidenceAt = LocalDateTime.now().minusMinutes(3);
        AiLearningProfileEvidenceEntity latest = new AiLearningProfileEvidenceEntity();
        latest.id = 202L;
        latest.profileId = 101L;
        latest.createdAt = latestEvidenceAt;
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(
                List.of(signal(1L, "WEAKNESS", "binary search", 0.50, "JUDGED_SUBMISSION", "456")), List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(active);
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null, latest);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(4L);

        service.aggregatePendingSignals(7L);

        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        ArgumentCaptor<AiLearningProfileEntity> updateCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(updateCaptor.capture());
        AiLearningProfileEntity updated = updateCaptor.getValue();
        // (0.8 * min(3,10) + 0.5) / (min(3,10) + 1) = 0.725
        assertThat(updated.confidence).isEqualByComparingTo(new BigDecimal("0.7250"));
        assertThat(updated.state).isEqualTo("ACTIVE");
        assertThat(updated.evidenceCount).isEqualTo(4);
        assertThat(updated.lastEvidenceAt).isEqualTo(latestEvidenceAt);
        verify(evidenceMapper).insert(any(AiLearningProfileEvidenceEntity.class));
    }

    @Test
    void terminalProfilesAreNotRevivedAndGetNoEvidence() {
        AiLearningProfileEntity disabled = profile(101L, "DISABLED", "0.8000", 3);
        disabled.disabledAt = LocalDateTime.now().minusDays(1);
        AiLearningProfileEntity resolved = profile(102L, "RESOLVED", "0.9000", 5);
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                signal(1L, "WEAKNESS", "binary search", 0.50, "CHAT_TURN", "t-1"),
                signal(2L, "WEAKNESS", "prefix sum", 0.60, "CHAT_TURN", "t-2")), List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(disabled, resolved);

        int processed = service.aggregatePendingSignals(7L);

        assertThat(processed).isEqualTo(2);
        verify(profileMapper, never()).insert(any(AiLearningProfileEntity.class));
        verify(profileMapper, never()).updateById(any(AiLearningProfileEntity.class));
        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        // Signals are still closed out so the job never rescans them.
        verify(signalMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void duplicateEvidenceSourceIsNotReinserted() {
        AiLearningProfileEvidenceEntity existing = new AiLearningProfileEvidenceEntity();
        existing.id = 202L;
        existing.profileId = 101L;
        existing.createdAt = LocalDateTime.now().minusHours(1);
        when(signalMapper.selectList(any(QueryWrapper.class))).thenReturn(
                List.of(signal(1L, "WEAKNESS", "binary search", 0.60, "JUDGED_SUBMISSION", "456")), List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        assignProfileIds();

        service.aggregatePendingSignals(7L);

        verify(evidenceMapper, never()).insert(any(AiLearningProfileEvidenceEntity.class));
        ArgumentCaptor<AiLearningProfileEntity> updateCaptor = ArgumentCaptor.forClass(AiLearningProfileEntity.class);
        verify(profileMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().evidenceCount).isEqualTo(1);
        assertThat(updateCaptor.getValue().lastEvidenceAt).isEqualTo(existing.createdAt);
    }

    @Test
    void processesAtMostFiveHundredSignalsPerRun() {
        List<AiProfileSignalEntity> batch = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            batch.add(signal((long) i, "WEAKNESS", "binary search", 0.5, "CHAT_TURN", "t-" + i));
        }
        when(signalMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(batch, batch, batch, batch, batch, List.of());
        when(profileMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(evidenceMapper.selectCount(any(QueryWrapper.class))).thenReturn(100L);
        assignProfileIds();

        int processed = service.aggregatePendingSignals(7L);

        assertThat(processed).isEqualTo(500);
        // 5 batches of 100; a sixth fetch never happens.
        verify(signalMapper, times(5)).selectList(any(QueryWrapper.class));
    }

    private void assignProfileIds() {
        doAnswer(invocation -> {
            AiLearningProfileEntity profile = invocation.getArgument(0);
            profile.id = 101L;
            return 1;
        }).when(profileMapper).insert(any(AiLearningProfileEntity.class));
    }

    private AiLearningProfileEntity profile(Long id, String state, String confidence, int evidenceCount) {
        AiLearningProfileEntity profile = new AiLearningProfileEntity();
        profile.id = id;
        profile.userId = 7L;
        profile.category = "weakness";
        profile.profileKey = "binary_search";
        profile.label = "binary search";
        profile.state = state;
        profile.confidence = new BigDecimal(confidence);
        profile.evidenceCount = evidenceCount;
        profile.createdAt = LocalDateTime.now().minusDays(2);
        profile.updatedAt = profile.createdAt;
        return profile;
    }

    private AiProfileSignalEntity signal(Long id, String type, String node, double score, String sourceType, String sourceId) {
        AiProfileSignalEntity signal = new AiProfileSignalEntity();
        signal.setId(id);
        signal.setUserId(7L);
        signal.setSignalType(type);
        signal.setKnowledgeNode(node);
        signal.setPolarity("NEUTRAL");
        signal.setScore(BigDecimal.valueOf(score));
        signal.setSourceType(sourceType);
        signal.setSourceId(sourceId);
        signal.setPayloadJson("{\"signal\":\"signal text " + id + "\"}");
        signal.setStatus(ProfileSignalIngestionService.STATUS_PENDING);
        signal.setCreatedAt(LocalDateTime.now());
        return signal;
    }
}
