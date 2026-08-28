package com.aioj.next.ai.agent.memory;

import com.aioj.next.ai.persistence.entity.AiMemoryCandidateEntity;
import com.aioj.next.ai.persistence.entity.AiMemoryClaimEntity;
import com.aioj.next.ai.persistence.mapper.AiMemoryCandidateMapper;
import com.aioj.next.ai.persistence.mapper.AiMemoryClaimMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDistrustPolicyTest {

    private final AiMemoryClaimMapper claimMapper = mock(AiMemoryClaimMapper.class);
    private final AiMemoryCandidateMapper candidateMapper = mock(AiMemoryCandidateMapper.class);
    private final MemoryDistrustPolicy policy = new MemoryDistrustPolicy(claimMapper, candidateMapper);

    @Test
    void disabledClaimOnSameFiveTupleIsDistrusted() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isTrue();

        ArgumentCaptor<QueryWrapper<AiMemoryClaimEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(claimMapper).selectCount(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
                .contains("user_id", "scope_type", "scope_id", "category", "memory_key", "status");
    }

    @Test
    void blankMemoryKeySkipsClaimLookup() {
        when(candidateMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "  ", "用户喜欢先给思路")).isFalse();

        verify(claimMapper, never()).selectCount(any(QueryWrapper.class));
    }

    @Test
    void userRejectedCandidateWithSameKeyIsDistrustedCaseInsensitive() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(candidateMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(rejectedCandidate("Guidance_Preference", "user_rejected", "完全不同的文本")));

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isTrue();
    }

    @Test
    void userRejectedCandidateWithDifferentKeyIsNotDistrusted() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(candidateMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(rejectedCandidate("other_key", "user_rejected", "用户喜欢先给思路")));

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isFalse();
    }

    @Test
    void rejectedCandidateMatchesNormalizedCanonicalTextWhenKeyBlank() {
        when(candidateMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(rejectedCandidate("old_key", "memory_clarification_rejected", "用户  喜欢\n先给   提示")));

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "", "用户 喜欢 先给 提示")).isTrue();
    }

    @Test
    void rejectReasonWhitelistIsEnforcedInQuery() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(candidateMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路");

        // User distrust only counts user_rejected / memory_clarification_rejected; quality-gate
        // hard rejects, identity_permission_isolated and admin_rejected must never feed the policy.
        ArgumentCaptor<QueryWrapper<AiMemoryCandidateEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(candidateMapper).selectList(queryCaptor.capture());
        QueryWrapper<AiMemoryCandidateEntity> query = queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("rejected_reason", "IN", "status");
        assertThat(query.getParamNameValuePairs().values())
                .contains("user_rejected", "memory_clarification_rejected");
        assertThat(query.getParamNameValuePairs().values())
                .doesNotContain("identity_permission_isolated", "admin_rejected");
    }

    @Test
    void identityIsolatedRejectionRowsAreExcludedSoKeyIsNotDistrusted() {
        // The DB-side reason whitelist excludes identity_permission_isolated rows;
        // an empty filtered result means the key is NOT distrusted.
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(candidateMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isFalse();
    }

    @Test
    void claimLookupFailureFailsSafeToDistrusted() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenThrow(new RuntimeException("db down"));

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isTrue();
    }

    @Test
    void candidateLookupFailureFailsSafeToDistrusted() {
        when(claimMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(candidateMapper.selectList(any(QueryWrapper.class))).thenThrow(new RuntimeException("db down"));

        assertThat(policy.isDistrusted(7L, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isTrue();
    }

    @Test
    void nullUserIsNeverDistrusted() {
        assertThat(policy.isDistrusted(null, "GLOBAL", null, "PREFERENCE", "guidance_preference", "用户喜欢先给思路"))
                .isFalse();
    }

    private AiMemoryCandidateEntity rejectedCandidate(String memoryKey, String rejectedReason, String canonicalText) {
        AiMemoryCandidateEntity candidate = new AiMemoryCandidateEntity();
        candidate.id = 42L;
        candidate.userId = 7L;
        candidate.category = "PREFERENCE";
        candidate.memoryKey = memoryKey;
        candidate.canonicalText = canonicalText;
        candidate.status = "REJECTED";
        candidate.rejectedReason = rejectedReason;
        candidate.updatedAt = LocalDateTime.now();
        return candidate;
    }
}
