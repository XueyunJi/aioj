package com.aioj.next.contract.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AiConversationContextDebugResponse(
        String conversationId,
        String userId,
        Map<String, Object> state,
        List<RecentMessage> recentMessages,
        List<ClarificationDebug> pendingClarifications,
        List<ClarificationAnswerDebug> answeredClarifications,
        List<SummarySegment> summarySegments,
        String contextPackPreview,
        List<ContextSection> sections,
        Map<String, Integer> sourceSummary,
        ContextBuildReport contextBuildReport,
        TokenEstimate tokenEstimate,
        List<Warning> warnings
) {
    public AiConversationContextDebugResponse(
            String conversationId,
            String userId,
            Map<String, Object> state,
            List<RecentMessage> recentMessages,
            List<ClarificationDebug> pendingClarifications,
            List<ClarificationAnswerDebug> answeredClarifications,
            List<SummarySegment> summarySegments,
            String contextPackPreview,
            TokenEstimate tokenEstimate,
            List<Warning> warnings
    ) {
        this(conversationId, userId, state, recentMessages, pendingClarifications, answeredClarifications,
                summarySegments, contextPackPreview, List.of(), Map.of(), ContextBuildReport.empty(), tokenEstimate, warnings);
    }

    public record RecentMessage(
            String id,
            String role,
            String contentPreview,
            Instant createdAt
    ) {
    }

    public record ClarificationDebug(
            String id,
            String requestKey,
            String question,
            String priority,
            String status,
            Map<String, Object> inputSchema,
            Instant createdAt,
            Instant answeredAt
    ) {
    }

    public record ClarificationAnswerDebug(
            String id,
            String requestId,
            String requestKey,
            String question,
            String answerPreview,
            Map<String, Object> interpretedDelta,
            boolean mergedToState,
            Instant createdAt
    ) {
    }

    public record SummarySegment(
            String id,
            String summaryType,
            String fromMessageId,
            String toMessageId,
            String narrativeSummary,
            Map<String, Object> structuredSummary,
            double salienceScore,
            int tokenEstimate,
            String status,
            Instant createdAt
    ) {
    }

    public record TokenEstimate(
            int recentMessages,
            int state,
            int summaries,
            int longTermMemories,
            int total
    ) {
    }

    public record ContextSection(
            String id,
            String type,
            String title,
            int priority,
            String source,
            String sensitivity,
            int estimatedTokens,
            boolean required,
            String contentPreview,
            Map<String, Object> metadata
    ) {
    }

    public record ContextBuildReport(
            List<ContextSection> sections,
            Map<String, Integer> sourceSummary,
            int totalEstimatedTokens,
            int requiredEstimatedTokens,
            int optionalEstimatedTokens,
            int requiredSectionCount,
            int optionalSectionCount,
            ContextBudgetReport budget
    ) {
        public ContextBuildReport(
                List<ContextSection> sections,
                Map<String, Integer> sourceSummary,
                int totalEstimatedTokens,
                int requiredEstimatedTokens,
                int optionalEstimatedTokens,
                int requiredSectionCount,
                int optionalSectionCount
        ) {
            this(sections, sourceSummary, totalEstimatedTokens, requiredEstimatedTokens, optionalEstimatedTokens,
                    requiredSectionCount, optionalSectionCount, ContextBudgetReport.empty());
        }

        public static ContextBuildReport empty() {
            return new ContextBuildReport(List.of(), Map.of(), 0, 0, 0, 0, 0, ContextBudgetReport.empty());
        }
    }

    public record ContextBudgetReport(
            String model,
            int modelWindowTokens,
            int compressionThresholdTokens,
            int maxPromptBudgetTokens,
            int estimatedPromptTokensBefore,
            int estimatedPromptTokensAfter,
            boolean compressionApplied,
            List<String> trimmedSections,
            List<String> droppedSections,
            Map<String, Integer> estimatedBySection,
            List<String> warnings
    ) {
        public static ContextBudgetReport empty() {
            return new ContextBudgetReport("", 0, 0, 0, 0, 0, false, List.of(), List.of(), Map.of(), List.of());
        }
    }

    public record Warning(
            String level,
            String code,
            String message
    ) {
    }
}
