package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.PlagiarismAnalysisRequest;
import com.aioj.next.contract.ai.PlagiarismAnalysisResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class PlagiarismAnalysisService {
    private static final int MAX_FRAGMENT_CHARS = 900;

    private final AiProvider aiProvider;
    private final AiQuotaService aiQuotaService;
    private final AiCapacityService aiCapacityService;

    public PlagiarismAnalysisService(AiProvider aiProvider, AiQuotaService aiQuotaService,
                                     AiCapacityService aiCapacityService) {
        this.aiProvider = aiProvider;
        this.aiQuotaService = aiQuotaService;
        this.aiCapacityService = aiCapacityService;
    }

    public PlagiarismAnalysisResponse analyze(PlagiarismAnalysisRequest request) {
        Long actorUserId = request == null || request.actorUserId() == null ? 0L : request.actorUserId();
        try {
            AiCompletion completion = aiCapacityService.call(
                    AiCapacityService.AiWorkload.BATCH_REPORT,
                    () -> aiProvider.chat(new AiChatRequest(
                            null,
                            null,
                            buildUserPrompt(request),
                            "plagiarism_analysis",
                            null,
                            null,
                            null,
                            null,
                            null
                    ), new AiChatContext("", "", "", "", buildContextPack(request)), AiModelScope.REPORT_ANALYSIS)
            );
            aiQuotaService.record(actorUserId, completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true);
            return new PlagiarismAnalysisResponse(completion.content(), completion.provider(), completion.model(),
                    completion.promptTokens(), completion.completionTokens(), true, null);
        } catch (RuntimeException ex) {
            aiQuotaService.record(actorUserId, aiProvider.providerName(), aiProvider.model(), 0, 0, false);
            return new PlagiarismAnalysisResponse(null, aiProvider.providerName(), aiProvider.model(),
                    0, 0, false, summarize(ex.getMessage(), 300));
        }
    }

    private String buildContextPack(PlagiarismAnalysisRequest request) {
        return """
                [Plagiarism Analysis Context]
                You are helping teachers review code similarity evidence in an online judge contest.
                Treat the deterministic similarity and fragments as evidence, but never conclude cheating as a fact.
                Separate deterministic evidence from AI interpretation.
                Do not ask for full source code. Do not expose private data beyond the provided participant snapshots.
                Return a concise Chinese markdown analysis with these sections:
                1. 风险概览
                2. 相似证据
                3. 可能的正常原因
                4. 建议人工复核步骤
                5. 结论边界

                %s
                """.formatted(structuredContext(request));
    }

    private String buildUserPrompt(PlagiarismAnalysisRequest request) {
        return """
                请基于以下比赛查重片段证据，生成教师可读的风险解释。注意：这只是风险线索，不要定性作弊。

                %s
                """.formatted(structuredContext(request));
    }

    private String structuredContext(PlagiarismAnalysisRequest request) {
        if (request == null) {
            return "- Empty request";
        }
        return """
                - Contest: %s (#%s)
                - Job ID: %s
                - Pair ID: %s
                - Problem: %s %s
                - Language: %s
                - Risk level: %s
                - Similarity: %.4f
                - Left: %s
                - Right: %s
                - Similar fragments:
                %s
                """.formatted(
                safe(request.contestTitle()), request.contestId(),
                request.jobId(), request.pairId(),
                safe(request.problemLabel()), safe(request.problemTitle()),
                safe(request.language()), safe(request.riskLevel()).toUpperCase(Locale.ROOT),
                request.similarity(),
                participant(request.left()),
                participant(request.right()),
                fragments(request.fragments())
        );
    }

    private String participant(PlagiarismAnalysisRequest.Participant participant) {
        if (participant == null) {
            return "unknown";
        }
        return "%s (%s), userId=%s, participantId=%s, submissionId=%s".formatted(
                safe(participant.displayNameSnapshot()),
                safe(participant.accountSnapshot()),
                participant.userId(),
                participant.participantId(),
                participant.submissionId()
        );
    }

    private String fragments(List<PlagiarismAnalysisRequest.Fragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "  - No fragment excerpt was captured.";
        }
        return fragments.stream()
                .limit(5)
                .map(fragment -> """
                          - #%d, matched tokens: %d
                            Left excerpt:
                            ```text
                            %s
                            ```
                            Right excerpt:
                            ```text
                            %s
                            ```
                        """.formatted(
                        fragment.sequenceNo(),
                        fragment.tokenLength(),
                        summarize(fragment.leftExcerpt(), MAX_FRAGMENT_CHARS),
                        summarize(fragment.rightExcerpt(), MAX_FRAGMENT_CHARS)
                ))
                .collect(Collectors.joining("\n"));
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
