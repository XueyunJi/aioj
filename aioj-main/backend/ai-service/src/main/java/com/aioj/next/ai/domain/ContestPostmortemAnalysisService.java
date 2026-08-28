package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisRequest;
import com.aioj.next.contract.ai.ContestPostmortemAnalysisResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ContestPostmortemAnalysisService {
    private static final int MAX_STATISTICS_CHARS = 18000;
    private static final int MAX_SUMMARY_CHARS = 6000;

    private final AiProvider aiProvider;
    private final AiQuotaService aiQuotaService;
    private final AiCapacityService aiCapacityService;

    public ContestPostmortemAnalysisService(AiProvider aiProvider, AiQuotaService aiQuotaService,
                                            AiCapacityService aiCapacityService) {
        this.aiProvider = aiProvider;
        this.aiQuotaService = aiQuotaService;
        this.aiCapacityService = aiCapacityService;
    }

    public ContestPostmortemAnalysisResponse analyze(ContestPostmortemAnalysisRequest request) {
        Long actorUserId = request == null || request.actorUserId() == null ? 0L : request.actorUserId();
        try {
            AiCompletion completion = aiCapacityService.call(
                    AiCapacityService.AiWorkload.BATCH_REPORT,
                    () -> aiProvider.chat(new AiChatRequest(
                            null,
                            null,
                            buildUserPrompt(request),
                            "contest_postmortem_analysis",
                            null,
                            null,
                            null,
                            null,
                            null
                    ), new AiChatContext("", "", "", "", buildContextPack(request)), AiModelScope.REPORT_ANALYSIS)
            );
            aiQuotaService.record(actorUserId, completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true);
            return new ContestPostmortemAnalysisResponse(completion.content(), completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true, null);
        } catch (RuntimeException ex) {
            aiQuotaService.record(actorUserId, aiProvider.providerName(), aiProvider.model(), 0, 0, false);
            return new ContestPostmortemAnalysisResponse(null, aiProvider.providerName(), aiProvider.model(),
                    0, 0, false, summarize(ex.getMessage(), 300));
        }
    }

    private String buildContextPack(ContestPostmortemAnalysisRequest request) {
        return """
                [Contest Postmortem Teaching Report Context]
                You are helping teachers prepare a post-contest classroom review report.
                Use only the deterministic aggregate statistics supplied by the problem service.
                Do not ask for or infer full source code, stdout, stderr, hidden testcase data, or private judge details.
                Plagiarism information is a risk overview only; never conclude cheating as a fact.
                Distinguish factual aggregate statistics from teaching suggestions.
                Return Chinese Markdown with exactly these sections:
                1. 赛况总览
                2. 题目复盘顺序
                3. 高频错误
                4. 薄弱知识点
                5. 课堂讲解建议
                6. 课后练习建议
                7. 需要人工关注

                %s
                """.formatted(structuredContext(request));
    }

    private String buildUserPrompt(ContestPostmortemAnalysisRequest request) {
        return """
                请基于以下 run 级比赛聚合统计，生成教师可用于课堂复盘的 Markdown 报告。
                只做教学分析和表达整理，不要定性作弊，不要引用未提供的源码或隐藏测试数据。

                %s
                """.formatted(structuredContext(request));
    }

    private String structuredContext(ContestPostmortemAnalysisRequest request) {
        if (request == null) {
            return "- Empty request";
        }
        return """
                - Contest: %s (#%s)
                - Run: %s (#%s)
                - Mode: %s

                [Deterministic Summary]
                %s

                [Statistics JSON]
                ```json
                %s
                ```
                """.formatted(
                safe(request.contestTitle()), request.contestId(),
                safe(request.runTitle()), request.contestRunId(),
                safe(request.mode()),
                summarize(request.summaryText(), MAX_SUMMARY_CHARS),
                summarize(request.statisticsJson(), MAX_STATISTICS_CHARS)
        );
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
