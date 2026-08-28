package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.StudentPostmortemAnalysisResponse;
import com.aioj.next.contract.ai.StudentPostmortemPracticeSuggestion;
import com.aioj.next.contract.ai.StudentPostmortemWeaknessSuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class StudentPostmortemAnalysisService {
    private static final int MAX_STATISTICS_CHARS = 16000;
    private static final int MAX_SUMMARY_CHARS = 5000;

    private final AiProvider aiProvider;
    private final AiQuotaService aiQuotaService;
    private final AiCapacityService aiCapacityService;
    private final ObjectMapper objectMapper;

    public StudentPostmortemAnalysisService(AiProvider aiProvider, AiQuotaService aiQuotaService,
                                            AiCapacityService aiCapacityService, ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.aiQuotaService = aiQuotaService;
        this.aiCapacityService = aiCapacityService;
        this.objectMapper = objectMapper;
    }

    public StudentPostmortemAnalysisResponse analyze(StudentPostmortemAnalysisRequest request) {
        Long actorUserId = request == null || request.actorUserId() == null ? 0L : request.actorUserId();
        List<StudentPostmortemWeaknessSuggestion> candidates = deterministicWeaknessCandidates(request);
        List<StudentPostmortemPracticeSuggestion> practice = practiceSuggestions(candidates);
        try {
            AiCompletion completion = aiCapacityService.call(
                    AiCapacityService.AiWorkload.BATCH_REPORT,
                    () -> aiProvider.chat(new AiChatRequest(
                            null,
                            null,
                            buildUserPrompt(request),
                            "student_postmortem_analysis",
                            null,
                            null,
                            null,
                            null,
                            null
                    ), new AiChatContext("", "", "", "", buildContextPack(request)), AiModelScope.REPORT_ANALYSIS)
            );
            aiQuotaService.record(actorUserId, completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true);
            return new StudentPostmortemAnalysisResponse(completion.content(), candidates, practice,
                    completion.provider(), completion.model(), completion.promptTokens(), completion.completionTokens(),
                    true, null);
        } catch (RuntimeException ex) {
            aiQuotaService.record(actorUserId, aiProvider.providerName(), aiProvider.model(), 0, 0, false);
            return new StudentPostmortemAnalysisResponse(null, candidates, practice, aiProvider.providerName(),
                    aiProvider.model(), 0, 0, false, summarize(ex.getMessage(), 300));
        }
    }

    private String buildContextPack(StudentPostmortemAnalysisRequest request) {
        String modeRules = isAcm(request) ? """
                ACM mode rules:
                - Do not mention total score, testcase score, case score, or partial score.
                - Review accepted/unsolved status, attempts, error patterns, tags, and representative code excerpts.
                - If code is provided, use it only for this report and do not turn source code into memory evidence.
                """ : """
                IOI mode rules:
                - Use total score, problem score, testcase score, and score improvement opportunities.
                - Do not request or infer full source code.
                """;
        return """
                [Student Contest Postmortem Context]
                You are writing a personalized post-contest learning review for one student.
                Use only the deterministic context supplied by the problem service.
                Do not ask for stdout, stderr, hidden testcase data, private scoreboard data, or plagiarism details.
                Weaknesses are candidates only. The student must confirm before anything becomes long-term memory.
                %s
                Return Chinese Markdown with exactly these sections:
                1. 个人成绩概览
                2. 我的提交轨迹
                3. 题目复盘
                4. 薄弱点候选
                5. 个性化练习建议
                6. 下一步计划

                %s
                """.formatted(modeRules, structuredContext(request));
    }

    private String buildUserPrompt(StudentPostmortemAnalysisRequest request) {
        String instruction = isAcm(request)
                ? "这是 ACM 赛制个人复盘。不要使用总分、测试点分或 case 分作为评价依据；请结合提交状态、提交轨迹和代表性代码片段给出具体复盘。"
                : "这是 IOI 赛制个人复盘。请结合总分、每题分数、测试点得分和提分路径给出具体复盘。";
        return """
                请基于以下个人比赛聚合统计，生成学生可读的中文 Markdown 复盘。
                语气要具体、鼓励，但不要夸大，不要把候选薄弱点写成已经确认的长期画像。
                %s

                %s
                """.formatted(instruction, structuredContext(request));
    }

    private String structuredContext(StudentPostmortemAnalysisRequest request) {
        if (request == null) {
            return "- Empty request";
        }
        return """
                - Student user id: %s
                - Contest: %s (#%s)
                - Run: %s (#%s)
                - Participant: #%s
                - Mode: %s

                [Deterministic Summary]
                %s

                [Statistics JSON]
                ```json
                %s
                ```

                %s
                """.formatted(
                request.targetUserId(),
                safe(request.contestTitle()), request.contestId(),
                safe(request.runTitle()), request.contestRunId(),
                request.contestParticipantId(),
                safe(request.mode()),
                summarize(request.summaryText(), MAX_SUMMARY_CHARS),
                summarize(request.statisticsJson(), MAX_STATISTICS_CHARS),
                codeReferenceSection(request)
        );
    }

    private List<StudentPostmortemWeaknessSuggestion> deterministicWeaknessCandidates(StudentPostmortemAnalysisRequest request) {
        if (request == null || !StringUtils.hasText(request.statisticsJson())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(request.statisticsJson());
            List<StudentPostmortemWeaknessSuggestion> suggestions = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            JsonNode seeds = root.path("weaknessSeeds");
            if (seeds.isArray()) {
                for (JsonNode seed : seeds) {
                    addSeedSuggestion(suggestions, seen, seed.asText(), 0.76);
                }
            }
            JsonNode problems = root.path("problems");
            if (problems.isArray()) {
                for (JsonNode problem : problems) {
                    String label = problem.path("label").asText("");
                    String title = problem.path("title").asText("");
                    String status = problem.path("bestStatus").asText("");
                    if (isAcm(request)) {
                        if (!"ACCEPTED".equals(status)) {
                            List<String> tags = readTags(problem.path("tags"));
                            int attempts = problem.path("submissionCount").asInt(0);
                            addSuggestion(suggestions, seen,
                                    "%s %s：最佳状态 %s，提交 %d 次".formatted(label, title, status, attempts),
                                    tags,
                                    attempts >= 3 ? 0.80 : 0.72);
                        }
                        continue;
                    }
                    double best = problem.path("bestScore").asDouble(0);
                    double max = problem.path("maxScore").asDouble(0);
                    if (!"ACCEPTED".equals(status) || (max > 0 && best / max < 0.7)) {
                        List<String> tags = readTags(problem.path("tags"));
                        addSuggestion(suggestions, seen,
                                "%s %s：最佳状态 %s，得分 %.2f/%.2f".formatted(label, title, status, best, max),
                                tags,
                                max > 0 && best / max < 0.4 ? 0.82 : 0.72);
                    }
                }
            }
            return suggestions.stream().limit(8).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void addSuggestion(List<StudentPostmortemWeaknessSuggestion> suggestions, Set<String> seen,
                               String evidence, List<String> tags, double confidence) {
        String normalized = summarize(evidence, 220);
        if (!StringUtils.hasText(normalized) || !seen.add(normalized)) {
            return;
        }
        String knowledgeNode = tags == null || tags.isEmpty() ? "赛后复盘薄弱点" : tags.get(0);
        suggestions.add(new StudentPostmortemWeaknessSuggestion(
                knowledgeNode,
                normalized,
                tags == null ? List.of() : tags,
                List.of(normalized),
                confidence
        ));
    }

    private void addSeedSuggestion(List<StudentPostmortemWeaknessSuggestion> suggestions, Set<String> seen,
                                   String evidence, double confidence) {
        String normalized = summarize(evidence, 220);
        if (!StringUtils.hasText(normalized) || !seen.add(normalized)) {
            return;
        }
        String knowledgeNode = "赛后复盘薄弱点";
        List<String> tags = List.of();
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            separator = normalized.indexOf('：');
        }
        if (separator > 0) {
            String candidate = normalized.substring(0, separator).trim();
            if (StringUtils.hasText(candidate) && candidate.length() <= 80) {
                knowledgeNode = candidate;
                tags = List.of(candidate);
            }
        }
        suggestions.add(new StudentPostmortemWeaknessSuggestion(
                knowledgeNode,
                normalized,
                tags,
                List.of(normalized),
                confidence
        ));
    }

    private List<StudentPostmortemPracticeSuggestion> practiceSuggestions(List<StudentPostmortemWeaknessSuggestion> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of(new StudentPostmortemPracticeSuggestion(
                    "复盘错题并重做一次",
                    "选择本场比赛中最不稳定的一题，先不看旧代码，重新写一遍核心思路和边界条件。",
                    List.of("review")
            ));
        }
        return candidates.stream()
                .limit(5)
                .map(candidate -> new StudentPostmortemPracticeSuggestion(
                        "针对 %s 做 2-3 道同类题".formatted(safe(candidate.knowledgeNode())),
                        "先写出判定条件、边界和复杂度，再开始编码；完成后对照本场证据复盘是否仍犯同类错误。",
                        candidate.tags() == null ? List.of() : candidate.tags()
                ))
                .toList();
    }

    private List<String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : tagsNode) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                tags.add(value);
            }
        }
        return tags.stream().distinct().limit(8).toList();
    }

    private boolean isAcm(StudentPostmortemAnalysisRequest request) {
        return request != null && "ACM".equalsIgnoreCase(request.mode());
    }

    private String codeReferenceSection(StudentPostmortemAnalysisRequest request) {
        if (!isAcm(request) || request.representativeCodeReferences() == null || request.representativeCodeReferences().isEmpty()) {
            return "";
        }
        try {
            return """
                    [Representative ACM Code References]
                    These are selected per problem: last accepted submission if solved, otherwise last submission.
                    Use excerpts only for this report. Do not store code as memory.
                    ```json
                    %s
                    ```
                    """.formatted(summarize(objectMapper.writeValueAsString(request.representativeCodeReferences()), 12000));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String summarize(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "\n...";
    }
}
