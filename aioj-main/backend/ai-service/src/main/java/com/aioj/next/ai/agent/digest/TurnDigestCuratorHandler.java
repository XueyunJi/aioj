package com.aioj.next.ai.agent.digest;

import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobHandler;
import com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService;
import com.aioj.next.ai.agent.memory.MemoryCandidateIngestionService;
import com.aioj.next.ai.agent.model.CallProfile;
import com.aioj.next.ai.agent.model.GatewayMessage;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.model.ToolChoiceMode;
import com.aioj.next.ai.agent.profile.ProfileAggregateJobProducer;
import com.aioj.next.ai.agent.profile.ProfileSignalIngestionService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Async TurnDigest Curator (design doc §6.3, blueprint §七 "一次 Curator 调用完成多个任务").
 * One structured call upgrades the rule-based stub (digest_version=1/STUB) into the
 * semantic digest (digest_version=2/READY). Since P2-2 the curator's memory/profile
 * outputs are kept in the digest and ingested BEFORE the READY row persists: memory
 * candidates flow through MemoryCandidateIngestionService (legacy quality gate +
 * identity/permission write isolation), profile signals land in ai_profile_signals;
 * episodeBoundaryProposal stays unpersisted until P4. The stub's deterministic fields
 * (source, codeRefs, explicit ids, selection) are authoritative and always survive the merge.
 */
@Component
public class TurnDigestCuratorHandler implements AgentAsyncJobHandler {

    public static final String CURATOR_PROMPT_VERSION = "agent-curator-v3.1";

    private static final Logger log = LoggerFactory.getLogger(TurnDigestCuratorHandler.class);

    private static final int MAX_MESSAGE_CHARS = 8000;
    private static final int MAX_SEARCH_TEXT_CHARS = 1600;
    private static final int MAX_CURATOR_OUTPUT_ITEMS = 8;

    private static final String SYSTEM_PROMPT = """
            你是 AI-OJ 教学平台的会话结构化整理器（Curator）。给你一轮对话的用户消息、助手回答和规则生成的存根摘要，
            你要输出该轮的结构化摘要 JSON（schemaVersion=3），供后续检索与指代消解使用。

            只输出一个 JSON 对象，不要输出任何其他文字。字段如下：
            {
              "dialogueAct": "NEW_REQUEST|FOLLOW_UP|CONTINUE|SWITCH_TARGET|COMPARE|CORRECT_REFERENCE|MODIFY_PREVIOUS_RESULT|ASK_REASON|ASK_EXAMPLE|SUMMARIZE|RECALL_HISTORY 之一",
              "userIntents": ["用户意图标签，如 EXPLAIN_PROBLEM/DEBUG_CODE/COMPLEXITY_ANALYSIS/CONCEPT_QUESTION/OTHER"],
              "topicPath": ["从粗到细的主题路径，如 algorithm, binary_search"],
              "summary": "120 字以内的中文摘要：用户要什么、助手给了什么结论",
              "searchKeywords": ["检索关键词：中文术语、算法名、代码符号、数值，不超过 20 个"],
              "entities": [{"type": "PROBLEM|CODE_SNAPSHOT|ALGORITHM_TOPIC|CONCEPT|OTHER", "canonicalName": "规范名", "aliases": ["别称"]}],
              "references": [{"expression": "用户消息中的指代表达", "resolution": "它指向什么", "confidence": 0.0}],
              "userAssertions": [{"text": "用户自己陈述的事实或困难", "sourceMessageId": "user"}],
              "assistantClaims": [{"text": "助手给出的结论性说法", "verification": "UNVERIFIED_ASSISTANT_CLAIM", "sourceMessageId": "assistant"}],
              "decisions": ["本轮达成的明确结论"],
              "unresolvedQuestions": ["本轮未解决的问题"],
              "openTasks": ["用户尚未完成、后续可能继续的任务"],
              "problemRefs": [题目ID数字，没有则为空数组],
              "safetyTags": ["NORMAL_PRACTICE|CONTEST_RELATED|SENSITIVE 之一或多个"],
              "memoryCandidates": [{"text": "值得长期记住的用户偏好/目标/规则（没有则为空数组）", "category": "PREFERENCE|RULE|HABIT|GOAL|PROFILE|WEAKNESS|MANUAL_NOTE 之一", "memoryKey": "snake_key", "confidence": 0.0, "longTerm": true, "evidenceType": "EXPLICIT_PREFERENCE|REPEATED_BEHAVIOR|INFERRED 之一"}],
              "profileSignals": [{"signal": "反映用户学习能力/弱点的观察（没有则为空数组）", "signalType": "MASTERY|WEAKNESS|MISCONCEPTION|PROGRESS|GENERIC_OBSERVATION 之一", "knowledgeNode": "知识点名，可空", "polarity": "POSITIVE|NEGATIVE|NEUTRAL 之一", "score": 0.0}]
            }

            铁律：
            - 严格区分“用户说的”（userAssertions）和“助手说的”（assistantClaims），助手结论不得写成用户事实；
            - 不得编造消息中不存在的内容；没有信息的字段给空数组或空字符串；
            - 摘要与关键词必须包含足以让“第 N 题/那道 X 题/上次那个错误”类远距引用被命中的线索；
            - memoryCandidates 只提炼长期有价值的用户偏好/目标/规则：题目细节、临时内容、一次性信息一律不提，宁缺毋滥；
            - 输出必须是合法 JSON。
            """;

    private final AiTurnDigestMapper digestMapper;
    private final AiConversationService conversationService;
    private final ModelGateway modelGateway;
    private final AgentAsyncJobService jobService;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;
    private final MemoryCandidateIngestionService memoryCandidateIngestion;
    private final ProfileSignalIngestionService profileSignalIngestion;
    private final ProfileAggregateJobProducer profileAggregateProducer;

    public TurnDigestCuratorHandler(
            AiTurnDigestMapper digestMapper,
            AiConversationService conversationService,
            ModelGateway modelGateway,
            AgentAsyncJobService jobService,
            ObjectMapper objectMapper,
            AiProperties properties,
            MemoryCandidateIngestionService memoryCandidateIngestion,
            ProfileSignalIngestionService profileSignalIngestion,
            ProfileAggregateJobProducer profileAggregateProducer
    ) {
        this.digestMapper = digestMapper;
        this.conversationService = conversationService;
        this.modelGateway = modelGateway;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.memoryCandidateIngestion = memoryCandidateIngestion;
        this.profileSignalIngestion = profileSignalIngestion;
        this.profileAggregateProducer = profileAggregateProducer;
    }

    @Override
    public String jobType() {
        return TurnDigestService.JOB_TYPE_TURN_CURATE;
    }

    @Override
    public void handle(AiAsyncJobEntity job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayloadJson());
        String turnId = payload.path("turnId").asText();
        Long userId = payload.path("userId").isNumber() ? payload.path("userId").asLong() : null;
        if (turnId.isBlank() || userId == null) {
            throw new IllegalStateException("curate job payload missing turnId/userId");
        }

        AiTurnDigestEntity stub = latestForTurn(turnId);
        if (stub == null) {
            throw new IllegalStateException("no digest row for turn " + turnId);
        }
        if (StubDigestFactory.STATUS_READY.equals(stub.getStatus())
                && CURATOR_PROMPT_VERSION.equals(stub.getCuratorPromptVersion())) {
            return; // already curated with the current prompt: idempotent no-op
        }

        ObjectNode stubDigest = (ObjectNode) objectMapper.readTree(stub.getStructuredDigest());
        JsonNode source = stubDigest.path("source");
        AiChatMessageResponse userMessage = fetchMessage(userId, source.path("userMessageId").asText());
        AiChatMessageResponse assistantMessage = fetchMessage(userId, source.path("assistantMessageId").asText());

        GatewayResponse response = modelGateway.call(
                modelGateway.configFor(AiModelScope.from(properties.getAgentCore().getCuratorScope())),
                new GatewayRequest(
                        List.of(
                                GatewayMessage.system(SYSTEM_PROMPT),
                                GatewayMessage.user(buildUserPayload(stubDigest, userMessage, assistantMessage))
                        ),
                        List.of(),
                        ToolChoiceMode.AUTO,
                        CallProfile.CURATOR
                )
        );

        ObjectNode curated = parseCuratorOutput(response.content());
        ObjectNode merged = merge(stubDigest, curated);
        // P2-2: sinks run BEFORE persistReady. If a sink fails the whole curate job
        // rethrows and retries; the READY no-op short circuit above would otherwise
        // skip the retry and silently lose candidates. Both sinks are idempotent.
        ingestCuratorOutputs(userId, stub, merged, userMessage, assistantMessage);
        persistReady(stub, merged, response.model());
    }

    /** Parses curator memory/profile outputs from the merged digest and runs the P2-2 sinks. */
    private void ingestCuratorOutputs(Long userId, AiTurnDigestEntity stub, ObjectNode merged,
                                      AiChatMessageResponse userMessage, AiChatMessageResponse assistantMessage) {
        List<MemoryCandidateIngestionService.CandidateProposal> candidates =
                parseCandidateProposals(merged.path("memoryCandidates"));
        if (!candidates.isEmpty()) {
            MemoryCandidateIngestionService.IngestResult result = memoryCandidateIngestion.ingest(
                    userId, stub.getConversationId(), userMessage.id(), candidates,
                    userMessage.content(), assistantMessage.content());
            log.info("curator memory candidates ingested turn={} active={} needsConfirmation={} rejected={} preRejected={}",
                    stub.getTurnId(), result.active(), result.needsConfirmation(), result.rejected(), result.preRejected());
        }
        List<ProfileSignalIngestionService.SignalProposal> signals =
                parseSignalProposals(merged.path("profileSignals"));
        if (!signals.isEmpty()) {
            int inserted = profileSignalIngestion.recordChatTurnSignals(
                    userId, stub.getTurnId(), signals, ProfileSignalIngestionService.SOURCE_TYPE_CHAT_TURN);
            log.info("curator profile signals ingested turn={} proposed={} inserted={}",
                    stub.getTurnId(), signals.size(), inserted);
            // P2-6: schedule folding the PENDING signals into ai_learning_profile. Enqueued
            // whenever this batch proposed signals (even on an idempotent 0-insert retry,
            // which may follow a crash between write and enqueue); the hourly-bucketed
            // idempotency key keeps this cheap, and the producer itself never throws.
            profileAggregateProducer.enqueueForUser(userId);
        }
    }

    private List<MemoryCandidateIngestionService.CandidateProposal> parseCandidateProposals(JsonNode array) {
        List<MemoryCandidateIngestionService.CandidateProposal> proposals = new ArrayList<>();
        if (!array.isArray()) {
            return proposals;
        }
        for (JsonNode item : array) {
            if (proposals.size() >= MAX_CURATOR_OUTPUT_ITEMS) {
                break;
            }
            if (!item.isObject()) {
                log.debug("skip non-object memoryCandidate entry: {}", item);
                continue;
            }
            String text = item.path("text").asText("");
            if (text.isBlank()) {
                log.debug("skip memoryCandidate without text: {}", item);
                continue;
            }
            JsonNode confidence = item.path("confidence");
            JsonNode longTerm = item.path("longTerm");
            proposals.add(new MemoryCandidateIngestionService.CandidateProposal(
                    text,
                    textOrNull(item, "category"),
                    textOrNull(item, "memoryKey"),
                    confidence.isNumber() ? confidence.asDouble() : 0.5,
                    longTerm.isBoolean() ? longTerm.asBoolean() : null,
                    textOrNull(item, "evidenceType")));
        }
        return proposals;
    }

    private List<ProfileSignalIngestionService.SignalProposal> parseSignalProposals(JsonNode array) {
        List<ProfileSignalIngestionService.SignalProposal> proposals = new ArrayList<>();
        if (!array.isArray()) {
            return proposals;
        }
        for (JsonNode item : array) {
            if (proposals.size() >= MAX_CURATOR_OUTPUT_ITEMS) {
                break;
            }
            if (!item.isObject()) {
                log.debug("skip non-object profileSignal entry: {}", item);
                continue;
            }
            String signal = item.path("signal").asText("");
            if (signal.isBlank()) {
                log.debug("skip profileSignal without signal: {}", item);
                continue;
            }
            JsonNode score = item.path("score");
            proposals.add(new ProfileSignalIngestionService.SignalProposal(
                    signal,
                    textOrNull(item, "signalType"),
                    textOrNull(item, "knowledgeNode"),
                    textOrNull(item, "polarity"),
                    score.isNumber() ? score.asDouble() : 0.5));
        }
        return proposals;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private AiTurnDigestEntity latestForTurn(String turnId) {
        List<AiTurnDigestEntity> rows = digestMapper.selectList(new QueryWrapper<AiTurnDigestEntity>()
                .eq("turn_id", turnId)
                .orderByDesc("digest_version")
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AiChatMessageResponse fetchMessage(Long userId, String messageId) {
        long id;
        try {
            id = Long.parseLong(messageId);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("digest source message id invalid: " + messageId);
        }
        AiChatMessageResponse message = conversationService.getMessage(userId, id);
        if (message == null) {
            throw new IllegalStateException("digest source message missing: " + messageId);
        }
        return message;
    }

    private String buildUserPayload(ObjectNode stubDigest, AiChatMessageResponse user, AiChatMessageResponse assistant) {
        StringBuilder builder = new StringBuilder();
        builder.append("【存根摘要 JSON】\n").append(stubDigest.toString()).append("\n\n");
        builder.append("【用户消息】\n").append(cap(user.content(), MAX_MESSAGE_CHARS)).append("\n\n");
        builder.append("【助手回答】\n").append(cap(assistant.content(), MAX_MESSAGE_CHARS));
        return builder.toString();
    }

    private ObjectNode parseCuratorOutput(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("curator returned empty content");
        }
        String cleaned = content.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                throw new IllegalStateException("curator output is not a JSON object");
            }
            ObjectNode output = (ObjectNode) node;
            String summary = output.path("summary").asText("");
            if (summary.isBlank()) {
                throw new IllegalStateException("curator output missing summary");
            }
            return output;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("curator output is not valid JSON: " + ex.getMessage());
        }
    }

    /** Curator fills semantics; deterministic stub fields stay authoritative. */
    private ObjectNode merge(ObjectNode stub, ObjectNode curated) {
        ObjectNode merged = stub.deepCopy();
        putTextIfPresent(merged, curated, "dialogueAct");
        copyArray(merged, curated, "userIntents", 8);
        copyArray(merged, curated, "topicPath", 6);
        merged.put("summary", curated.path("summary").asText());
        merged.set("searchKeywords", unionTextArrays(stub.path("searchKeywords"), curated.path("searchKeywords"), 32));
        copyArray(merged, curated, "entities", 20);
        copyArray(merged, curated, "references", 12);
        copyArray(merged, curated, "userAssertions", 12);
        copyArray(merged, curated, "assistantClaims", 12);
        copyArray(merged, curated, "decisions", 8);
        copyArray(merged, curated, "unresolvedQuestions", 8);
        copyArray(merged, curated, "openTasks", 8);
        copyArray(merged, curated, "safetyTags", 8);
        merged.set("problemRefs", unionTextArrays(stub.path("problemRefs"), curated.path("problemRefs"), 20));
        // P2-2: memory/profile outputs stay in the digest and are ingested before READY persists.
        copyArray(merged, curated, "memoryCandidates", MAX_CURATOR_OUTPUT_ITEMS);
        copyArray(merged, curated, "profileSignals", MAX_CURATOR_OUTPUT_ITEMS);
        merged.remove("episodeBoundaryProposal");
        return merged;
    }

    private void putTextIfPresent(ObjectNode target, ObjectNode source, String field) {
        JsonNode value = source.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }

    private void copyArray(ObjectNode target, ObjectNode source, String field, int cap) {
        JsonNode value = source.path(field);
        if (!value.isArray()) {
            return;
        }
        ArrayNode copy = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode item : value) {
            if (count++ >= cap) {
                break;
            }
            if (item.isTextual() && item.asText().isBlank()) {
                continue;
            }
            copy.add(item);
        }
        target.set(field, copy);
    }

    private ArrayNode unionTextArrays(JsonNode first, JsonNode second, int cap) {
        Set<String> union = new LinkedHashSet<>();
        collectTexts(second, union, cap); // curator first: richer semantics lead
        collectTexts(first, union, cap);
        ArrayNode result = objectMapper.createArrayNode();
        union.forEach(result::add);
        return result;
    }

    private void collectTexts(JsonNode array, Set<String> out, int cap) {
        if (!array.isArray()) {
            return;
        }
        Iterator<JsonNode> iterator = array.elements();
        while (iterator.hasNext() && out.size() < cap) {
            JsonNode item = iterator.next();
            String text = item.isTextual() ? item.asText() : item.toString();
            if (!text.isBlank() && text.length() <= 60) {
                out.add(text);
            }
        }
    }

    private void persistReady(AiTurnDigestEntity stub, ObjectNode merged, String curatorModel) throws Exception {
        AiTurnDigestEntity ready = new AiTurnDigestEntity();
        ready.setTurnId(stub.getTurnId());
        ready.setConversationId(stub.getConversationId());
        ready.setUserId(stub.getUserId());
        ready.setSummary(merged.path("summary").asText());
        ready.setStructuredDigest(objectMapper.writeValueAsString(merged));
        ready.setSearchText(rebuildSearchText(stub.getSearchText(), merged));
        ready.setSourceHash(stub.getSourceHash());
        ready.setDigestVersion((stub.getDigestVersion() == null ? 1 : stub.getDigestVersion()) + 1);
        ready.setCuratorModel(curatorModel);
        ready.setCuratorPromptVersion(CURATOR_PROMPT_VERSION);
        ready.setStatus(StubDigestFactory.STATUS_READY);
        ready.setTokenEstimate(stub.getTokenEstimate());
        LocalDateTime now = LocalDateTime.now();
        ready.setCreatedAt(now);
        ready.setUpdatedAt(now);
        digestMapper.insert(ready);
        enqueueEmbedJob(ready);
    }

    /** Dense lane handoff (P1-6): embed the READY digest into ai_retrieval_chunks. */
    private void enqueueEmbedJob(AiTurnDigestEntity ready) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("turnId", ready.getTurnId());
            payload.put("userId", ready.getUserId());
            payload.put("digestId", ready.getId());
            jobService.enqueue(
                    TurnDigestService.JOB_TYPE_EMBED_DIGEST,
                    TurnDigestService.JOB_TYPE_EMBED_DIGEST + ":" + ready.getId(),
                    objectMapper.writeValueAsString(payload),
                    properties.getAgentJobs().getMaxAttempts()
            );
        } catch (Exception ex) {
            // Enqueue failure never fails the curate job; the KEYWORD lane keeps working.
            log.warn("embed job enqueue failed digest={} error={}", ready.getId(), ex.toString());
        }
    }

    private String rebuildSearchText(String stubSearchText, ObjectNode merged) {
        StringBuilder builder = new StringBuilder(MAX_SEARCH_TEXT_CHARS);
        append(builder, merged.path("summary").asText(""));
        for (JsonNode keyword : merged.path("searchKeywords")) {
            append(builder, keyword.asText(""));
        }
        for (JsonNode entity : merged.path("entities")) {
            append(builder, entity.path("canonicalName").asText(""));
        }
        for (JsonNode topic : merged.path("topicPath")) {
            append(builder, topic.asText(""));
        }
        append(builder, stubSearchText);
        String text = builder.toString().trim();
        return text.length() > MAX_SEARCH_TEXT_CHARS ? text.substring(0, MAX_SEARCH_TEXT_CHARS) : text;
    }

    private void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank() || builder.length() >= MAX_SEARCH_TEXT_CHARS) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private String cap(String content, int maxChars) {
        if (content == null) {
            return "";
        }
        return content.length() > maxChars ? content.substring(0, maxChars) : content;
    }
}
