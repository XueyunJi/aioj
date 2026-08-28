package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.TestCaseDto;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record AiChatRequest(
        String conversationId,
        Long problemId,
        @NotBlank String message,
        String mode,
        ProblemContext problemContext,
        CodeContext codeContext,
        ClarificationAnswer clarificationAnswer,
        String clientMessageId,
        SelectionContext selectionContext,
        ContestContext contestContext,
        SubmissionContext submissionContext
    ) {
    public AiChatRequest(
            String conversationId,
            Long problemId,
            String message,
            String mode,
            ProblemContext problemContext,
            CodeContext codeContext,
            ClarificationAnswer clarificationAnswer,
            String clientMessageId,
            SelectionContext selectionContext
    ) {
        this(conversationId, problemId, message, mode, problemContext, codeContext, clarificationAnswer,
                clientMessageId, selectionContext, null, null);
    }

    public AiChatRequest(
            String conversationId,
            Long problemId,
            String message,
            String mode,
            ProblemContext problemContext,
            CodeContext codeContext,
            ClarificationAnswer clarificationAnswer
    ) {
        this(conversationId, problemId, message, mode, problemContext, codeContext, clarificationAnswer,
                null, null, null, null);
    }

    public record ProblemContext(
            String id,
            String title,
            String difficulty,
            String statement,
            String notes,
            List<String> tags,
            List<TestCaseDto> samples,
            Integer timeLimitMillis,
            Integer memoryLimitKb
    ) {
    }

    public record CodeContext(String language, String code) {
    }

    public record ContestContext(
            Long contestId,
            Long contestRunId,
            Long contestProblemId
    ) {
    }

    public record SubmissionContext(
            Long submissionId,
            String intent,
            Boolean userSelected,
            String note
    ) {
    }

    public record ClarificationAnswer(
            String requestId,
            String question,
            String answerText,
            List<String> selectedOptionIds,
            String customText
    ) {
    }

    public record SelectionContext(
            String selectionId,
            String conversationId,
            String sourceType,
            String sourceMessageId,
            String sourceRole,
            String selectedText,
            String selectedMarkdown,
            SelectionRange selectionRange,
            SurroundingContext surroundingContext,
            SelectedCodeContext codeContext,
            SelectedProblemContext problemContext,
            String uiIntent
    ) {
    }

    public record SelectionRange(
            Integer startOffset,
            Integer endOffset,
            Integer startLine,
            Integer endLine
    ) {
    }

    public record SurroundingContext(
            String before,
            String after,
            String sectionTitle,
            String messagePreview
    ) {
    }

    public record SelectedCodeContext(
            String language,
            String functionName,
            String enclosingSymbol,
            String latestCodeMessageId,
            String codeHash,
            Boolean hasCompileRisk
    ) {
    }

    public record SelectedProblemContext(
            String problemId,
            String title,
            List<String> tags,
            List<String> constraints
    ) {
    }
}
