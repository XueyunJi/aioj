package com.aioj.next.ai.domain.migration;

import com.aioj.next.ai.persistence.entity.AiLearningProfileEntity;
import com.aioj.next.ai.persistence.entity.AiLearningWeaknessEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiProfileSignalEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningProfileMapper;
import com.aioj.next.ai.persistence.mapper.AiLearningWeaknessMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiProfileSignalMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyMemoryMigrationServiceTest {

    private final AiUserMemoryMapper userMemoryMapper = mock(AiUserMemoryMapper.class);
    private final AiMemoryClaimMapper claimMapper = mock(AiMemoryClaimMapper.class);
    private final AiLearningProfileMapper profileMapper = mock(AiLearningProfileMapper.class);
    private final AiLearningWeaknessMapper weaknessMapper = mock(AiLearningWeaknessMapper.class);
    private final AiProfileSignalMapper signalMapper = mock(AiProfileSignalMapper.class);
    private final LegacyMemoryMigrationService service = new LegacyMemoryMigrationService(
            userMemoryMapper, claimMapper, profileMapper, weaknessMapper, signalMapper, new ObjectMapper());

    @BeforeEach
    void stubEmptyReconciliation() {
        when(profileMapper.selectList(any())).thenReturn(List.of());
        when(weaknessMapper.selectList(any())).thenReturn(List.of());
        when(signalMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void dryRunPlansInsertWithoutWriting() {
        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示再给代码"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        LegacyMemoryMigrationReport report = service.migrate(false);

        assertThat(report.getScanned()).isEqualTo(1);
        assertThat(report.getMigrated()).isEqualTo(1);
        assertThat(report.getEntries()).extracting(LegacyMemoryMigrationReport.Entry::action)
                .containsExactly(LegacyMemoryMigrationReport.Action.WOULD_MIGRATE);
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
    }

    @Test
    void applyInsertsClaimWithFrozenMapping() {
        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示再给代码"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getMigrated()).isEqualTo(1);
        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).insert(captor.capture());
        AiMemoryClaimEntity claim = captor.getValue();
        assertThat(claim.userId).isEqualTo(7L);
        assertThat(claim.legacyMemoryId).isEqualTo(11L);
        assertThat(claim.scopeType).isEqualTo("GLOBAL");
        assertThat(claim.scopeId).isNull();
        assertThat(claim.category).isEqualTo("RULE");
        assertThat(claim.memoryKey).isEqualTo("legacy_11");
        assertThat(claim.canonicalText).isEqualTo("先给提示再给代码");
        assertThat(claim.confidence).isEqualByComparingTo("0.8000");
        assertThat(claim.stabilityScore).isEqualByComparingTo("0.8000");
        assertThat(claim.supportCount).isEqualTo(1);
        assertThat(claim.sourceMode).isEqualTo(LegacyMemoryMigrationService.SOURCE_MODE);
        assertThat(claim.status).isEqualTo("ACTIVE");
        assertThat(claim.pinned).isFalse();
        assertThat(claim.version).isEqualTo(1);
        assertThat(claim.valueJson).contains("\"legacyMemoryType\":\"rule\"");
    }

    @Test
    void preferenceAndWeaknessTypesMapToCategories() {
        stubLegacyRows(
                memory(1L, 7L, "preferred_language", "ACTIVE", "c++"),
                memory(2L, 7L, "guidance_preference", "ACTIVE", "先给提示"),
                memory(3L, 7L, "teaching_style", "ACTIVE", "逐步讲解"),
                memory(4L, 7L, "weakness", "ACTIVE", "二分边界"),
                memory(5L, 7L, "learning_weakness", "ACTIVE", "二分 l/r 混淆"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        service.migrate(true);

        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper, org.mockito.Mockito.times(5)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(claim -> claim.category)
                .containsExactly("PREFERENCE", "PREFERENCE", "PREFERENCE", "WEAKNESS", "WEAKNESS");
    }

    @Test
    void productionLegacyTypesMapWithoutDroppingSupersededState() {
        stubLegacyRows(
                memory(21L, 8L, "answer_style_preference", "SUPERSEDED", "先给思路"),
                memory(22L, 9L, "manual_note", "ACTIVE", "学生确认的备注"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        service.migrate(true);

        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(claim -> claim.category)
                .containsExactly("PREFERENCE", "MANUAL_NOTE");
        assertThat(captor.getAllValues()).extracting(claim -> claim.status)
                .containsExactly("SUPERSEDED", "ACTIVE");
    }

    @Test
    void mappedManualNoteIsRecognizedOnReentry() {
        stubLegacyRows(memory(22L, 9L, "manual_note", "ACTIVE", "学生确认的备注"));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim(9022L, "ACTIVE")));

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getAlreadyMapped()).isEqualTo(1);
        assertThat(report.getSkippedUnknownType()).isZero();
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
    }

    @Test
    void contentTypeIsSkippedWithoutClaimLookup() {
        stubLegacyRows(memory(11L, 7L, "content", "ACTIVE", "排序是基础步骤"));

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getSkippedContentType()).isEqualTo(1);
        assertThat(report.getMigrated()).isZero();
        verify(claimMapper, never()).selectList(any());
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
    }

    @Test
    void unknownTypeIsSkipped() {
        stubLegacyRows(memory(11L, 7L, "goal_v2", "ACTIVE", "目标"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getSkippedUnknownType()).isEqualTo(1);
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
    }

    @Test
    void alreadyMappedRowIsLeftUntouched() {
        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示"));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim(9001L, "ACTIVE")));

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getAlreadyMapped()).isEqualTo(1);
        assertThat(report.getStatusMismatches()).isZero();
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
        verify(claimMapper, never()).updateById(any(AiMemoryClaimEntity.class));
    }

    @Test
    void supersededLegacyWithActiveLinkedClaimIsReportedOnly() {
        stubLegacyRows(memory(11L, 7L, "rule", "SUPERSEDED", "旧规则"));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim(9001L, "ACTIVE")));

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getAlreadyMapped()).isEqualTo(1);
        assertThat(report.getStatusMismatches()).isEqualTo(1);
        LegacyMemoryMigrationReport.Entry entry = report.getEntries().get(0);
        assertThat(entry.action()).isEqualTo(LegacyMemoryMigrationReport.Action.STATUS_MISMATCH);
        assertThat(entry.detail()).contains("9001");
        verify(claimMapper, never()).insert(any(AiMemoryClaimEntity.class));
        verify(claimMapper, never()).updateById(any(AiMemoryClaimEntity.class));
    }

    @Test
    void supersededLegacyUnmappedMigratesAsSuperseded() {
        stubLegacyRows(memory(11L, 7L, "rule", "SUPERSEDED", "旧规则"));
        when(claimMapper.selectList(any())).thenReturn(List.of());

        service.migrate(true);

        ArgumentCaptor<AiMemoryClaimEntity> captor = ArgumentCaptor.forClass(AiMemoryClaimEntity.class);
        verify(claimMapper).insert(captor.capture());
        assertThat(captor.getValue().status).isEqualTo("SUPERSEDED");
    }

    @Test
    void secondRunIsIdempotent() {
        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示"));
        when(claimMapper.selectList(any())).thenReturn(List.of());
        service.migrate(true);

        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示"));
        when(claimMapper.selectList(any())).thenReturn(List.of(claim(9001L, "ACTIVE")));
        LegacyMemoryMigrationReport second = service.migrate(true);

        assertThat(second.getMigrated()).isZero();
        assertThat(second.getAlreadyMapped()).isEqualTo(1);
    }

    @Test
    void keyConflictIsReportedAndDoesNotAbort() {
        stubLegacyRows(
                memory(11L, 7L, "rule", "ACTIVE", "规则一"),
                memory(12L, 7L, "rule", "ACTIVE", "规则二"));
        when(claimMapper.selectList(any())).thenReturn(List.of());
        when(claimMapper.insert(any(AiMemoryClaimEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_memory_claim_key"))
                .thenReturn(1);

        LegacyMemoryMigrationReport report = service.migrate(true);

        assertThat(report.getKeyConflicts()).isEqualTo(1);
        assertThat(report.getMigrated()).isEqualTo(1);
        assertThat(report.getScanned()).isEqualTo(2);
    }

    @Test
    void reconciliationCountsAreCollected() {
        stubLegacyRows(memory(11L, 7L, "rule", "ACTIVE", "先给提示"));
        when(claimMapper.selectList(any())).thenReturn(List.of());
        AiLearningProfileEntity active = new AiLearningProfileEntity();
        active.state = "ACTIVE";
        AiLearningProfileEntity disabled = new AiLearningProfileEntity();
        disabled.state = "DISABLED";
        when(profileMapper.selectList(any())).thenReturn(List.of(active, disabled));
        AiLearningWeaknessEntity weakness = new AiLearningWeaknessEntity();
        weakness.status = "ACTIVE";
        when(weaknessMapper.selectList(any())).thenReturn(List.of(weakness));
        AiProfileSignalEntity signal = new AiProfileSignalEntity();
        signal.setStatus("PENDING");
        when(signalMapper.selectList(any())).thenReturn(List.of(signal));

        LegacyMemoryMigrationReport report = service.migrate(false);

        assertThat(report.getProfileByState()).containsEntry("ACTIVE", 1L).containsEntry("DISABLED", 1L);
        assertThat(report.getWeaknessByStatus()).containsEntry("ACTIVE", 1L);
        assertThat(report.getSignalsByStatus()).containsEntry("PENDING", 1L);
    }

    private void stubLegacyRows(AiUserMemoryEntity... rows) {
        when(userMemoryMapper.selectList(any())).thenReturn(List.of(rows), List.of());
    }

    private AiUserMemoryEntity memory(long id, long userId, String type, String status, String content) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setMemoryType(type);
        memory.setStatus(status);
        memory.setContent(content);
        memory.setConfidence(new BigDecimal("0.800"));
        memory.setCreatedAt(LocalDateTime.of(2026, 6, 1, 12, 0));
        memory.setUpdatedAt(LocalDateTime.of(2026, 6, 2, 12, 0));
        return memory;
    }

    private AiMemoryClaimEntity claim(long id, String status) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = id;
        claim.status = status;
        return claim;
    }
}
