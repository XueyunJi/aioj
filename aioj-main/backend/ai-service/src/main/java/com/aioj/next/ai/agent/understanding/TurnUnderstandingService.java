package com.aioj.next.ai.agent.understanding;

import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.model.ModelUsage;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiModelScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-turn understanding via one small structured model call (design doc §6.2,
 * frozen decision D1=B2). The judgement is a floor, not a ceiling: it only
 * decides whether the Agent Runtime must execute at least one tool call
 * (REQUIRED) before answering; the main loop may still call tools on its own
 * (AUTO). Category labels never enter the prompt context.
 *
 * <p>Fail-open by contract: any model/parse failure yields an empty
 * understanding and the chat turn proceeds unaffected.</p>
 */
@Service
public class TurnUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(TurnUnderstandingService.class);

    /** Tool requirement categories the judge may return (P2: context and memory retrieval tools are live). */
    private static final Set<String> KNOWN_REQUIRES_TOOLS = Set.of(
            "CONTEXT_SEARCH", "MEMORY_SEARCH", "PROBLEM_FETCH", "SUBMISSION_FETCH");

    private static final Set<String> KNOWN_DIALOGUE_ACTS = Set.of(
            "NEW_REQUEST", "FOLLOW_UP", "CONTINUE", "SWITCH_TARGET", "COMPARE",
            "CORRECT_REFERENCE", "MODIFY_PREVIOUS_RESULT", "ASK_REASON", "ASK_EXAMPLE",
            "SUMMARIZE", "RECALL_HISTORY");

    private static final Set<String> KNOWN_REFERENCE_TYPES = Set.of(
            "ORDINAL", "RELATIVE", "DEMONSTRATIVE", "TEMPORAL", "SEMANTIC", "VERSION");

    private static final int MAX_MESSAGE_CHARS = 4000;

    private static final String SYSTEM_PROMPT = """
            你是 AI-OJ 教学平台 Agent 的轮次理解器。给你用户本轮消息，判断回答这一轮是否需要先调用检索工具获取额外上下文。

            只输出一个 JSON 对象，不要输出任何其他文字。字段如下：
            {
              "dialogueAct": "NEW_REQUEST|FOLLOW_UP|CONTINUE|SWITCH_TARGET|COMPARE|CORRECT_REFERENCE|MODIFY_PREVIOUS_RESULT|ASK_REASON|ASK_EXAMPLE|SUMMARIZE|RECALL_HISTORY 之一",
              "referenceTypes": ["ORDINAL|RELATIVE|DEMONSTRATIVE|TEMPORAL|SEMANTIC|VERSION 中的若干项，没有则为空数组"],
              "longRangeCue": true 或 false,
              "requiresTools": ["CONTEXT_SEARCH|MEMORY_SEARCH|PROBLEM_FETCH|SUBMISSION_FETCH 中的若干项，没有则为空数组"]
            }

            判定规则：
            - 消息自包含、仅凭当前消息与最近几轮对话即可回答 → requiresTools 为空数组；
            - 出现“第 N 题/上一题/下一题/刚才/之前/上次/最开始/那道某类题”等指代或远距线索，且答案依赖历史对话内容 → 包含 CONTEXT_SEARCH；
            - 答案依赖用户长期偏好、水平或画像 → 包含 MEMORY_SEARCH；
            - 答案依赖某道题的完整题面而消息中未给出 → 包含 PROBLEM_FETCH；
            - 答案依赖用户某次提交的代码或评测详情 → 包含 SUBMISSION_FETCH；
            - longRangeCue：用户引用了距离当前很远的会话内容（如“最开始那批”“上次聊的”）时为 true；
            - 拿不准时 requiresTools 保持空数组，宁可不强制也不要过度调用；
            - 输出必须是合法 JSON。
            """;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public TurnUnderstandingService(ModelGateway modelGateway, ObjectMapper objectMapper, AiProperties properties) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Assess one user message before the agent run. First-turn conversations
     * skip the model call entirely: there is no history to retrieve.
     */
    public TurnUnderstanding assess(String userMessage, boolean hasPriorTurns) {
        if (!hasPriorTurns || userMessage == null || userMessage.isBlank()) {
            return TurnUnderstanding.empty();
        }
        try {
            GatewayResponse response = modelGateway.call(
                    modelGateway.configFor(AiModelScope.from(properties.getAgentCore().getCuratorScope())),
                    new GatewayRequest(
                            List.of(
                                    GatewayMessage.system(SYSTEM_PROMPT),
                                    GatewayMessage.user(cap(userMessage))
                            ),
                            List.of(),
                            ToolChoiceMode.AUTO,
                            CallProfile.STRUCTURED_SMALL
                    )
            );
            return parse(response.content(), ModelUsage.from(response));
        } catch (RuntimeException ex) {
            // Fail-open: understanding is advisory; the chat turn must never break because of it.
            log.warn("turn understanding failed, proceeding without tool floor: {}", ex.toString());
            return TurnUnderstanding.empty();
        }
    }

    private TurnUnderstanding parse(String content, ModelUsage usage) {
        if (content == null || content.isBlank()) {
            return TurnUnderstanding.empty(usage);
        }
        String cleaned = content.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                return TurnUnderstanding.empty(usage);
            }
            String dialogueAct = textIfKnown(node, "dialogueAct", KNOWN_DIALOGUE_ACTS);
            List<String> referenceTypes = knownValues(node.path("referenceTypes"), KNOWN_REFERENCE_TYPES);
            boolean longRangeCue = node.path("longRangeCue").asBoolean(false);
            Set<String> requiresTools = new LinkedHashSet<>(knownValues(node.path("requiresTools"), KNOWN_REQUIRES_TOOLS));
            return new TurnUnderstanding(dialogueAct, referenceTypes, longRangeCue, requiresTools, usage);
        } catch (Exception ex) {
            log.warn("turn understanding output unparsable, proceeding without tool floor: {}", ex.toString());
            return TurnUnderstanding.empty(usage);
        }
    }

    private String textIfKnown(JsonNode node, String field, Set<String> known) {
        String value = node.path(field).asText("");
        return known.contains(value) ? value : null;
    }

    private List<String> knownValues(JsonNode array, Set<String> known) {
        if (!array.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (item.isTextual() && known.contains(item.asText())) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private String cap(String message) {
        return message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) : message;
    }

    /**
     * Structured pre-turn judgement. {@code requiresTools} is the floor for the
     * agent loop's REQUIRED tool choice; the other labels are observability
     * data only and are never injected into the prompt.
     */
    public record TurnUnderstanding(
            String dialogueAct,
            List<String> referenceTypes,
            boolean longRangeCue,
            Set<String> requiresTools,
            /** Provider-reported usage is telemetry only; it never influences the tool floor. */
            ModelUsage usage
    ) {
        public TurnUnderstanding {
            referenceTypes = referenceTypes == null ? List.of() : List.copyOf(referenceTypes);
            requiresTools = requiresTools == null ? Set.of() : Set.copyOf(requiresTools);
        }

        /** Compatibility constructor for callers/tests that do not need usage telemetry. */
        public TurnUnderstanding(String dialogueAct, List<String> referenceTypes, boolean longRangeCue,
                                 Set<String> requiresTools) {
            this(dialogueAct, referenceTypes, longRangeCue, requiresTools, null);
        }

        public static TurnUnderstanding empty() {
            return empty(null);
        }

        private static TurnUnderstanding empty(ModelUsage usage) {
            return new TurnUnderstanding(null, List.of(), false, Set.of(), usage);
        }
    }
}
