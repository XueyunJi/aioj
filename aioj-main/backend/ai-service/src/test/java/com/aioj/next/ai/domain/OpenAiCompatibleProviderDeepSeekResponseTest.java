package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer;
import com.aioj.next.ai.domain.context.AiIntentAnalysis;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleProviderDeepSeekResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenAiCompatibleProvider(
                new AiProperties(),
                objectMapper,
                new ClarificationSchemaRepairer()
        );
    }

    @Test
    void defaultModelUsesDeepSeekV4Pro() {
        AiProperties properties = new AiProperties();

        assertThat(properties.getProvider()).isEqualTo("deepseek");
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(properties.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(properties.getIntent().getModel()).isBlank();
        assertThat(properties.getDeepseek().isThinkingEnabled()).isFalse();
        assertThat(properties.getDeepseek().getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void applicationYamlUsesDeepSeekKeyAndIgnoresGenericAiKey() throws IOException {
        MockEnvironment environment = environmentFromApplicationYaml();

        assertThat(environment.getProperty("aioj.ai.api-key")).isEmpty();
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-pro");

        environment.setProperty("DEEPSEEK_API_KEY", "deepseek-key");
        environment.setProperty("DEEPSEEK_MODEL", "deepseek-v4-flash");
        assertThat(environment.getProperty("aioj.ai.api-key")).isEqualTo("deepseek-key");
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-flash");

        environment.setProperty("AI_API_KEY", "generic-key");
        environment.setProperty("AI_MODEL", "custom-model");
        assertThat(environment.getProperty("aioj.ai.api-key")).isEqualTo("deepseek-key");
        assertThat(environment.getProperty("aioj.ai.model")).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void visibleContentIgnoresKimiReasoningContent() throws Exception {
        Method method = OpenAiCompatibleProvider.class.getDeclaredMethod("visibleAssistantContent", JsonNode.class);
        method.setAccessible(true);
        JsonNode root = objectMapper.readTree("""
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "reasoning_content": "hidden chain of thought",
                        "content": "{\\"teachingDecision\\":\\"DIRECT\\",\\"content\\":\\"visible answer\\",\\"clarification\\":{\\"options\\":[]}}"
                      }
                    }
                  ],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 7}
                }
                """);

        String visible = (String) method.invoke(provider, root);

        assertThat(visible).contains("visible answer");
        assertThat(visible).doesNotContain("hidden chain of thought");
    }

    @Test
    void fallbackIntentDetectsCompositeCodeAndExplanationRequest() throws Exception {
        Method method = OpenAiCompatibleProvider.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        method.setAccessible(true);

        AiIntentAnalysis analysis = (AiIntentAnalysis) method.invoke(provider,
                new AiChatRequest("c1", 1L, "能给我这道题你的解题思路和代码吗？按照你的思路讲解你的代码", "hint", null, null, null));

        assertThat(analysis.primaryIntent()).isEqualTo(AiIntentAnalysis.UserIntent.DIRECT_SOLUTION_WITH_CODE);
        assertThat(analysis.answerStyle()).isEqualTo(AiIntentAnalysis.AnswerStyle.CODE_FIRST_THEN_EXPLAIN);
        assertThat(analysis.wantsCodeFirst()).isTrue();
        assertThat(analysis.blocksHelpfulClarification()).isTrue();
    }

    @Test
    void dynamicPromptCarriesIntentAnalysisAsHiddenInstruction() throws Exception {
        Method intentMethod = OpenAiCompatibleProvider.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        intentMethod.setAccessible(true);
        Method promptMethod = OpenAiCompatibleProvider.class.getDeclaredMethod("chatUserPrompt", AiChatRequest.class, AiChatContext.class, AiIntentAnalysis.class);
        promptMethod.setAccessible(true);
        AiChatRequest request = new AiChatRequest("c1", 1L, "能给我这道题你的解题思路和代码吗？按照你的思路讲解你的代码", "hint", null, null, null);
        AiIntentAnalysis analysis = (AiIntentAnalysis) intentMethod.invoke(provider, request);

        String prompt = (String) promptMethod.invoke(provider, request, AiChatContext.empty(), analysis);

        assertThat(prompt).contains("<INTENT_ANALYSIS>");
        assertThat(prompt).contains("answerStyle: CODE_FIRST_THEN_EXPLAIN");
        assertThat(prompt).contains("先给完整可提交代码");
        assertThat(prompt).contains("<STUDENT_QUESTION>");
    }

    @Test
    void contestPolicyPromptRemainsVisibleAlongsideCodeFirstIntent() throws Exception {
        Field systemPromptField = OpenAiCompatibleProvider.class.getDeclaredField("CHAT_SYSTEM_PROMPT");
        systemPromptField.setAccessible(true);
        String systemPrompt = (String) systemPromptField.get(null);
        Method intentMethod = OpenAiCompatibleProvider.class.getDeclaredMethod("fallbackIntentAnalysis", AiChatRequest.class);
        intentMethod.setAccessible(true);
        Method promptMethod = OpenAiCompatibleProvider.class.getDeclaredMethod("chatUserPrompt", AiChatRequest.class, AiChatContext.class, AiIntentAnalysis.class);
        promptMethod.setAccessible(true);
        AiChatRequest request = new AiChatRequest("c1", 1L, "比赛中也请先给我完整代码和讲解", "hint", null, null, null);
        AiIntentAnalysis analysis = (AiIntentAnalysis) intentMethod.invoke(provider, request);
        AiChatContext context = new AiChatContext(
                "",
                "",
                "",
                "",
                """
                        [Contest AI policy]
                        activeContestProblem: true
                        allowFullCodeInResponse: false
                        allowOwnSubmissionCodeToAi: false
                        policyMessage: 比赛进行中只能提供思路、复杂度、边界情况和调试方向，不能提供完整可提交代码。
                        """
        );

        String prompt = (String) promptMethod.invoke(provider, request, context, analysis);

        assertThat(systemPrompt)
                .contains("比赛进行中只能提供思路")
                .contains("禁止输出完整可提交代码");
        assertThat(prompt)
                .contains("answerStyle: CODE_FIRST_THEN_EXPLAIN")
                .contains("先给完整可提交代码")
                .contains("allowFullCodeInResponse: false")
                .contains("不能提供完整可提交代码");
    }

    @Test
    void deepSeekJsonBodyAddsResponseFormatAndDisablesThinkingByDefault() {
        Map<String, Object> body = provider.completionBody(
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                "deepseek-v4-pro",
                "https://api.deepseek.com/chat/completions",
                true
        );

        assertThat(body).containsEntry("model", "deepseek-v4-pro");
        assertThat(body).containsEntry("max_tokens", 100);
        assertThat(body.get("response_format")).isEqualTo(Map.of("type", "json_object"));
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(body).doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("temperature", 0.2);
    }

    @Test
    void deepSeekThinkingCanBeEnabledWithReasoningEffort() {
        AiProperties properties = new AiProperties();
        properties.getDeepseek().setThinkingEnabled(true);
        properties.getDeepseek().setReasoningEffort("max");
        OpenAiCompatibleProvider thinkingProvider = new OpenAiCompatibleProvider(
                properties,
                objectMapper,
                new ClarificationSchemaRepairer()
        );

        Map<String, Object> body = thinkingProvider.completionBody(
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                "deepseek-v4-pro",
                "https://api.deepseek.com/chat/completions",
                true
        );

        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(body).containsEntry("reasoning_effort", "max");
        assertThat(body).doesNotContainKey("temperature");
    }

    @Test
    void moonshotV1JsonBodyDoesNotSendThinkingOptions() {
        Map<String, Object> body = provider.completionBody(
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                "moonshot-v1-32k",
                "https://api.moonshot.ai/v1/chat/completions",
                true
        );

        assertThat(body.get("response_format")).isEqualTo(Map.of("type", "json_object"));
        assertThat(body).doesNotContainKey("thinking");
        assertThat(body).doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("temperature", 0.2);
    }

    @Test
    void kimiThinkingModelUsesKimiThinkingShapeWithoutEffort() {
        AiProperties properties = new AiProperties();
        properties.getDeepseek().setThinkingEnabled(true);
        OpenAiCompatibleProvider kimiProvider = new OpenAiCompatibleProvider(
                properties,
                objectMapper,
                new ClarificationSchemaRepairer()
        );

        Map<String, Object> body = kimiProvider.completionBody(
                List.of(Map.of("role", "user", "content", "return json")),
                0.2,
                100,
                "kimi-k2.6",
                "https://api.moonshot.ai/v1/chat/completions",
                true
        );

        assertThat(body.get("response_format")).isEqualTo(Map.of("type", "json_object"));
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "enabled"));
        assertThat(body).doesNotContainKey("reasoning_effort");
        assertThat(body).containsEntry("temperature", 1.0);
    }

    @Test
    void embeddingDoesNotUseGlobalDeepSeekApiKey() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("deepseek-key");
        OpenAiCompatibleProvider embeddingProvider = new OpenAiCompatibleProvider(
                properties,
                objectMapper,
                new ClarificationSchemaRepairer(),
                new EnvironmentAiModelConfigResolver(properties, ignored -> ""),
                new AiModelCompletionClient(objectMapper, properties)
        );

        assertThat(embeddingProvider.embed("test text")).isEmpty();
    }

    private MockEnvironment environmentFromApplicationYaml() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
