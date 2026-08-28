package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.contract.contest.ContestAiPolicyResponse;

import java.util.Map;

public record AiChatContext(
        String userMemory,
        String conversationSummary,
        String currentProblems,
        String retrievedHistory,
        String conversationContextPack,
        Map<String, Object> submissionContextSummary,
        AiContextBuildReport contextBuildReport,
        ContestAiPolicyResponse contestPolicy,
        /** Rendered [Contest Policy] block of a CONSTRAIN turn; empty when the turn is not constrained. */
        String contestPolicyBlock
) {
    public AiChatContext(String userMemory, String conversationSummary, String currentProblems, String retrievedHistory, String conversationContextPack, Map<String, Object> submissionContextSummary, AiContextBuildReport contextBuildReport, ContestAiPolicyResponse contestPolicy) {
        this(userMemory, conversationSummary, currentProblems, retrievedHistory, conversationContextPack, submissionContextSummary, contextBuildReport, contestPolicy, "");
    }

    public AiChatContext(String userMemory, String conversationSummary, String currentProblems, String retrievedHistory, String conversationContextPack, Map<String, Object> submissionContextSummary, AiContextBuildReport contextBuildReport) {
        this(userMemory, conversationSummary, currentProblems, retrievedHistory, conversationContextPack, submissionContextSummary, contextBuildReport, ContestAiPolicyResponse.inactive());
    }

    public AiChatContext(String userMemory, String conversationSummary, String currentProblems, String retrievedHistory, String conversationContextPack, Map<String, Object> submissionContextSummary) {
        this(userMemory, conversationSummary, currentProblems, retrievedHistory, conversationContextPack, submissionContextSummary, AiContextBuildReport.empty(), ContestAiPolicyResponse.inactive());
    }

    public AiChatContext(String userMemory, String conversationSummary, String currentProblems, String retrievedHistory, String conversationContextPack) {
        this(userMemory, conversationSummary, currentProblems, retrievedHistory, conversationContextPack, Map.of(), AiContextBuildReport.empty(), ContestAiPolicyResponse.inactive());
    }

    public AiChatContext(String userMemory, String conversationSummary, String currentProblems, String retrievedHistory) {
        this(userMemory, conversationSummary, currentProblems, retrievedHistory, "", Map.of(), AiContextBuildReport.empty(), ContestAiPolicyResponse.inactive());
    }

    public static AiChatContext empty() {
        return new AiChatContext("", "", "", "", "", Map.of(), AiContextBuildReport.empty(), ContestAiPolicyResponse.inactive());
    }

    public AiChatContext withContestPolicyBlock(String block) {
        return new AiChatContext(userMemory, conversationSummary, currentProblems, retrievedHistory, conversationContextPack,
                submissionContextSummary, contextBuildReport, contestPolicy, block == null ? "" : block);
    }

    public boolean hasContent() {
        return hasText(userMemory)
                || hasText(conversationSummary)
                || hasText(currentProblems)
                || hasText(retrievedHistory)
                || hasText(conversationContextPack)
                || (submissionContextSummary != null && !submissionContextSummary.isEmpty())
                || (contextBuildReport != null && contextBuildReport.hasSections());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
