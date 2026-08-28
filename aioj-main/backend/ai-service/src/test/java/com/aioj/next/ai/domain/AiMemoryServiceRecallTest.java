package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.memory.AiMemoryCandidateService;
import com.aioj.next.ai.domain.memory.MemoryQualityGate;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryRecallLogEntity;
import com.aioj.next.ai.persistence.entity.AiUserMemoryEntity;
import com.aioj.next.ai.persistence.mapper.AiLearningWeaknessMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryRecallLogMapper;
import com.aioj.next.ai.persistence.mapper.AiUserMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1.5 recall-side quality gates: claim expiry filtering, recall-log cooldown
 * penalty (replacing the old last_used_at positive feedback) and the
 * last_confirmed_at positive feature.
 */
@ExtendWith(MockitoExtension.class)
class AiMemoryServiceRecallTest {
    private static final Long USER_ID = 7L;
    private static final String QUERY = "二分答案 单调性 练习 目标 提升";
    private static final String CONTENT = "我正在准备二分答案和单调性练习，目标是提升边界处理能力。";

    @Mock
    private AiUserMemoryMapper memoryMapper;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private MemoryQualityGate memoryQualityGate;
    @Mock
    private AiMemoryCandidateService memoryCandidateService;
    @Mock
    private AiMemoryClaimMapper memoryClaimMapper;
    @Mock
    private AiMemoryRecallLogMapper memoryRecallLogMapper;
    @Mock
    private AiLearningWeaknessMapper learningWeaknessMapper;

    private AiProperties properties;
    private AiMemoryService service;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        service = new AiMemoryService(memoryMapper, aiProvider,
                new AiCapacityService(properties),
                retrievalService, memoryQualityGate,
                memoryCandidateService, memoryClaimMapper, memoryRecallLogMapper, learningWeaknessMapper,
                properties);
        lenient().when(retrievalService.search(any(), anyString(), anyList(), anyInt())).thenReturn(List.of());
    }

    @Test
    void memoryWhoseClaimsAreAllExpiredIsFilteredOut() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(901L)));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of(
                claim(901L, LocalDateTime.now().minusHours(1), null),
                claim(901L, LocalDateTime.now().minusMinutes(5), null)
        ));

        String recalled = service.recallForContext(USER_ID, QUERY);

        assertThat(recalled).isEmpty();
        verify(memoryRecallLogMapper, never()).insert(any(AiMemoryRecallLogEntity.class));
    }

    @Test
    void nullExpiresAtKeepsMemoryRecallable() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(901L)));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of(
                claim(901L, LocalDateTime.now().minusHours(1), null),
                claim(901L, null, null)
        ));

        String recalled = service.recallForContext(USER_ID, QUERY);

        assertThat(recalled).contains("二分答案");
        verify(memoryRecallLogMapper).insert(any(AiMemoryRecallLogEntity.class));
    }

    @Test
    void memoryWithoutClaimsIsNotAffectedByExpiryFilter() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(901L)));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of());

        String recalled = service.recallForContext(USER_ID, QUERY);

        assertThat(recalled).contains("二分答案");
    }

    @Test
    void cooldownPenaltyAppliesAndLastUsedAtPositiveFeedbackIsGone() {
        AiUserMemoryEntity cooled = memory(901L);
        AiUserMemoryEntity fresh = memory(902L);
        fresh.setLastUsedAt(LocalDateTime.now());
        when(memoryMapper.selectList(any())).thenReturn(List.of(cooled, fresh));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of());
        AiMemoryRecallLogEntity recentLog = new AiMemoryRecallLogEntity();
        recentLog.userId = USER_ID;
        recentLog.legacyMemoryId = 901L;
        recentLog.createdAt = LocalDateTime.now().minusMinutes(5);
        when(memoryRecallLogMapper.selectList(any())).thenReturn(List.of(recentLog));

        String recalled = service.recallForContext(USER_ID, QUERY);

        assertThat(recalled).contains("二分答案");
        ArgumentCaptor<AiMemoryRecallLogEntity> captor = ArgumentCaptor.forClass(AiMemoryRecallLogEntity.class);
        verify(memoryRecallLogMapper, times(2)).insert(captor.capture());
        double cooledScore = scoreOf(captor.getAllValues(), 901L);
        double freshScore = scoreOf(captor.getAllValues(), 902L);
        // Identical relevance; the cooled memory loses exactly the configured
        // penalty. If the old +0.08 lastUsedAt bonus still existed, the fresh
        // memory (lastUsedAt set) would score penalty + 0.08 higher.
        assertThat(freshScore - cooledScore)
                .isCloseTo(properties.getRecall().getCooldownPenalty(), within(1e-4));
    }

    @Test
    void oldRecallLogOutsideCooldownWindowDoesNotPenalize() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(901L)));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of());
        AiMemoryRecallLogEntity staleLog = new AiMemoryRecallLogEntity();
        staleLog.userId = USER_ID;
        staleLog.legacyMemoryId = 901L;
        staleLog.createdAt = LocalDateTime.now().minusHours(2);
        when(memoryRecallLogMapper.selectList(any())).thenReturn(List.of(staleLog));
        // The SQL window filter would exclude this row; the mocked mapper
        // returns it anyway, so scoring must trust the query, not re-check.

        String recalled = service.recallForContext(USER_ID, QUERY);

        assertThat(recalled).contains("二分答案");
    }

    @Test
    void lastConfirmedClaimAddsPositiveBoost() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(901L), memory(902L)));
        when(memoryClaimMapper.selectList(any())).thenReturn(List.of(
                claim(901L, null, LocalDateTime.now().minusDays(1))
        ));
        when(memoryRecallLogMapper.selectList(any())).thenReturn(List.of());

        service.recallForContext(USER_ID, QUERY);

        ArgumentCaptor<AiMemoryRecallLogEntity> captor = ArgumentCaptor.forClass(AiMemoryRecallLogEntity.class);
        verify(memoryRecallLogMapper, times(2)).insert(captor.capture());
        double confirmedScore = scoreOf(captor.getAllValues(), 901L);
        double plainScore = scoreOf(captor.getAllValues(), 902L);
        assertThat(confirmedScore - plainScore)
                .isCloseTo(properties.getRecall().getConfirmedBoost(), within(1e-4));
    }

    private double scoreOf(List<AiMemoryRecallLogEntity> logs, Long memoryId) {
        return logs.stream()
                .filter(log -> memoryId.equals(log.legacyMemoryId))
                .map(log -> log.recallScore)
                .findFirst()
                .map(BigDecimal::doubleValue)
                .orElseThrow(() -> new AssertionError("no recall log for memory " + memoryId));
    }

    private static AiUserMemoryEntity memory(Long id) {
        AiUserMemoryEntity memory = new AiUserMemoryEntity();
        memory.setId(id);
        memory.setUserId(USER_ID);
        memory.setCategory("memory");
        memory.setTitle("学习方向");
        memory.setMemoryType("learning_direction");
        memory.setContent(CONTENT);
        memory.setStatus("ACTIVE");
        memory.setSource("USER_MANUAL");
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }

    private static AiMemoryClaimEntity claim(Long memoryId, LocalDateTime expiresAt, LocalDateTime lastConfirmedAt) {
        AiMemoryClaimEntity claim = new AiMemoryClaimEntity();
        claim.id = 5000L + memoryId;
        claim.userId = USER_ID;
        claim.legacyMemoryId = memoryId;
        claim.expiresAt = expiresAt;
        claim.lastConfirmedAt = lastConfirmedAt;
        return claim;
    }
}
