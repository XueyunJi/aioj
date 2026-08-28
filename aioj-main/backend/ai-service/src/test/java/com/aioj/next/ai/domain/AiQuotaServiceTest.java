package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.AiQuotaPolicyMapper;
import com.aioj.next.ai.persistence.mapper.AiUsageRecordMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiQuotaServiceTest {

    @Test
    void assertAvailableBlocksStudentRollingWindow() {
        AiQuotaPolicyMapper policyMapper = mock(AiQuotaPolicyMapper.class);
        AiUsageRecordMapper usageMapper = mock(AiUsageRecordMapper.class);
        AiQuotaService service = new AiQuotaService(properties(), policyMapper, usageMapper);
        when(policyMapper.selectOne(any())).thenReturn(null);
        when(usageMapper.selectCount(any())).thenReturn(50L);

        DomainException error = assertThrows(DomainException.class, () -> service.assertAvailable(1L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, error.errorCode());
        assertEquals("AI rolling quota exceeded", error.getMessage());
    }

    @Test
    void assertAvailableBlocksStudentMonthlyQuota() {
        AiQuotaPolicyMapper policyMapper = mock(AiQuotaPolicyMapper.class);
        AiUsageRecordMapper usageMapper = mock(AiUsageRecordMapper.class);
        AiQuotaService service = new AiQuotaService(properties(), policyMapper, usageMapper);
        when(policyMapper.selectOne(any())).thenReturn(null);
        when(usageMapper.selectCount(any())).thenReturn(10L, 1000L);

        DomainException error = assertThrows(DomainException.class, () -> service.assertAvailable(1L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, error.errorCode());
        assertEquals("AI monthly quota exceeded", error.getMessage());
    }

    @Test
    void usageReportsRecentWindowAndMonth() {
        AiQuotaPolicyMapper policyMapper = mock(AiQuotaPolicyMapper.class);
        AiUsageRecordMapper usageMapper = mock(AiUsageRecordMapper.class);
        AiQuotaService service = new AiQuotaService(properties(), policyMapper, usageMapper);
        when(policyMapper.selectOne(any())).thenReturn(null);
        when(usageMapper.selectCount(any())).thenReturn(7L, 42L);

        var usage = service.usage(1L);

        assertEquals(7L, usage.usedRecent());
        assertEquals(50L, usage.rollingLimit());
        assertEquals(2, usage.recentWindowHours());
        assertEquals(42L, usage.usedThisMonth());
        assertEquals(1000L, usage.monthlyLimit());
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setRollingLimit(50);
        properties.setRollingWindowHours(2);
        properties.setMonthlyLimit(1000);
        return properties;
    }
}
