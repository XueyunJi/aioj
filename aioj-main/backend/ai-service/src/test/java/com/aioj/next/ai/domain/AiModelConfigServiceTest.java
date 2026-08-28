package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiModelConfigEntity;
import com.aioj.next.ai.persistence.mapper.AiModelConfigMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiModelListResponse;
import com.aioj.next.contract.ai.AiModelConfigResponse;
import com.aioj.next.contract.ai.AiModelConfigTestRequest;
import com.aioj.next.contract.ai.AiModelConfigUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelConfigServiceTest {

    @Test
    void environmentFallbackUsesDeepSeekTextButDoesNotUseTextKeyForEmbedding() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-text-key"));
        fixture.properties.getEmbedding().setApiKey("");

        AiModelEffectiveConfig text = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);
        AiModelEffectiveConfig memoryExtraction = fixture.service.effectiveConfig(AiModelScope.MEMORY_EXTRACTION);
        AiModelEffectiveConfig reportAnalysis = fixture.service.effectiveConfig(AiModelScope.REPORT_ANALYSIS);
        AiModelEffectiveConfig draft = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);
        AiModelEffectiveConfig embedding = fixture.service.effectiveConfig(AiModelScope.EMBEDDING);

        assertThat(text.provider()).isEqualTo("deepseek");
        assertThat(text.model()).isEqualTo("deepseek-v4-pro");
        assertThat(text.hasApiKey()).isTrue();
        assertThat(text.apiKeyEnvName()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(memoryExtraction.inherited()).isTrue();
        assertThat(memoryExtraction.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(reportAnalysis.inherited()).isTrue();
        assertThat(reportAnalysis.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(draft.inherited()).isTrue();
        assertThat(draft.apiKey()).isEqualTo("deepseek-text-key");
        assertThat(embedding.hasApiKey()).isFalse();
        assertThat(embedding.apiKeySource()).isEqualTo("NONE");
    }

    @Test
    void aiApiKeyDoesNotShadowDeepSeekProviderKey() {
        Fixture fixture = new Fixture(Map.of(
                "AI_API_KEY", "wrong-global-key",
                "DEEPSEEK_API_KEY", "deepseek-key"
        ));

        AiModelEffectiveConfig text = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(text.apiKey()).isEqualTo("deepseek-key");
        assertThat(text.apiKeyEnvName()).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(text.apiKey()).isNotEqualTo("wrong-global-key");
    }

    @Test
    void kimiUsesKimiKeyEvenWhenAiApiKeyExists() {
        Fixture fixture = new Fixture(Map.of(
                "AI_API_KEY", "wrong-global-key",
                "DEEPSEEK_API_KEY", "deepseek-key",
                "KIMI_API_KEY", "kimi-key"
        ));
        AiModelConfigEntity text = new AiModelConfigEntity();
        text.setId(1L);
        text.setScope("TEXT_GENERATION");
        text.setEnabled(true);
        text.setProvider("moonshot");
        text.setBaseUrl("https://api.moonshot.ai/v1/chat/completions");
        text.setModel("kimi-k2.6");
        text.setJsonOutputEnabled(true);
        text.setThinkingEnabled(true);
        text.setReasoningEffort("high");
        fixture.rows.put("TEXT_GENERATION", text);

        AiModelEffectiveConfig config = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(config.provider()).isEqualTo("kimi");
        assertThat(config.baseUrl()).isEqualTo("https://api.moonshot.cn/v1/chat/completions");
        assertThat(config.apiKey()).isEqualTo("kimi-key");
        assertThat(config.apiKeyEnvName()).isEqualTo("KIMI_API_KEY");
    }

    @Test
    void missingKimiTestConfigDoesNotUseDeepSeekFallbackKey() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));

        var response = fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
                true,
                "moonshot",
                "https://api.moonshot.ai/v1/chat/completions",
                "kimi-k2.6",
                "CLEAR",
                null,
                true,
                true,
                "high",
                null,
                null,
                null,
                "return ok"
        ), 9L);

        assertThat(response.success()).isFalse();
        assertThat(response.provider()).isEqualTo("kimi");
        assertThat(response.errorMessage()).contains("API key is not configured");
        verify(fixture.completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
    }

    @Test
    void disabledProblemDraftTestInheritsDefaultTextGenerationConfig() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        when(fixture.completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenReturn(new AiModelCompletionClient.CompletionResult("{\"ok\":true}", "deepseek", "deepseek-v4-pro", 12, 6));

        var response = fixture.service.testConfig("PROBLEM_DRAFT", new AiModelConfigTestRequest(
                false,
                "kimi",
                "https://api.moonshot.cn/v1/chat/completions",
                "kimi-k2.6",
                "CLEAR",
                null,
                true,
                true,
                "max",
                null,
                null,
                null,
                "return ok"
        ), 9L);

        assertThat(response.success()).isTrue();
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-v4-pro");
        verify(fixture.completionClient).complete(
                argThat(config -> config.scope() == AiModelScope.PROBLEM_DRAFT
                        && "deepseek".equals(config.provider())
                        && "deepseek-v4-pro".equals(config.model())
                        && "deepseek-key".equals(config.apiKey())
                        && "DEEPSEEK_API_KEY".equals(config.apiKeyEnvName())
                        && config.inherited()),
                anyList(),
                anyDouble(),
                anyInt(),
                anyBoolean()
        );
        verify(fixture.quotaService).record(9L, "deepseek", "deepseek-v4-pro", 12, 6, true);
    }

    @Test
    void runtimeSceneOverrideFallsBackToDefaultDeepSeekWhenProviderKeyIsMissing() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setId(2L);
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("moonshot");
        draft.setBaseUrl("https://api.moonshot.ai/v1/chat/completions");
        draft.setModel("kimi-k2.6");
        draft.setJsonOutputEnabled(true);
        draft.setThinkingEnabled(true);
        draft.setReasoningEffort("high");
        fixture.rows.put("PROBLEM_DRAFT", draft);

        AiModelEffectiveConfig runtime = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);

        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.model()).isEqualTo("deepseek-v4-pro");
        assertThat(runtime.apiKey()).isEqualTo("deepseek-key");
        assertThat(runtime.inherited()).isTrue();
        assertThat(runtime.source()).isEqualTo("TEXT_GENERATION");
    }

    @Test
    void adminListShowsSavedProviderAndMissingProviderSpecificKey() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setId(2L);
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("moonshot");
        draft.setBaseUrl("https://api.moonshot.ai/v1/chat/completions");
        draft.setModel("kimi-k2.6");
        draft.setJsonOutputEnabled(true);
        draft.setThinkingEnabled(true);
        draft.setReasoningEffort("high");
        fixture.rows.put("PROBLEM_DRAFT", draft);

        AiModelConfigResponse display = fixture.service.listConfigs().stream()
                .filter(item -> "PROBLEM_DRAFT".equals(item.scope()))
                .findFirst()
                .orElseThrow();

        assertThat(display.provider()).isEqualTo("kimi");
        assertThat(display.apiKeyConfigured()).isFalse();
        assertThat(display.apiKeyEnvName()).isEqualTo("KIMI_API_KEY");
    }

    @Test
    void unknownTextProviderFallsBackToDefaultDeepSeekAtRuntime() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelConfigEntity draft = new AiModelConfigEntity();
        draft.setId(2L);
        draft.setScope("PROBLEM_DRAFT");
        draft.setEnabled(true);
        draft.setProvider("custom");
        draft.setBaseUrl("https://example.test/v1/chat/completions");
        draft.setModel("custom-model");
        draft.setJsonOutputEnabled(true);
        draft.setThinkingEnabled(false);
        draft.setReasoningEffort("high");
        fixture.rows.put("PROBLEM_DRAFT", draft);

        AiModelEffectiveConfig runtime = fixture.service.effectiveConfig(AiModelScope.PROBLEM_DRAFT);

        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.apiKey()).isEqualTo("deepseek-key");
        assertThat(runtime.inherited()).isTrue();
    }

    @Test
    void saveRejectsUnsupportedTextProvider() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));

        assertThatThrownBy(() -> fixture.service.updateConfig("TEXT_GENERATION", new AiModelConfigUpdateRequest(
                true,
                "custom",
                "https://example.test/v1/chat/completions",
                "custom-model",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessageContaining("DeepSeek or Kimi");
    }

    @Test
    void testRejectsMismatchedTextApiAndModel() {
        Fixture fixture = new Fixture(Map.of(
                "DEEPSEEK_API_KEY", "deepseek-key",
                "KIMI_API_KEY", "kimi-key"
        ));

        assertThatThrownBy(() -> fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
                true,
                "kimi",
                "https://api.moonshot.ai/v1/chat/completions",
                "deepseek-v4-pro",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                "return ok"
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessageContaining("do not match");

        verify(fixture.completionClient, never()).complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean());
    }

    @Test
    void apiKeyReplaceIsRejectedBecauseKeysComeFromEnvironment() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "environment-key"));

        assertThatThrownBy(() -> fixture.service.updateConfig("PROBLEM_DRAFT", new AiModelConfigUpdateRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "REPLACE",
                "draft-secret-key",
                true,
                true,
                "max",
                0.2,
                2048,
                null
        ), 9L)).isInstanceOf(DomainException.class)
                .hasMessageContaining("environment variables");

        assertThat(fixture.rows).doesNotContainKey("PROBLEM_DRAFT");
    }

    @Test
    void databaseKeyIsIgnoredAndClearedWhenSavingConfig() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "environment-key"));
        AiModelConfigEntity legacy = new AiModelConfigEntity();
        legacy.setId(1L);
        legacy.setScope("TEXT_GENERATION");
        legacy.setEnabled(true);
        legacy.setProvider("deepseek");
        legacy.setBaseUrl("https://api.deepseek.com/chat/completions");
        legacy.setModel("deepseek-v4-pro");
        legacy.setJsonOutputEnabled(true);
        legacy.setThinkingEnabled(false);
        legacy.setReasoningEffort("high");
        legacy.setApiKeyEncrypted("legacy-encrypted-key");
        legacy.setApiKeyPreview("legacy");
        fixture.rows.put("TEXT_GENERATION", legacy);

        AiModelEffectiveConfig beforeSave = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(beforeSave.apiKey()).isEqualTo("environment-key");
        assertThat(beforeSave.apiKeySource()).isEqualTo("ENVIRONMENT");

        fixture.service.updateConfig("TEXT_GENERATION", new AiModelConfigUpdateRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null
        ), 9L);

        AiModelEffectiveConfig effective = fixture.service.effectiveConfig(AiModelScope.TEXT_GENERATION);

        assertThat(fixture.rows.get("TEXT_GENERATION").getApiKeyEncrypted()).isNull();
        assertThat(fixture.rows.get("TEXT_GENERATION").getApiKeyPreview()).isNull();
        assertThat(effective.apiKey()).isEqualTo("environment-key");
        assertThat(effective.apiKeySource()).isEqualTo("ENVIRONMENT");
    }

    @Test
    void testConfigSanitizesProviderErrorsAndRecordsFailure() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "temp-secret-key"));
        when(fixture.completionClient.complete(any(AiModelEffectiveConfig.class), anyList(), anyDouble(), anyInt(), anyBoolean()))
                .thenThrow(new DomainException(ErrorCode.INTERNAL_ERROR, "Provider returned HTTP 401: {\"error\":{\"message\":\"Authentication Fails, Your api key: ****lqO5 is invalid\"}}"));

        var response = fixture.service.testConfig("TEXT_GENERATION", new AiModelConfigTestRequest(
                true,
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                "deepseek-v4-pro",
                "CLEAR",
                null,
                true,
                false,
                "high",
                null,
                null,
                null,
                "return ok"
        ), 9L);

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains("***");
        assertThat(response.errorMessage()).doesNotContain("temp-secret-key");
        assertThat(response.errorMessage()).doesNotContain("lqO5");
        verify(fixture.quotaService).record(9L, "deepseek", "deepseek-v4-pro", 0, 0, false);
    }

    @Test
    void listModelsUsesEnvironmentKeyForSelectedProvider() {
        Fixture fixture = new Fixture(Map.of("DEEPSEEK_API_KEY", "deepseek-key"));
        AiModelListResponse expected = new AiModelListResponse(
                "TEXT_GENERATION",
                "deepseek",
                "https://api.deepseek.com/chat/completions",
                true,
                "DEEPSEEK_API_KEY",
                true,
                "SUCCESS",
                null,
                java.util.List.of()
        );
        when(fixture.modelCatalogClient.listModels(
                eq(AiModelScope.TEXT_GENERATION),
                eq("deepseek"),
                eq("https://api.deepseek.com/chat/completions"),
                argThat(key -> key != null && "deepseek-key".equals(key.apiKey()) && "DEEPSEEK_API_KEY".equals(key.apiKeyEnvName()))
        )).thenReturn(expected);

        var response = fixture.service.listModels("TEXT_GENERATION", "deepseek", "https://api.deepseek.com/chat/completions");

        assertThat(response).isSameAs(expected);
    }

    @Test
    void listModelsNormalizesKimiBaseUrlToCnEndpoint() {
        Fixture fixture = new Fixture(Map.of("KIMI_API_KEY", "kimi-key"));
        AiModelListResponse expected = new AiModelListResponse(
                "TEXT_GENERATION",
                "kimi",
                "https://api.moonshot.cn/v1/chat/completions",
                true,
                "KIMI_API_KEY",
                true,
                "SUCCESS",
                null,
                java.util.List.of()
        );
        when(fixture.modelCatalogClient.listModels(
                eq(AiModelScope.TEXT_GENERATION),
                eq("kimi"),
                eq("https://api.moonshot.cn/v1/chat/completions"),
                argThat(key -> key != null && "kimi-key".equals(key.apiKey()) && "KIMI_API_KEY".equals(key.apiKeyEnvName()))
        )).thenReturn(expected);

        var response = fixture.service.listModels("TEXT_GENERATION", "kimi", "https://api.moonshot.ai/v1/chat/completions");

        assertThat(response).isSameAs(expected);
    }

    private static final class Fixture {
        final AiProperties properties = new AiProperties();
        final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        final AiModelCompletionClient completionClient = mock(AiModelCompletionClient.class);
        final AiModelCatalogClient modelCatalogClient = mock(AiModelCatalogClient.class);
        final AiQuotaService quotaService = mock(AiQuotaService.class);
        final Map<String, AiModelConfigEntity> rows = new HashMap<>();
        final Map<String, String> env = new HashMap<>();
        final AiModelConfigService service;

        Fixture() {
            this(Map.of());
        }

        Fixture(Map<String, String> initialEnv) {
            env.putAll(initialEnv);
            when(mapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> rows.get(scopeFrom(invocation.getArgument(0))));
            when(mapper.insert(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
                AiModelConfigEntity entity = invocation.getArgument(0);
                rows.put(entity.getScope(), entity);
                return 1;
            });
            when(mapper.updateById(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
                AiModelConfigEntity entity = invocation.getArgument(0);
                rows.put(entity.getScope(), entity);
                return 1;
            });
            service = new AiModelConfigService(properties, mapper, completionClient, modelCatalogClient, quotaService, name -> env.getOrDefault(name, ""));
        }

        private String scopeFrom(QueryWrapper<AiModelConfigEntity> wrapper) {
            AtomicReference<String> scope = new AtomicReference<>();
            wrapper.getSqlSegment();
            wrapper.getParamNameValuePairs().values().stream()
                    .findFirst()
                    .ifPresent(value -> scope.set(String.valueOf(value)));
            return scope.get();
        }
    }
}
