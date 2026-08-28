package com.aioj.next.ai.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiStructuredSubmissionAnalysisService {
    private static final int MAX_TAGS = 12;
    private static final Set<String> NON_PROFILE_STATUSES = Set.of("ACCEPTED", "COMPILE_ERROR", "SYSTEM_ERROR", "QUEUED", "RUNNING");

    private final ObjectMapper objectMapper;
    private final AiModelConfigResolver configResolver;
    private final AiModelCompletionClient completionClient;
    private final AiQuotaService quotaService;
    private final AiCapacityService capacityService;

    public AiStructuredSubmissionAnalysisService(
            ObjectMapper objectMapper,
            AiModelConfigResolver configResolver,
            AiModelCompletionClient completionClient,
            AiQuotaService quotaService,
            AiCapacityService capacityService
    ) {
        this.objectMapper = objectMapper;
        this.configResolver = configResolver;
        this.completionClient = completionClient;
        this.quotaService = quotaService;
        this.capacityService = capacityService;
    }

    public AnalysisResult analyze(Long userId, AiChatRequest request, AiCompletion completion, AiSubmissionContextResponse context) {
        AnalysisResult fallback = fallbackAnalysis(request, completion, context);
        AiModelEffectiveConfig config = configResolver.effectiveConfig(AiModelScope.MEMORY_EXTRACTION);
        if (config == null || !config.enabled() || !config.hasApiKey()) {
            return fallback;
        }
        try {
            return capacityService.call(AiCapacityService.AiWorkload.INTENT_MEMORY, () -> {
                quotaService.assertMonthlyAvailable(userId);
                try {
                    AiModelCompletionClient.CompletionResult result = completionClient.complete(
                            config,
                            List.of(
                                    message("system", "你是 AI-OJ 的提交分析结构化器。只输出严格 JSON 对象，不要输出 Markdown 或 JSON 外文本。"),
                                    message("user", analysisPrompt(request, completion, context))
                            ),
                            config.temperatureOr(0.1),
                            Math.min(config.maxTokensOr(1400), 1800),
                            true
                    );
                    quotaService.record(userId, result.provider(), result.model(),
                            result.promptTokens(), result.completionTokens(), true, request == null ? null : request.contestContext());
                    try {
                        return normalizeModelResult(parseJson(result.content()), fallback, context);
                    } catch (RuntimeException ex) {
                        return fallback;
                    }
                } catch (DomainException ex) {
                    if (ex.errorCode() != ErrorCode.TOO_MANY_REQUESTS) {
                        quotaService.record(userId, config.provider(), config.model(), 0, 0, false,
                                request == null ? null : request.contestContext());
                    }
                    return fallback;
                } catch (RuntimeException ex) {
                    quotaService.record(userId, config.provider(), config.model(), 0, 0, false,
                            request == null ? null : request.contestContext());
                    return fallback;
                }
            });
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private AnalysisResult normalizeModelResult(JsonNode root, AnalysisResult fallback, AiSubmissionContextResponse context) {
        if (root == null || !root.isObject()) {
            return fallback;
        }
        List<String> rootCauseTags = safeTags(root.path("rootCauseTags"));
        List<String> algorithmTags = safeTags(root.path("algorithmTags"));
        List<String> bugPatternTags = safeTags(root.path("bugPatternTags"));
        List<String> complexityTags = safeTags(root.path("complexityTags"));
        List<String> nextSteps = safeTexts(root.path("nextSteps"), 8, 180);
        List<String> evidenceItems = safeTexts(root.path("evidenceItems"), 8, 240);
        String status = status(context);
        boolean accepted = "ACCEPTED".equals(status);
        boolean masteryEvidence = accepted && root.path("masteryEvidence").asBoolean(fallback.masteryEvidence());
        boolean profileEligible = root.path("profileEligible").asBoolean(fallback.profileEligible()) && profileEligibleStatus(status);
        String profileKey = normalizeTag(root.path("profileKey").asText(""));
        if (profileKey.isBlank()) {
            profileKey = fallback.profileKey();
        }
        String profileLabel = safeText(root.path("profileLabel").asText(""), 200);
        if (profileLabel.isBlank()) {
            profileLabel = fallback.profileLabel();
        }
        String summary = safeText(root.path("summary").asText(""), 600);
        if (summary.isBlank()) {
            summary = fallback.summary();
        }
        double confidence = clamp(root.path("confidence").asDouble(fallback.confidence()), 0.0, 1.0);
        if (rootCauseTags.isEmpty()) {
            rootCauseTags = fallback.rootCauseTags();
        }
        if (algorithmTags.isEmpty()) {
            algorithmTags = fallback.algorithmTags();
        }
        if (bugPatternTags.isEmpty()) {
            bugPatternTags = fallback.bugPatternTags();
        }
        if (complexityTags.isEmpty()) {
            complexityTags = fallback.complexityTags();
        }
        if (nextSteps.isEmpty()) {
            nextSteps = fallback.nextSteps();
        }
        if (evidenceItems.isEmpty()) {
            evidenceItems = fallback.evidenceItems();
        }
        return new AnalysisResult(
                summary,
                rootCauseTags,
                algorithmTags,
                bugPatternTags,
                complexityTags,
                nextSteps,
                evidenceItems,
                confidence,
                profileKey,
                profileLabel,
                profileEligible,
                masteryEvidence,
                true
        );
    }

    private JsonNode parseJson(String content) {
        try {
            return objectMapper.readTree(extractJson(content));
        } catch (Exception ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "AI submission analysis response could not be parsed");
        }
    }

    private AnalysisResult fallbackAnalysis(AiChatRequest request, AiCompletion completion, AiSubmissionContextResponse context) {
        String status = status(context);
        String algorithm = primaryAlgorithm(context, request, completion);
        List<String> rootCauseTags = new ArrayList<>();
        rootCauseTags.add(statusKey(status));
        List<String> algorithmTags = algorithm.isBlank() ? List.of() : List.of(algorithm);
        List<String> bugPatternTags = bugPatterns(context, request, completion);
        List<String> complexityTags = complexityTags(status, context, request, completion);
        boolean masteryEvidence = "ACCEPTED".equals(status) && hasMasterySignal(request, completion, algorithm);
        boolean profileEligible = profileEligibleStatus(status);
        if ("ACCEPTED".equals(status)) {
            profileEligible = false;
        }
        String profileKey = profileKey(status, algorithm, bugPatternTags, complexityTags);
        String title = context.problemContext() == null ? "" : safeText(context.problemContext().title(), 80);
        String profileLabel = "提交分析候选弱点：" + status + (title.isBlank() ? "" : " / " + title);
        String summary = fallbackSummary(status, algorithm, bugPatternTags, complexityTags, masteryEvidence);
        List<String> nextSteps = nextSteps(status, algorithm, bugPatternTags, complexityTags, masteryEvidence);
        List<String> evidenceItems = fallbackEvidence(context, completion);
        double confidence = "ACCEPTED".equals(status) ? 0.78 : "COMPILE_ERROR".equals(status) || "SYSTEM_ERROR".equals(status) ? 0.55 : 0.64;
        return new AnalysisResult(
                summary,
                distinct(rootCauseTags),
                algorithmTags,
                bugPatternTags,
                complexityTags,
                nextSteps,
                evidenceItems,
                confidence,
                profileKey,
                profileLabel,
                profileEligible,
                masteryEvidence,
                false
        );
    }

    private String analysisPrompt(AiChatRequest request, AiCompletion completion, AiSubmissionContextResponse context) {
        AiProblemContextResponse problem = context.problemContext();
        return """
                请把本次 AI-OJ 提交分析整理成严格 JSON 对象。
                不要输出代码，不要复原源码，不要输出 raw stdout/stderr，不要输出 token/key/password。
                只根据给定安全上下文判断；不确定的字段用空数组或 false。

                JSON 字段：
                {
                  "summary":"string, <= 300 chars",
                  "rootCauseTags":["wrong_answer|time_limit|runtime_error|compile_error|accepted|..."],
                  "algorithmTags":["binary_search|dynamic_programming|greedy|graph|..."],
                  "bugPatternTags":["boundary|monotonicity|overflow|state_transition|input_parsing|..."],
                  "complexityTags":["time_complexity|space_complexity|..."],
                  "nextSteps":["safe short suggestion"],
                  "evidenceItems":["safe short evidence"],
                  "confidence":0.0,
                  "profileKey":"snake_case",
                  "profileLabel":"string",
                  "profileEligible":true,
                  "masteryEvidence":false
                }

                规则：
                - COMPILE_ERROR 和 SYSTEM_ERROR 默认 profileEligible=false。
                - ACCEPTED 可以 masteryEvidence=true，但 profileEligible=false，不创建弱点。
                - TLE 可以给 complexityTags 并 profileEligible=true。
                - 只有 AC 且用户/AI 文本体现解释、复盘、掌握或证明时 masteryEvidence=true。
                - profileKey 必须表达 status + 主题，例如 wrong_answer_binary_search 或 time_limit_complexity。

                <SUBMISSION>
                id=%s
                problemId=%s
                status=%s
                language=%s
                codeHash=%s
                judgeMessage=%s
                runTimeMillis=%s
                memoryKb=%s
                score=%s/%s
                cases=%s
                </SUBMISSION>

                <PROBLEM>
                title=%s
                difficulty=%s
                tags=%s
                constraints=%s
                summary=%s
                </PROBLEM>

                <USER_MESSAGE>
                %s
                </USER_MESSAGE>

                <AI_ANSWER>
                %s
                </AI_ANSWER>
                """.formatted(
                context.submissionId(),
                context.problemId(),
                status(context),
                safeText(context.language(), 48),
                safeText(context.codeHash(), 128),
                safeText(context.judgeMessage(), 500),
                context.runTimeMillis(),
                context.memoryKb(),
                context.score(),
                context.maxScore(),
                caseSummary(context.caseResults()),
                problem == null ? "" : safeText(problem.title(), 120),
                problem == null ? "" : safeText(problem.difficulty(), 32),
                problem == null ? List.of() : safeTags(problem.tags()),
                problem == null ? List.of() : safeTexts(problem.constraints(), 6, 120),
                problem == null ? "" : safeText(problem.statementSummary(), 800),
                safeText(request == null ? null : request.message(), 1200),
                safeText(completion == null ? null : completion.content(), 1800)
        );
    }

    private List<String> caseSummary(List<AiSubmissionCaseContext> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (AiSubmissionCaseContext item : cases) {
            if (item == null || values.size() >= 6) {
                continue;
            }
            values.add("case=%s status=%s score=%s/%s time=%sms memory=%skb message=%s".formatted(
                    item.caseIndex(),
                    safeText(item.status(), 48),
                    item.score(),
                    item.maxScore(),
                    item.timeMillis(),
                    item.memoryKb(),
                    safeText(item.message(), 120)
            ));
        }
        return values;
    }

    private String primaryAlgorithm(AiSubmissionContextResponse context, AiChatRequest request, AiCompletion completion) {
        String text = textForMatching(context, request, completion);
        if (containsAny(text, "binary", "二分", "check", "单调", "最大化最小值", "最小化最大值")) {
            return "binary_search";
        }
        if (containsAny(text, "dynamic", "dp", "动态规划", "knapsack", "背包")) {
            return "dynamic_programming";
        }
        if (containsAny(text, "greedy", "贪心")) {
            return "greedy";
        }
        if (containsAny(text, "graph", "bfs", "dfs", "shortest", "图论", "最短路")) {
            return "graph";
        }
        return "debugging";
    }

    private List<String> bugPatterns(AiSubmissionContextResponse context, AiChatRequest request, AiCompletion completion) {
        String text = textForMatching(context, request, completion);
        List<String> tags = new ArrayList<>();
        if (containsAny(text, "boundary", "边界", "off by one", "off-by-one")) {
            tags.add("boundary");
        }
        if (containsAny(text, "monotonic", "单调", "check")) {
            tags.add("monotonicity");
        }
        if (containsAny(text, "overflow", "溢出", "long long")) {
            tags.add("overflow");
        }
        if (containsAny(text, "state", "transition", "状态", "转移")) {
            tags.add("state_transition");
        }
        if (containsAny(text, "input", "parse", "读入", "输入")) {
            tags.add("input_parsing");
        }
        if (tags.isEmpty() && isFailureStatus(status(context))) {
            tags.add("debugging");
        }
        return distinct(tags);
    }

    private List<String> complexityTags(String status, AiSubmissionContextResponse context, AiChatRequest request, AiCompletion completion) {
        String text = textForMatching(context, request, completion);
        List<String> tags = new ArrayList<>();
        if ("TIME_LIMIT_EXCEEDED".equals(status) || "TLE".equals(status) || containsAny(text, "time limit", "tle", "超时", "复杂度")) {
            tags.add("time_complexity");
        }
        if ("MEMORY_LIMIT_EXCEEDED".equals(status) || containsAny(text, "memory limit", "mle", "空间", "内存")) {
            tags.add("space_complexity");
        }
        return distinct(tags);
    }

    private String fallbackSummary(String status, String algorithm, List<String> bugPatterns, List<String> complexityTags, boolean masteryEvidence) {
        if ("ACCEPTED".equals(status)) {
            return masteryEvidence ? "通过提交包含可用于解决旧弱点的掌握证据。" : "通过提交可作为旧弱点的反证，但缺少明确掌握解释。";
        }
        if (!complexityTags.isEmpty()) {
            return "提交失败与复杂度或资源使用有关，需要复查算法复杂度和边界规模。";
        }
        if (!bugPatterns.isEmpty() && !"debugging".equals(bugPatterns.get(0))) {
            return "提交失败疑似与 %s 相关，需要围绕 %s 检查。".formatted(String.join(", ", bugPatterns), algorithm);
        }
        return "提交失败，需要结合评测状态、题目标签和 AI 回答继续调试。";
    }

    private List<String> nextSteps(String status, String algorithm, List<String> bugPatterns, List<String> complexityTags, boolean masteryEvidence) {
        if ("ACCEPTED".equals(status)) {
            return masteryEvidence ? List.of("保留通过原因和关键不变量，后续遇到同类题复用。") : List.of("补充解释通过原因后再作为掌握证据。");
        }
        if (!complexityTags.isEmpty()) {
            return List.of("按最大数据范围估算时间/空间复杂度。", "检查是否存在可剪枝、二分、DP 优化或数据结构优化。");
        }
        if ("binary_search".equals(algorithm)) {
            return List.of("检查 check 函数单调性。", "用最小值、最大值和相邻边界构造反例。");
        }
        if (!bugPatterns.isEmpty()) {
            return List.of("围绕 " + String.join(", ", bugPatterns) + " 构造最小反例。");
        }
        return List.of("先定位首个失败用例类型，再缩小到边界、初始化或状态转移。");
    }

    private List<String> fallbackEvidence(AiSubmissionContextResponse context, AiCompletion completion) {
        List<String> evidence = new ArrayList<>();
        if (context.judgeMessage() != null && !context.judgeMessage().isBlank()) {
            evidence.add(safeText(context.judgeMessage(), 180));
        }
        if (completion != null && completion.content() != null && !completion.content().isBlank()) {
            evidence.add(safeText(completion.content(), 240));
        }
        return evidence.stream().filter(value -> !value.isBlank()).limit(4).toList();
    }

    private boolean hasMasterySignal(AiChatRequest request, AiCompletion completion, String algorithm) {
        String text = safeText((request == null ? "" : request.message()) + "\n" + (completion == null ? "" : completion.content()), 4000)
                .toLowerCase(Locale.ROOT);
        boolean explanation = containsAny(text, "能通过是因为", "通过原因", "解释", "正确解释", "复盘", "掌握", "已经会", "证明", "理解了");
        if (!explanation) {
            return false;
        }
        if ("binary_search".equals(algorithm)) {
            return containsAny(text, "check", "单调", "边界", "二分答案", "最大化最小值", "最小化最大值");
        }
        return true;
    }

    private boolean profileEligibleStatus(String status) {
        return !NON_PROFILE_STATUSES.contains(status);
    }

    private boolean isFailureStatus(String status) {
        return profileEligibleStatus(status) && !"ACCEPTED".equals(status);
    }

    private String profileKey(String status, String algorithm, List<String> bugPatternTags, List<String> complexityTags) {
        String prefix = statusKey(status);
        if (!complexityTags.isEmpty()) {
            return prefix + "_complexity";
        }
        if (!algorithm.isBlank() && !"debugging".equals(algorithm)) {
            return prefix + "_" + algorithm;
        }
        if (!bugPatternTags.isEmpty() && !"debugging".equals(bugPatternTags.get(0))) {
            return prefix + "_" + bugPatternTags.get(0);
        }
        return prefix + "_debugging";
    }

    private String textForMatching(AiSubmissionContextResponse context, AiChatRequest request, AiCompletion completion) {
        StringBuilder text = new StringBuilder();
        AiProblemContextResponse problem = context.problemContext();
        if (problem != null) {
            append(text, problem.title());
            append(text, problem.statementSummary());
            if (problem.tags() != null) {
                append(text, String.join(" ", problem.tags()));
            }
            if (problem.constraints() != null) {
                append(text, String.join(" ", problem.constraints()));
            }
        }
        append(text, context.status());
        append(text, context.judgeMessage());
        append(text, request == null ? null : request.message());
        append(text, completion == null ? null : completion.content());
        return safeText(text.toString(), 5000).toLowerCase(Locale.ROOT);
    }

    private void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value).append('\n');
        }
    }

    private List<String> safeTags(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String tag = normalizeTag(item.asText(""));
                if (!tag.isBlank()) {
                    values.add(tag);
                }
            }
        } else {
            String tag = normalizeTag(node.asText(""));
            if (!tag.isBlank()) {
                values.add(tag);
            }
        }
        return distinct(values).stream().limit(MAX_TAGS).toList();
    }

    private List<String> safeTags(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::normalizeTag)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAX_TAGS)
                .toList();
    }

    private List<String> safeTexts(JsonNode node, int limit, int maxLength) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = safeText(item.asText(""), maxLength);
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        } else {
            String text = safeText(node.asText(""), maxLength);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values.stream().distinct().limit(limit).toList();
    }

    private List<String> safeTexts(List<String> values, int limit, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> safeText(value, maxLength))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private String safeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("(?i)(token|secret|password|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=***")
                .replaceAll("(?i)sk-[a-z0-9_\\-]{4,}", "sk-***")
                .replaceAll("(?i)(bearer)\\s+[a-z0-9._\\-]{8,}", "$1 ***")
                .replaceAll("(?i)\"(codeText|stdoutExcerpt|stderrExcerpt)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", "\"$1\":\"[omitted]\"")
                .replaceAll("(?i)(codeText|stdoutExcerpt|stderrExcerpt)\\s*[:=].*", "$1=[omitted]");
        StringBuilder kept = new StringBuilder();
        boolean inCodeFence = false;
        boolean inRawOutputBlock = false;
        int omittedCode = 0;
        int omittedOutput = 0;
        int omittedSecret = 0;
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
                omittedCode++;
                continue;
            }
            if (isRawOutputLabel(line)) {
                inRawOutputBlock = true;
                omittedOutput++;
                continue;
            }
            if (inRawOutputBlock) {
                if (line.isBlank() || isSummaryBoundary(line)) {
                    inRawOutputBlock = false;
                } else {
                    omittedOutput++;
                    continue;
                }
            }
            if (containsSecretLikeText(line)) {
                omittedSecret++;
                continue;
            }
            if (inCodeFence || looksLikeCodeLine(line)) {
                omittedCode++;
                continue;
            }
            if (!line.isBlank()) {
                if (!kept.isEmpty()) {
                    kept.append('\n');
                }
                kept.append(line);
            }
        }
        if (omittedCode > 0) {
            appendOmission(kept, "[submit-ready code omitted from structured analysis]");
        }
        if (omittedOutput > 0) {
            appendOmission(kept, "[raw output omitted from structured analysis]");
        }
        if (omittedSecret > 0) {
            appendOmission(kept, "[secret-like text omitted from structured analysis]");
        }
        String result = kept.toString().trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    private void appendOmission(StringBuilder builder, String marker) {
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(marker);
    }

    private String normalizeTag(String value) {
        String normalized = safeText(value, 96).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private String status(AiSubmissionContextResponse context) {
        return context == null || context.status() == null || context.status().isBlank()
                ? "UNKNOWN"
                : context.status().trim().toUpperCase(Locale.ROOT);
    }

    private String statusKey(String status) {
        return normalizeTag(status == null ? "unknown" : status);
    }

    private boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value;
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCodeLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("#include")
                || lower.startsWith("using namespace")
                || lower.startsWith("public class")
                || lower.startsWith("public static void main")
                || lower.startsWith("class solution")
                || lower.startsWith("def main")
                || lower.startsWith("import sys")
                || lower.startsWith("from sys")
                || lower.startsWith("if __name__")
                || lower.contains("scanner")
                || lower.contains("sys.stdin")
                || lower.contains("cin >>")
                || lower.contains("cout <<")
                || lower.contains("int main(")
                || lower.matches(".*[{};]\\s*$");
    }

    private boolean isRawOutputLabel(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("stdoutexcerpt:")
                || lower.startsWith("stderrexcerpt:")
                || lower.startsWith("stdout:")
                || lower.startsWith("stderr:");
    }

    private boolean isSummaryBoundary(String line) {
        return line != null && (line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ") || line.matches("^[A-Za-z][A-Za-z0-9 _-]{0,40}:.*"));
    }

    private boolean containsSecretLikeText(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("sk-")
                || lower.contains("openai_api_key")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("bearer ")
                || lower.matches(".*\\b(token|password|secret)\\b.*[:=].+");
    }

    private String extractJson(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "").trim();
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return normalized.substring(start, end + 1);
        }
        return normalized;
    }

    private Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<String> distinct(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalizeTag(value);
                if (!normalized.isBlank()) {
                    set.add(normalized);
                }
            }
        }
        return new ArrayList<>(set);
    }

    public record AnalysisResult(
            String summary,
            List<String> rootCauseTags,
            List<String> algorithmTags,
            List<String> bugPatternTags,
            List<String> complexityTags,
            List<String> nextSteps,
            List<String> evidenceItems,
            double confidence,
            String profileKey,
            String profileLabel,
            boolean profileEligible,
            boolean masteryEvidence,
            boolean modelGenerated
    ) {
        public List<String> flatTags() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            addAll(values, rootCauseTags);
            addAll(values, algorithmTags);
            addAll(values, bugPatternTags);
            addAll(values, complexityTags);
            return values.stream().limit(24).toList();
        }

        public List<String> signalTags() {
            LinkedHashSet<String> values = new LinkedHashSet<>(flatTags());
            if (profileKey != null && !profileKey.isBlank()) {
                values.add(profileKey);
            }
            return values.stream().limit(24).toList();
        }

        private static void addAll(LinkedHashSet<String> target, List<String> values) {
            if (values != null) {
                target.addAll(values);
            }
        }
    }
}
