package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.AiTurnService;
import com.aioj.next.ai.persistence.entity.AiClarificationRequestEntity;
import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.ai.persistence.entity.AiConversationTaskStateEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiClarificationRequestMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationTaskStateMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * W1.7 orchestration around the pure {@link ReferenceResolver}:
 * <ul>
 *   <li>loads conversation problem rows + focus set ids and runs the resolver per turn;</li>
 *   <li>persists the resolution manifest into {@code ai_turns.context_manifest_json}
 *       (queryable per turn; never only logged);</li>
 *   <li>reuses the existing clarification infrastructure (ai_clarification_requests +
 *       completion-clarification SSE event) for ambiguous references instead of silently
 *       binding; answers come back through the standard clarificationAnswer path;</li>
 *   <li>renders the {@code [Resolved Reference]} prompt block for hit turns.</li>
 * </ul>
 * Legacy rows with all-NULL ordinals are invisible to the resolver, so behavior for
 * pre-W59 conversations is unchanged.
 */
@Service
public class AiReferenceResolutionService {
    private static final Logger log = LoggerFactory.getLogger(AiReferenceResolutionService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int REQUEST_KEY_MAX = 96;
    private static final int STATEMENT_EXCERPT_MAX = 2000;
    private static final int PENDING_FALLBACK_WINDOW_MINUTES = 15;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final AiConversationProblemMapper problemMapper;
    private final AiConversationTaskStateMapper taskStateMapper;
    private final AiTurnMapper turnMapper;
    private final AiClarificationRequestMapper clarificationRequestMapper;
    private final ConversationStateMerger stateMerger;
    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;

    public AiReferenceResolutionService(
            AiConversationProblemMapper problemMapper,
            AiConversationTaskStateMapper taskStateMapper,
            AiTurnMapper turnMapper,
            AiClarificationRequestMapper clarificationRequestMapper,
            ConversationStateMerger stateMerger,
            ReferenceResolver referenceResolver,
            ObjectMapper objectMapper
    ) {
        this.problemMapper = problemMapper;
        this.taskStateMapper = taskStateMapper;
        this.turnMapper = turnMapper;
        this.clarificationRequestMapper = clarificationRequestMapper;
        this.stateMerger = stateMerger;
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Per-turn side effects, invoked once from AiContextService.beforeTurn after the task
     * state merge: persist the resolution manifest on the current turn row and, when the
     * reference is ambiguous, issue a PENDING clarification request (superseding older
     * resolver-issued ones). Best-effort: failures are logged and never break the turn.
     */
    public void processTurn(Long userId, String conversationId, AiChatRequest request) {
        try {
            ReferenceResolver.Outcome outcome = computeOutcome(userId, conversationId, request);
            if (outcome.isEmpty()) {
                return;
            }
            // Only resolutions or ambiguity questions are persisted; a pattern that matched
            // nothing resolvable (e.g. all legacy NULL-ordinal rows) leaves the turn manifest
            // untouched, keeping pre-W59 conversations byte-identical to the old behavior.
            if (hasPersistableResult(outcome)) {
                persistManifest(conversationId, request, outcome);
            }
            ReferenceResolver.Resolution ambiguous = outcome.firstAmbiguous();
            if (ambiguous != null) {
                persistAmbiguityClarification(userId, conversationId, request, ambiguous);
            }
        } catch (RuntimeException ex) {
            log.error("reference resolution processing failed user={} conversation={} error={}",
                    userId, conversationId, ex.toString());
        }
    }

    /**
     * Renders the [Resolved Reference] block injected next to [Current User Message].
     * Read-only and deterministic; empty when nothing resolved (legacy rows included).
     */
    public String injectionBlock(Long userId, String conversationId, AiChatRequest request) {
        ReferenceResolver.Outcome outcome = computeOutcome(userId, conversationId, request);
        if (outcome.isEmpty()) {
            return "";
        }
        FocusSetIds focus = focusSetIds(userId, conversationId);
        StringBuilder block = new StringBuilder();
        for (ReferenceResolver.Resolution resolution : outcome.resolutions()) {
            if (resolution.resolved()) {
                appendResolvedBlock(block, resolution, focus);
            } else if (resolution.clarificationIssued()) {
                appendAmbiguityBlock(block, resolution, focus);
            }
        }
        return block.toString();
    }

    /**
     * When this turn issued an ambiguity clarification and the provider completion carries
     * no clarification of its own, attach the resolver's question so the existing SSE
     * clarification event fires and the frontend can answer via the standard
     * clarificationAnswer path.
     */
    public Optional<AiCompletion.Clarification> pendingClarificationAttachment(Long userId, String conversationId, AiChatRequest request) {
        if (request == null || request.clarificationAnswer() != null) {
            return Optional.empty();
        }
        try {
            AiClarificationRequestEntity row = findPendingResolverClarification(userId, conversationId, request);
            if (row == null) {
                return Optional.empty();
            }
            List<ReferenceResolver.StoredCandidate> candidates = parseCandidates(row.inputSchema, List.of());
            List<AiCompletion.ClarificationOption> options = new ArrayList<>();
            for (ReferenceResolver.StoredCandidate candidate : candidates) {
                options.add(new AiCompletion.ClarificationOption(
                        "choice",
                        candidate.optionLabel(),
                        "指 " + candidate.optionLabel(),
                        null,
                        null
                ));
            }
            return Optional.of(new AiCompletion.Clarification(
                    row.requestKey,
                    "helpful",
                    "确认你指的是哪道题",
                    row.question,
                    AiCompletion.ClarificationInput.fromOptions(options),
                    options,
                    "ask_user",
                    "在你确认之前，我不会把这条引用绑定到具体题目。"
            ));
        } catch (RuntimeException ex) {
            log.error("reference clarification attachment failed user={} conversation={} error={}",
                    userId, conversationId, ex.toString());
            return Optional.empty();
        }
    }

    private boolean hasPersistableResult(ReferenceResolver.Outcome outcome) {
        for (ReferenceResolver.Resolution resolution : outcome.resolutions()) {
            if (resolution.resolved() || resolution.clarificationIssued()) {
                return true;
            }
        }
        return false;
    }

    private ReferenceResolver.Outcome computeOutcome(Long userId, String conversationId, AiChatRequest request) {        if (request == null || (request.message() == null || request.message().isBlank()) && request.clarificationAnswer() == null) {
            return ReferenceResolver.Outcome.empty();
        }
        List<AiConversationProblemEntity> problems = problemMapper.selectList(new QueryWrapper<AiConversationProblemEntity>()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId));
        FocusSetIds focus = focusSetIds(userId, conversationId);
        List<ReferenceResolver.StoredCandidate> storedCandidates = List.of();
        AiChatRequest.ClarificationAnswer answer = request.clarificationAnswer();
        if (answer != null && answer.requestId() != null
                && answer.requestId().trim().startsWith(ReferenceResolver.CLARIFICATION_KEY_PREFIX)) {
            AiClarificationRequestEntity row = clarificationRequestMapper.selectOne(new QueryWrapper<AiClarificationRequestEntity>()
                    .eq("user_id", userId)
                    .eq("conversation_id", conversationId)
                    .eq("request_key", answer.requestId().trim())
                    .last("LIMIT 1"));
            if (row != null) {
                storedCandidates = parseCandidates(row.inputSchema, problems);
            }
        }
        AiChatRequest.SelectedProblemContext selectionProblem = request.selectionContext() == null
                ? null
                : request.selectionContext().problemContext();
        return referenceResolver.resolve(new ReferenceResolver.Input(
                request.message(),
                answer,
                selectionProblem,
                problems,
                focus.activeSetId(),
                focus.lastSetId(),
                storedCandidates
        ));
    }

    private FocusSetIds focusSetIds(Long userId, String conversationId) {
        AiConversationTaskStateEntity state = taskStateMapper.selectOne(new QueryWrapper<AiConversationTaskStateEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .last("LIMIT 1"));
        if (state == null || state.stateJson == null || state.stateJson.isBlank()) {
            return new FocusSetIds(null, null);
        }
        Map<String, Object> stateMap = stateMerger.readState(state.stateJson);
        return new FocusSetIds(
                stringValue(stateMap.get("activeProblemSetId")),
                stringValue(stateMap.get("lastProblemSetId"))
        );
    }

    private void persistManifest(String conversationId, AiChatRequest request, ReferenceResolver.Outcome outcome) {
        AiTurnEntity turn = locateTurn(conversationId, request.clientMessageId());
        if (turn == null) {
            log.warn("reference resolution manifest not persisted: no turn row for conversation={} clientMessageId={}",
                    conversationId, request.clientMessageId());
            return;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ReferenceResolver.Resolution resolution : outcome.resolutions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "rawText", resolution.rawText());
            putIfPresent(entry, "resolver", resolution.resolver());
            putIfPresent(entry, "setId", resolution.setId());
            entry.put("setOrdinal", resolution.setOrdinal());
            entry.put("conversationOrdinal", resolution.conversationOrdinal());
            entry.put("resolvedProblemId", resolution.resolvedProblemId());
            entry.put("confidence", resolution.confidence());
            List<Map<String, Object>> alternatives = new ArrayList<>();
            for (ReferenceResolver.Candidate candidate : resolution.alternatives()) {
                Map<String, Object> alternative = new LinkedHashMap<>();
                alternative.put("problemId", candidate.problemId());
                putIfPresent(alternative, "title", candidate.title());
                putIfPresent(alternative, "setId", candidate.setId());
                alternative.put("setOrdinal", candidate.setOrdinal());
                alternative.put("conversationOrdinal", candidate.conversationOrdinal());
                alternatives.add(alternative);
            }
            entry.put("alternatives", alternatives);
            entry.put("clarificationIssued", resolution.clarificationIssued());
            entries.add(entry);
        }
        String manifestJson;
        try {
            manifestJson = objectMapper.writeValueAsString(Map.of("referenceResolutions", entries));
        } catch (Exception ex) {
            log.error("reference resolution manifest serialization failed conversation={} error={}", conversationId, ex.toString());
            return;
        }
        turnMapper.update(null, new UpdateWrapper<AiTurnEntity>()
                .eq("id", turn.getId())
                .set("context_manifest_json", manifestJson));
    }

    /**
     * Turn correlation: the exact (conversation_id, client_turn_id) unique key when the
     * client sent an id; otherwise the newest non-terminal turn of the conversation
     * (legacy clients always create a fresh srv-* turn right before context build).
     */
    private AiTurnEntity locateTurn(String conversationId, String clientMessageId) {
        if (clientMessageId != null && !clientMessageId.isBlank()) {
            // Same normalization as AiTurnService.beginTurn: trim, then cap at 80 chars.
            String clientTurnId = clientMessageId.trim();
            clientTurnId = clientTurnId.length() <= 80 ? clientTurnId : clientTurnId.substring(0, 80);
            return turnMapper.selectOne(new QueryWrapper<AiTurnEntity>()
                    .eq("conversation_id", conversationId)
                    .eq("client_turn_id", clientTurnId)
                    .last("LIMIT 1"));
        }
        return turnMapper.selectOne(new QueryWrapper<AiTurnEntity>()
                .eq("conversation_id", conversationId)
                .notIn("status", AiTurnService.STATUS_COMPLETED, AiTurnService.STATUS_FAILED_RETRYABLE,
                        AiTurnService.STATUS_FAILED_FINAL, AiTurnService.STATUS_CANCELLED, AiTurnService.STATUS_REFUSED)
                .orderByDesc("turn_seq")
                .last("LIMIT 1"));
    }

    private void persistAmbiguityClarification(Long userId, String conversationId, AiChatRequest request, ReferenceResolver.Resolution ambiguous) {
        FocusSetIds focus = focusSetIds(userId, conversationId);
        String requestKey = requestKey(conversationId, request, ambiguous);
        // Only one live resolver question per conversation: supersede older pending ones.
        clarificationRequestMapper.update(null, new UpdateWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", STATUS_PENDING)
                .likeRight("request_key", ReferenceResolver.CLARIFICATION_KEY_PREFIX)
                .ne("request_key", requestKey)
                .set("status", STATUS_SUPERSEDED));
        AiClarificationRequestEntity existing = clarificationRequestMapper.selectOne(new QueryWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("request_key", requestKey)
                .eq("status", STATUS_PENDING)
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        List<Map<String, Object>> options = new ArrayList<>();
        List<String> optionLabels = new ArrayList<>();
        int index = 1;
        for (ReferenceResolver.Candidate candidate : ambiguous.alternatives()) {
            String label = optionLabel(candidate, focus);
            optionLabels.add(index + ") " + label);
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("type", "choice");
            option.put("label", label);
            option.put("message", "指 " + label);
            Map<String, Object> candidatePayload = new LinkedHashMap<>();
            candidatePayload.put("rowId", candidate.rowId());
            candidatePayload.put("problemId", candidate.problemId());
            putIfPresent(candidatePayload, "title", candidate.title());
            putIfPresent(candidatePayload, "setId", candidate.setId());
            candidatePayload.put("setOrdinal", candidate.setOrdinal());
            candidatePayload.put("conversationOrdinal", candidate.conversationOrdinal());
            option.put("candidate", candidatePayload);
            options.add(option);
            index++;
        }
        String question = "你提到的「" + ambiguous.rawText() + "」在这次会话里对应多道题："
                + String.join("；", optionLabels) + "。你指的是哪一道？";
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("kind", "single_choice");
        inputSchema.put("options", options);

        AiClarificationRequestEntity entity = new AiClarificationRequestEntity();
        entity.userId = userId;
        entity.conversationId = conversationId;
        entity.requestKey = requestKey;
        entity.priority = "helpful";
        entity.question = question;
        entity.inputSchema = writeJson(inputSchema);
        entity.defaultAction = "ask_user";
        entity.assumption = "在用户确认之前，不把这条引用绑定到具体题目。";
        entity.status = STATUS_PENDING;
        entity.createdAt = LocalDateTime.now();
        clarificationRequestMapper.insert(entity);
    }

    private AiClarificationRequestEntity findPendingResolverClarification(Long userId, String conversationId, AiChatRequest request) {
        String clientMessageId = request.clientMessageId();
        if (clientMessageId != null && !clientMessageId.isBlank()) {
            AiClarificationRequestEntity row = clarificationRequestMapper.selectOne(new QueryWrapper<AiClarificationRequestEntity>()
                    .eq("user_id", userId)
                    .eq("conversation_id", conversationId)
                    .eq("request_key", requestKey(conversationId, request, null))
                    .eq("status", STATUS_PENDING)
                    .last("LIMIT 1"));
            if (row != null) {
                return row;
            }
        }
        return clarificationRequestMapper.selectOne(new QueryWrapper<AiClarificationRequestEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", STATUS_PENDING)
                .likeRight("request_key", ReferenceResolver.CLARIFICATION_KEY_PREFIX)
                .gt("created_at", LocalDateTime.now().minusMinutes(PENDING_FALLBACK_WINDOW_MINUTES))
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    private List<ReferenceResolver.StoredCandidate> parseCandidates(String inputSchema, List<AiConversationProblemEntity> problems) {
        Map<String, Object> schema = readMap(inputSchema);
        Object rawOptions = schema.get("options");
        if (!(rawOptions instanceof List<?> optionList)) {
            return List.of();
        }
        List<ReferenceResolver.StoredCandidate> candidates = new ArrayList<>();
        for (Object rawOption : optionList) {
            if (!(rawOption instanceof Map<?, ?> option)) {
                continue;
            }
            Object rawCandidate = option.get("candidate");
            if (!(rawCandidate instanceof Map<?, ?> candidate)) {
                continue;
            }
            Long rowId = longValue(candidate.get("rowId"));
            candidates.add(new ReferenceResolver.StoredCandidate(
                    stringValue(option.get("label")),
                    rowId,
                    longValue(candidate.get("problemId")),
                    stringValue(candidate.get("title")),
                    stringValue(candidate.get("setId")),
                    intValue(candidate.get("setOrdinal")),
                    intValue(candidate.get("conversationOrdinal")),
                    findRow(problems, rowId)
            ));
        }
        return candidates;
    }

    private AiConversationProblemEntity findRow(List<AiConversationProblemEntity> problems, Long rowId) {
        if (rowId == null || problems == null) {
            return null;
        }
        for (AiConversationProblemEntity row : problems) {
            if (row != null && rowId.equals(row.getId())) {
                return row;
            }
        }
        return null;
    }

    private void appendResolvedBlock(StringBuilder block, ReferenceResolver.Resolution resolution, FocusSetIds focus) {
        AiConversationProblemEntity row = resolution.entity();
        block.append("[Resolved Reference]\n")
                .append("- 引用 \"").append(resolution.rawText()).append("\" 已解析为题目《")
                .append(resolution.title() == null ? "未命名题目" : resolution.title()).append("》");
        if (resolution.resolvedProblemId() != null) {
            block.append("（题目 ID：").append(resolution.resolvedProblemId()).append("）");
        }
        block.append("\n- 来源：").append(setDescriptor(resolution.setId(), resolution.setOrdinal(), focus));
        if (resolution.conversationOrdinal() != null) {
            block.append("；全会话第 ").append(resolution.conversationOrdinal()).append(" 题");
        }
        if (resolution.setId() != null) {
            block.append("；来源消息：").append(resolution.setId());
        }
        block.append("（resolver=").append(resolution.resolver())
                .append("，confidence=").append(resolution.confidence()).append("）\n");
        String statement = row == null ? null : row.getStatementSnapshot();
        if (statement != null && !statement.isBlank()) {
            block.append("- 题面节选：\n").append(truncate(statement, STATEMENT_EXCERPT_MAX)).append("\n");
        }
        block.append("- How to use it: 以上是该引用指向题目的权威题面快照，直接据此回答，不要再让用户重复粘贴题面。\n\n");
    }

    private void appendAmbiguityBlock(StringBuilder block, ReferenceResolver.Resolution resolution, FocusSetIds focus) {
        block.append("[Resolved Reference]\n")
                .append("- 引用 \"").append(resolution.rawText()).append("\" 存在歧义，尚未绑定到任何题目。候选：");
        int index = 1;
        List<String> labels = new ArrayList<>();
        for (ReferenceResolver.Candidate candidate : resolution.alternatives()) {
            labels.add(index + ") " + optionLabel(candidate, focus));
            index++;
        }
        block.append(String.join("；", labels)).append("\n")
                .append("- 已向用户发起澄清（clarification）。本轮不要讲解或绑定任何候选题目，"
                        + "只向用户确认他指的是哪一道；等用户回答后再继续。\n\n");
    }

    private String optionLabel(ReferenceResolver.Candidate candidate, FocusSetIds focus) {
        String title = candidate.title() == null || candidate.title().isBlank() ? "未命名题目" : candidate.title();
        return "《" + title + "》（" + setDescriptor(candidate.setId(), candidate.setOrdinal(), focus) + "）";
    }

    private String setDescriptor(String setId, Integer setOrdinal, FocusSetIds focus) {
        String ordinalText = setOrdinal == null ? "" : "第 " + setOrdinal + " 题";
        if (setId != null && setId.equals(focus.activeSetId())) {
            return "这一批" + ordinalText;
        }
        if (setId != null && setId.equals(focus.lastSetId())) {
            return "上一批" + ordinalText;
        }
        return setId == null ? "未知批次" + ordinalText : "较早一批" + ordinalText;
    }

    private String requestKey(String conversationId, AiChatRequest request, ReferenceResolver.Resolution ambiguous) {
        String clientMessageId = request == null ? null : request.clientMessageId();
        String base;
        if (clientMessageId != null && !clientMessageId.isBlank()) {
            base = clientMessageId.trim();
        } else {
            String seed = conversationId + "|" + (ambiguous == null ? "" : ambiguous.rawText());
            base = "anon_" + Integer.toHexString(seed.hashCode());
        }
        String key = ReferenceResolver.CLARIFICATION_KEY_PREFIX + base;
        return key.length() <= REQUEST_KEY_MAX ? key : key.substring(0, REQUEST_KEY_MAX);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            payload.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }

    private String truncate(String value, int max) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private record FocusSetIds(String activeSetId, String lastSetId) {
    }
}
