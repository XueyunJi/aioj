package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ConversationStateMerger {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ConversationStateMerger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MergeResult mergeBeforePrompt(String existingJson, AiChatRequest request) {
        Map<String, Object> state = readState(existingJson);
        mergeProblem(state, request);
        mergeCurrentMessage(state, request);
        mergeSubmissionFocus(state, request.submissionContext());
        mergeSelectionFocus(state, request.selectionContext());
        Map<String, Object> clarificationDelta = mergeClarificationAnswer(state, request.clarificationAnswer());
        refreshSummary(state);
        return new MergeResult(writeJson(state), clarificationDelta);
    }

    public String mergeAfterCompletion(String existingJson, Long latestCodeSnapshotId, AiChatRequest request, AiCompletion completion) {
        Map<String, Object> state = readState(existingJson);
        mergeProblem(state, request);
        mergeCodeState(state, latestCodeSnapshotId, request);
        mergeAssistantResult(state, completion);
        refreshSummary(state);
        return writeJson(state);
    }

    public Map<String, Object> interpretClarificationAnswer(AiChatRequest.ClarificationAnswer answer) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (answer == null) {
            return delta;
        }
        String text = firstNonBlank(answer.answerText(), answer.customText());
        String lower = normalize(text).toLowerCase(Locale.ROOT);
        List<String> targetSlots = new ArrayList<>();
        targetSlots.add("clarifications.answers");
        targetSlots.add("learningFlow.answeredClarificationIds");
        if (mentionsBinarySearchCheck(lower)) {
            targetSlots.add("algorithmState.decisions");
            targetSlots.add("learningFlow.userKnownPoints");
            targetSlots.add("learningFlow.userStuckPoints");
            targetSlots.add("learningFlow.currentStep");
            delta.put("interpretation", "用户理解到需要二分候选距离 d，并检查 d 是否可行，但还需要补充 check(d) 的具体贪心过程。");
            delta.put("userKnownPoint", "知道可以先二分候选距离 d，再检查可行性");
            delta.put("userStuckPoint", "尚未明确 check(d) 为什么要从左到右选择最靠左可行位置");
            delta.put("currentStep", "explain_greedy_check_d");
            delta.put("nextTeachingAction", "解释 check(d) 的贪心最早放置策略和正确性");
            delta.put("algorithmDecision", decision(
                    "二分候选最小距离 d，再检查 d 是否可行",
                    "user_clarification",
                    0.86
            ));
        } else if (containsAny(lower, "不知道", "不会", "没想出来", "不清楚", "不确定")) {
            targetSlots.add("learningFlow.userStuckPoints");
            targetSlots.add("learningFlow.currentStep");
            delta.put("interpretation", "用户明确表示当前澄清点仍不理解，需要降低提示粒度。");
            delta.put("userStuckPoint", "用户对上一个澄清问题仍不确定");
            delta.put("currentStep", "reduce_hint_granularity");
            delta.put("nextTeachingAction", "用更小的例子解释当前卡点");
        } else if (!text.isBlank()) {
            targetSlots.add("learningFlow.userKnownPoints");
            delta.put("interpretation", "用户补充了澄清答案，应先评价该答案再继续推进。");
            delta.put("userKnownPoint", truncate(text, 160));
            delta.put("nextTeachingAction", "评价用户补充并继续下一步");
        }
        delta.put("targetSlots", targetSlots);
        delta.put("question", normalize(answer.question()));
        delta.put("answerText", text);
        delta.put("requestId", normalize(answer.requestId()));
        delta.put("selectedOptionIds", answer.selectedOptionIds() == null ? List.of() : answer.selectedOptionIds());
        return delta;
    }

    public Map<String, Object> readState(String json) {
        if (json != null && !json.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
                return ensureShape(parsed);
            } catch (Exception ignored) {
                return emptyState();
            }
        }
        return emptyState();
    }

    public String writeJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(ensureShape(state));
        } catch (Exception ignored) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureShape(Map<String, Object> state) {
        Map<String, Object> value = state == null ? new LinkedHashMap<>() : state;
        value.computeIfAbsent("problem", ignored -> new LinkedHashMap<String, Object>());
        value.computeIfAbsent("previousProblems", ignored -> new ArrayList<Map<String, Object>>());
        value.computeIfAbsent("learningFlow", ignored -> learningFlow());
        value.computeIfAbsent("algorithmState", ignored -> algorithmState());
        value.computeIfAbsent("codeState", ignored -> new LinkedHashMap<String, Object>());
        value.computeIfAbsent("clarifications", ignored -> clarifications());
        value.computeIfAbsent("summary", ignored -> summary());
        return value;
    }

    private Map<String, Object> emptyState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("problem", new LinkedHashMap<String, Object>());
        state.put("previousProblems", new ArrayList<Map<String, Object>>());
        state.put("learningFlow", learningFlow());
        state.put("algorithmState", algorithmState());
        state.put("codeState", new LinkedHashMap<String, Object>());
        state.put("clarifications", clarifications());
        state.put("summary", summary());
        return state;
    }

    private Map<String, Object> learningFlow() {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("mode", "socratic_hint");
        flow.put("currentGoal", "guide_to_solution");
        flow.put("currentStep", "understand_problem");
        flow.put("answeredClarificationIds", new ArrayList<String>());
        flow.put("pendingClarificationIds", new ArrayList<String>());
        flow.put("doNotRepeatQuestions", new ArrayList<String>());
        flow.put("userKnownPoints", new ArrayList<String>());
        flow.put("userStuckPoints", new ArrayList<String>());
        flow.put("nextTeachingAction", "ask_for_problem_if_missing");
        return flow;
    }

    private Map<String, Object> algorithmState() {
        Map<String, Object> algorithm = new LinkedHashMap<>();
        algorithm.put("candidateApproach", "");
        algorithm.put("decisions", new ArrayList<Map<String, Object>>());
        algorithm.put("unverifiedAssistantClaims", new ArrayList<String>());
        algorithm.put("correctedClaims", new ArrayList<String>());
        return algorithm;
    }

    private Map<String, Object> clarifications() {
        Map<String, Object> clarifications = new LinkedHashMap<>();
        clarifications.put("requests", new ArrayList<Map<String, Object>>());
        clarifications.put("answers", new ArrayList<Map<String, Object>>());
        return clarifications;
    }

    private Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lastCompactedMessageId", null);
        summary.put("summaryVersion", 0);
        summary.put("tokenEstimate", 0);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private void mergeProblem(Map<String, Object> state, AiChatRequest request) {
        Map<String, Object> problem = map(state, "problem");
        if (restorePreviousProblemIfRequested(state, request)) {
            applyProblemTeachingState(state, problem);
            return;
        }

        Map<String, Object> detected = detectProblem(request);
        if (detected.isEmpty()) {
            applyProblemTeachingState(state, problem);
            return;
        }

        String currentKey = string(problem.get("activeProblemKey"));
        String detectedKey = string(detected.get("activeProblemKey"));
        boolean hasCurrent = !currentKey.isBlank() || hasText(problem.get("statementSummary")) || hasText(problem.get("title"));
        boolean sameProblem = !detectedKey.isBlank() && detectedKey.equals(currentKey);
        if (hasCurrent && !sameProblem) {
            moveCurrentProblemToPrevious(state, "problem_switch");
            problem.clear();
            problem.putAll(detected);
            problem.put("status", "active");
            problem.put("switchReason", "new_problem_detected");
        } else if (!hasCurrent) {
            problem.putAll(detected);
            problem.put("status", "active");
        } else {
            mergeDetectedIntoCurrent(problem, detected);
        }
        applyProblemTeachingState(state, problem);
    }

    private Map<String, Object> detectProblem(AiChatRequest request) {
        Map<String, Object> detected = new LinkedHashMap<>();
        AiChatRequest.ProblemContext context = request.problemContext();
        if (context != null) {
            String statement = normalize(context.statement());
            String title = firstNonBlank(context.title(), "题目");
            String key = firstNonBlank(context.id(), request.problemId() == null ? "" : String.valueOf(request.problemId()));
            if (key.isBlank()) {
                key = "context:" + Integer.toHexString((title + "\n" + summarizeStatement(statement)).hashCode());
            }
            detected.put("activeProblemKey", key);
            putIfText(detected, "problemId", firstNonBlank(context.id(), request.problemId() == null ? null : String.valueOf(request.problemId())));
            putIfText(detected, "title", title);
            putIfText(detected, "statementSummary", summarizeStatement(statement));
            putIfText(detected, "inputSummary", inferInputSummary(statement));
            putIfText(detected, "outputSummary", inferOutputSummary(statement));
            detected.put("constraints", inferConstraints(statement));
            detected.put("tags", normalizeTags(context.tags(), context.title(), statement));
            detected.put("timeLimitMillis", context.timeLimitMillis());
            detected.put("memoryLimitKb", context.memoryLimitKb());
            return detected;
        }
        String message = normalize(request.message());
        if (looksLikeStarportProblem(message)) {
            detected.put("activeProblemKey", "detected:starport_max_min_distance");
            detected.put("title", "最大化最小距离 / 星港建设");
            detected.put("statementSummary", "在一条直线上从 n 个候选坐标中选 m 个，使任意两个被选星港之间的最小距离最大。");
            detected.put("inputSummary", "第一行 n,m；接下来 n 行坐标 xi。");
            detected.put("outputSummary", "输出最大可能的最小距离。");
            detected.put("constraints", List.of("2 <= m <= n <= 2e5", "0 <= xi <= 1e9", "xi 两两不同", "输入顺序不保证有序"));
            detected.put("tags", List.of("sorting", "binary_search_on_answer", "greedy"));
            return detected;
        }
        if (looksLikeKnapsackProblem(message)) {
            detected.put("activeProblemKey", "detected:knapsack_dp");
            detected.put("title", "背包 / 动态规划题");
            detected.put("statementSummary", "在容量限制下从若干物品中选择，使总价值或收益最大。");
            detected.put("inputSummary", "通常包含物品数量、容量，以及每个物品的重量和价值。");
            detected.put("outputSummary", "输出容量限制下可获得的最优值。");
            detected.put("constraints", inferConstraints(message));
            detected.put("tags", List.of("dynamic_programming", "knapsack"));
            return detected;
        }
        return detected;
    }

    private void mergeDetectedIntoCurrent(Map<String, Object> problem, Map<String, Object> detected) {
        putIfText(problem, "activeProblemKey", string(detected.get("activeProblemKey")));
        putIfText(problem, "problemId", string(detected.get("problemId")));
        putIfText(problem, "title", string(detected.get("title")));
        putIfText(problem, "statementSummary", string(detected.get("statementSummary")));
        putIfText(problem, "inputSummary", string(detected.get("inputSummary")));
        putIfText(problem, "outputSummary", string(detected.get("outputSummary")));
        mergeList(problem, "constraints", list(detected.get("constraints")).stream().map(String::valueOf).toList());
        mergeList(problem, "tags", list(detected.get("tags")).stream().map(String::valueOf).toList());
        if (detected.get("timeLimitMillis") != null) {
            problem.put("timeLimitMillis", detected.get("timeLimitMillis"));
        }
        if (detected.get("memoryLimitKb") != null) {
            problem.put("memoryLimitKb", detected.get("memoryLimitKb"));
        }
        problem.put("status", "active");
    }

    private void moveCurrentProblemToPrevious(Map<String, Object> state, String reason) {
        Map<String, Object> current = map(state, "problem");
        if (!hasText(current.get("title")) && !hasText(current.get("statementSummary"))) {
            return;
        }
        Map<String, Object> previous = new LinkedHashMap<>(current);
        previous.put("status", "inactive");
        previous.put("switchReason", reason);
        previous.put("lastActiveAt", LocalDateTime.now().toString());
        List<Object> previousProblems = list(state, "previousProblems");
        String key = string(previous.get("activeProblemKey"));
        previousProblems.removeIf(item -> key.equals(string(mapObject(item).get("activeProblemKey"))));
        previousProblems.add(0, previous);
        while (previousProblems.size() > 5) {
            previousProblems.remove(previousProblems.size() - 1);
        }
    }

    private boolean restorePreviousProblemIfRequested(Map<String, Object> state, AiChatRequest request) {
        String message = normalize(request.message());
        if (!containsAny(message, "继续刚才", "刚才那题", "上一题", "之前那题")) {
            return false;
        }
        List<Object> previousProblems = list(state, "previousProblems");
        if (previousProblems.isEmpty()) {
            return false;
        }
        int restoreIndex = -1;
        for (int i = 0; i < previousProblems.size(); i++) {
            Map<String, Object> previous = mapObject(previousProblems.get(i));
            String title = string(previous.get("title"));
            String key = string(previous.get("activeProblemKey"));
            if ((!title.isBlank() && message.contains(title)) || (message.contains("星港") && key.contains("starport")) || (message.contains("背包") && key.contains("knapsack"))) {
                restoreIndex = i;
                break;
            }
        }
        if (restoreIndex < 0 && containsAny(message, "上一题", "之前那题")) {
            restoreIndex = 0;
        }
        if (restoreIndex < 0) {
            return false;
        }
        Map<String, Object> restored = new LinkedHashMap<>(mapObject(previousProblems.remove(restoreIndex)));
        moveCurrentProblemToPrevious(state, "restore_previous_problem");
        Map<String, Object> current = map(state, "problem");
        current.clear();
        current.putAll(restored);
        current.put("status", "active");
        current.put("switchReason", "restored_previous_problem");
        return true;
    }

    private void applyProblemTeachingState(Map<String, Object> state, Map<String, Object> problem) {
        if (list(problem, "tags").isEmpty()) {
            return;
        }
        Map<String, Object> flow = map(state, "learningFlow");
        String tags = String.join(" ", list(problem, "tags").stream().map(String::valueOf).toList());
        if (containsAny(tags, "binary_search_on_answer", "二分")) {
            String currentStep = string(flow.get("currentStep"));
            if (!"explain_greedy_check_d".equals(currentStep) && !"reduce_hint_granularity".equals(currentStep)) {
                flow.put("currentStep", "explain_feasibility_check");
            }
            map(state, "algorithmState").put("candidateApproach", "排序 + 二分答案 + 贪心可行性检查");
        } else if (containsAny(tags, "dynamic_programming", "knapsack")) {
            flow.put("currentStep", "define_dp_state_transition");
            map(state, "algorithmState").put("candidateApproach", "动态规划 / 0-1 背包状态转移");
        }
    }

    private void mergeCurrentMessage(Map<String, Object> state, AiChatRequest request) {
        String message = normalize(request.message());
        String lower = message.toLowerCase(Locale.ROOT);
        Map<String, Object> flow = map(state, "learningFlow");
        if (containsAny(lower, "继续", "刚才", "上面", "这个")) {
            flow.put("currentGoal", "continue_current_context");
            flow.put("nextTeachingAction", "优先承接当前会话状态和最近原文窗口");
        }
        if (containsAny(lower, "不要给代码", "先不要给代码", "先别给代码")) {
            flow.put("currentGoal", "hint_without_code");
            flow.put("nextTeachingAction", "只给提示和关键观察，不给完整代码");
        }
        if (containsAny(lower, "完整代码", "正确代码", "给代码")) {
            flow.put("currentGoal", "provide_code_after_explanation");
            flow.put("nextTeachingAction", "先说明核心思路和复杂度，再给完整代码");
        }
        if (containsAny(lower, "wa", "wrong answer", "答案错误", "tle", "超时", "re", "运行错误", "ce", "编译错误")) {
            map(state, "codeState").put("latestErrorType", inferErrorType(lower));
            map(state, "codeState").put("latestErrorSummary", truncate(message, 300));
        }
    }

    private void mergeSelectionFocus(Map<String, Object> state, AiChatRequest.SelectionContext selection) {
        if (selection == null || normalize(selection.selectedText()).isBlank()) {
            state.remove("transientFocus");
            return;
        }
        Map<String, Object> focus = new LinkedHashMap<>();
        putIfText(focus, "selectionId", selection.selectionId());
        putIfText(focus, "sourceType", selection.sourceType());
        putIfText(focus, "sourceMessageId", selection.sourceMessageId());
        putIfText(focus, "sourceRole", selection.sourceRole());
        putIfText(focus, "uiIntent", selection.uiIntent());
        putIfText(focus, "selectedText", truncate(selection.selectedText(), 500));
        if (selection.selectionRange() != null) {
            focus.put("selectionRange", selection.selectionRange());
        }
        if (selection.codeContext() != null) {
            focus.put("codeContext", selection.codeContext());
        }
        if (selection.problemContext() != null) {
            focus.put("problemContext", selection.problemContext());
        }
        state.put("transientFocus", focus);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeClarificationAnswer(Map<String, Object> state, AiChatRequest.ClarificationAnswer answer) {
        if (answer == null) {
            return Map.of();
        }
        Map<String, Object> delta = interpretClarificationAnswer(answer);
        String requestId = normalize(answer.requestId());
        String question = normalize(answer.question());
        Map<String, Object> flow = map(state, "learningFlow");
        Map<String, Object> clarifications = map(state, "clarifications");
        if (!requestId.isBlank()) {
            addUnique(list(flow, "answeredClarificationIds"), requestId);
            list(flow, "pendingClarificationIds").removeIf(item -> requestId.equals(String.valueOf(item)));
        }
        if (!question.isBlank()) {
            addUnique(list(flow, "doNotRepeatQuestions"), question);
        }
        putIfText(flow, "currentStep", string(delta.get("currentStep")));
        putIfText(flow, "nextTeachingAction", string(delta.get("nextTeachingAction")));
        addUniqueIfPresent(flow, "userKnownPoints", string(delta.get("userKnownPoint")));
        addUniqueIfPresent(flow, "userStuckPoints", string(delta.get("userStuckPoint")));
        Object decision = delta.get("algorithmDecision");
        if (decision instanceof Map<?, ?> decisionMap) {
            addUniqueDecision(list(map(state, "algorithmState"), "decisions"), decisionMap);
        }
        List<Object> answers = list(clarifications, "answers");
        Map<String, Object> answerRecord = new LinkedHashMap<>();
        answerRecord.put("requestId", requestId);
        answerRecord.put("question", question);
        answerRecord.put("answerText", firstNonBlank(answer.answerText(), answer.customText()));
        answerRecord.put("selectedOptionIds", answer.selectedOptionIds() == null ? List.of() : answer.selectedOptionIds());
        answerRecord.put("interpretation", delta.get("interpretation"));
        addUniqueByKey(answers, answerRecord, "requestId", "answerText");
        return delta;
    }

    private void mergeCodeState(Map<String, Object> state, Long latestCodeSnapshotId, AiChatRequest request) {
        Map<String, Object> code = map(state, "codeState");
        if (latestCodeSnapshotId != null) {
            code.put("latestCodeSnapshotId", latestCodeSnapshotId);
        }
        if (request.codeContext() != null) {
            putIfText(code, "language", request.codeContext().language());
        }
        if (request.submissionContext() != null && request.submissionContext().submissionId() != null) {
            code.put("latestSubmissionId", request.submissionContext().submissionId());
            putIfText(code, "latestSubmissionIntent", request.submissionContext().intent());
            code.put("latestSubmissionUserSelected", Boolean.TRUE.equals(request.submissionContext().userSelected()));
        }
    }

    private void mergeAssistantResult(Map<String, Object> state, AiCompletion completion) {
        Map<String, Object> flow = map(state, "learningFlow");
        Map<String, Object> codeState = map(state, "codeState");
        if (looksLikeCompleteCode(completion.content())) {
            codeState.put("latestAssistantProvidedCode", true);
            codeState.put("latestAssistantCodeSummary", truncate(completion.content(), 360));
        }
        if (completion.hasClarification()) {
            AiCompletion.Clarification clarification = completion.clarification();
            String requestId = normalize(clarification.id());
            if (!requestId.isBlank()) {
                addUnique(list(flow, "pendingClarificationIds"), requestId);
            }
            Map<String, Object> clarifications = map(state, "clarifications");
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("id", requestId);
            request.put("question", clarification.prompt());
            request.put("priority", clarification.priority());
            request.put("kind", clarification.input() == null ? null : clarification.input().kind());
            addUniqueByKey(list(clarifications, "requests"), request, "id", "question");
            addUniqueIfPresent(flow, "doNotRepeatQuestions", clarification.prompt());
        }
        if ("CLARIFY".equalsIgnoreCase(completion.teachingDecision())) {
            flow.put("currentGoal", "clarify_context");
        }
    }

    private void mergeSubmissionFocus(Map<String, Object> state, AiChatRequest.SubmissionContext submission) {
        if (submission == null || submission.submissionId() == null) {
            return;
        }
        Map<String, Object> code = map(state, "codeState");
        code.put("latestSubmissionId", submission.submissionId());
        putIfText(code, "latestSubmissionIntent", submission.intent());
        code.put("latestSubmissionUserSelected", Boolean.TRUE.equals(submission.userSelected()));
        Map<String, Object> flow = map(state, "learningFlow");
        flow.put("currentGoal", "debug_selected_submission");
        flow.put("nextTeachingAction", "优先分析用户选中的提交结果和服务端评测上下文");
    }

    private void refreshSummary(Map<String, Object> state) {
        Map<String, Object> summary = map(state, "summary");
        summary.put("tokenEstimate", Math.max(1, writeJson(state).length() / 4));
    }

    private List<String> normalizeTags(List<String> provided, String title, String statement) {
        Set<String> tags = new LinkedHashSet<>();
        if (provided != null) {
            provided.stream().filter(tag -> tag != null && !tag.isBlank()).map(String::trim).forEach(tags::add);
        }
        String text = (normalize(title) + "\n" + normalize(statement)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "最大化最小", "最小距离", "二分答案", "binary search")) {
            tags.add("sorting");
            tags.add("binary_search_on_answer");
            tags.add("greedy");
        }
        if (containsAny(text, "二分")) {
            tags.add("binary_search");
        }
        if (looksLikeKnapsackProblem(text)) {
            tags.add("dynamic_programming");
            tags.add("knapsack");
        }
        return new ArrayList<>(tags);
    }

    private List<String> inferConstraints(String statement) {
        String text = normalize(statement);
        List<String> constraints = new ArrayList<>();
        if (containsAny(text, "2e5", "200000", "2 * 10^5")) constraints.add("n <= 2e5");
        if (containsAny(text, "1e9", "1000000000", "10^9")) constraints.add("xi <= 1e9");
        if (containsAny(text, "m <= n", "m≤n")) constraints.add("2 <= m <= n");
        if (containsAny(text, "互不相同", "两两不同", "distinct")) constraints.add("xi 两两不同");
        return constraints;
    }

    private String summarizeStatement(String statement) {
        String text = normalize(statement).replaceAll("\\s+", " ");
        if (looksLikeStarportProblem(text)) {
            return "在一条直线上从 n 个候选坐标中选 m 个，使任意两个被选星港之间的最小距离最大。";
        }
        return truncate(text, 420);
    }

    private String inferInputSummary(String statement) {
        String text = normalize(statement);
        if (looksLikeStarportProblem(text)) return "第一行 n,m；接下来 n 行坐标 xi。";
        if (containsAny(text, "输入格式", "Input")) return truncate(sectionAfter(text, "输入"), 180);
        return "";
    }

    private String inferOutputSummary(String statement) {
        String text = normalize(statement);
        if (looksLikeStarportProblem(text)) return "输出最大可能的最小距离。";
        if (containsAny(text, "输出格式", "Output")) return truncate(sectionAfter(text, "输出"), 180);
        return "";
    }

    private String sectionAfter(String text, String marker) {
        int index = text.indexOf(marker);
        if (index < 0) return "";
        return text.substring(index).replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeStarportProblem(String text) {
        String normalized = normalize(text);
        return containsAny(normalized, "星港", "最大化最小距离", "最大最小距离", "最小距离最大", "aggressive cows")
                || (containsAny(normalized, "最小距离", "最大") && containsAny(normalized, "选择 m", "选出 m", "m 个"));
    }

    private boolean looksLikeKnapsackProblem(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        return containsAny(normalized, "背包", "knapsack")
                || (containsAny(normalized, "物品", "items") && containsAny(normalized, "容量", "重量", "weight") && containsAny(normalized, "价值", "value"))
                || (containsAny(normalized, "dp", "动态规划", "状态转移") && containsAny(normalized, "容量", "物品"));
    }

    private boolean mentionsBinarySearchCheck(String lower) {
        return containsAny(lower, "二分", "binary") && containsAny(lower, "检查", "check", "可行", "合理", "距离");
    }

    private Map<String, Object> decision(String text, String source, double confidence) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("text", text);
        decision.put("source", source);
        decision.put("confidence", confidence);
        return decision;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> next = new LinkedHashMap<>();
        parent.put(key, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof List<?> existing) {
            return (List<Object>) existing;
        }
        List<Object> next = new ArrayList<>();
        parent.put(key, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        if (value instanceof List<?> existing) {
            return (List<Object>) existing;
        }
        return List.of();
    }

    private void mergeList(Map<String, Object> parent, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<Object> existing = list(parent, key);
        values.stream().filter(value -> value != null && !value.isBlank()).forEach(value -> addUnique(existing, value.trim()));
    }

    private void addUniqueIfPresent(Map<String, Object> parent, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        addUnique(list(parent, key), value);
    }

    private void addUnique(List<Object> list, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        boolean exists = list.stream().map(String::valueOf).anyMatch(value::equals);
        if (!exists) {
            list.add(value);
        }
    }

    private void addUniqueDecision(List<Object> decisions, Map<?, ?> decision) {
        String text = String.valueOf(decision.get("text"));
        boolean exists = decisions.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> String.valueOf(item.get("text")))
                .anyMatch(text::equals);
        if (!exists) {
            decisions.add(new LinkedHashMap<>(decision));
        }
    }

    private void addUniqueByKey(List<Object> records, Map<String, Object> record, String firstKey, String fallbackKey) {
        String primary = string(record.get(firstKey));
        String fallback = string(record.get(fallbackKey));
        boolean exists = records.stream().filter(Map.class::isInstance).map(Map.class::cast).anyMatch(item -> {
            String itemPrimary = string(item.get(firstKey));
            String itemFallback = string(item.get(fallbackKey));
            return (!primary.isBlank() && primary.equals(itemPrimary)) || (!fallback.isBlank() && fallback.equals(itemFallback));
        });
        if (!exists) {
            records.add(record);
        }
    }

    private void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCompleteCode(String value) {
        String text = normalize(value);
        if (text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.contains("```")) score++;
        if (lower.contains("#include") || lower.contains("int main(")) score += 2;
        if (lower.contains("public class") && lower.contains("static void main")) score += 2;
        if (lower.contains("def main") || lower.contains("if __name__")) score += 2;
        if (text.lines().count() >= 16 && (lower.contains("return") || lower.contains("for ") || lower.contains("while "))) score++;
        return score >= 2;
    }

    private String inferErrorType(String lower) {
        if (containsAny(lower, "tle", "超时", "time limit")) return "TIME_LIMIT";
        if (containsAny(lower, "re", "runtime", "运行错误")) return "RUNTIME_ERROR";
        if (containsAny(lower, "ce", "compile", "编译错误")) return "COMPILE_ERROR";
        if (containsAny(lower, "wa", "wrong answer", "答案错误")) return "WRONG_ANSWER";
        return "UNKNOWN";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : normalize(second);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String truncate(String value, int max) {
        String normalized = normalize(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapObject(Object value) {
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        return Map.of();
    }

    public record MergeResult(String stateJson, Map<String, Object> clarificationDelta) {
    }
}
