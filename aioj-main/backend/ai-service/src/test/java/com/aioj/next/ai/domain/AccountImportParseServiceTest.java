package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.contract.ai.AccountImportParseRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountImportParseServiceTest {
    @Test
    void localFallbackKeepsChineseNamesAndNormalizesFullWidthEmailSymbols() {
        AiModelConfigResolver configResolver = mock(AiModelConfigResolver.class);
        when(configResolver.effectiveConfig(AiModelScope.ACCOUNT_IMPORT_PARSE)).thenReturn(disabledConfig());
        AccountImportParseService service = new AccountImportParseService(
                new ObjectMapper(),
                configResolver,
                mock(AiModelCompletionClient.class),
                mock(AiQuotaService.class),
                new AiCapacityService(new AiProperties())
        );

        var response = service.parse(1L, new AccountImportParseRequest("""
                学号,姓名,邮箱
                2405024101,艾振鹏,ai＠example。com
                2405024102,曹思毅,bad＠example
                """));

        assertEquals(2, response.users().size());
        assertEquals("艾振鹏", response.users().get(0).displayName());
        assertEquals("ai@example.com", response.users().get(0).email());
        assertEquals("曹思毅", response.users().get(1).displayName());
        assertEquals("bad@example", response.users().get(1).email());
        assertTrue(response.note().contains("本地规则解析"));
    }

    private AiModelEffectiveConfig disabledConfig() {
        return new AiModelEffectiveConfig(
                AiModelScope.ACCOUNT_IMPORT_PARSE,
                false,
                false,
                "test",
                "deepseek",
                "https://example.test",
                "",
                "",
                "ENVIRONMENT",
                "DUMMY",
                "model",
                true,
                false,
                null,
                0.1,
                1000,
                null,
                Instant.now(),
                1L
        );
    }
}
