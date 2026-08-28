package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.persistence.entity.AiConversationSummaryEntity;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ConversationContextPackBuilder {
    private final ConversationStateMerger stateMerger;
    private final AiSelectedContextService selectedContextService;
    private final AiTeachingStrategyRouter teachingStrategyRouter;
    private final ObjectMapper objectMapper;

    public ConversationContextPackBuilder(ConversationStateMerger stateMerger, ObjectMapper objectMapper) {
        this(stateMerger, new AiSelectedContextService(), new AiTeachingStrategyRouter(), objectMapper);
    }

    @Autowired
    public ConversationContextPackBuilder(
            ConversationStateMerger stateMerger,
            AiSelectedContextService selectedContextService,
            AiTeachingStrategyRouter teachingStrategyRouter,
            ObjectMapper objectMapper
    ) {
        this.stateMerger = stateMerger;
        this.selectedContextService = selectedContextService;
        this.teachingStrategyRouter = teachingStrategyRouter;
        this.objectMapper = objectMapper;
    }

    public String build(
            AiChatRequest request,
            String stateJson,
            List<AiMessageEntity> recentMessages,
            List<AiConversationSummaryEntity> summaries,
            String longTermMemories,
            String retrievedHistory
    ) {
        return build(request, stateJson, recentMessages, summaries, longTermMemories, retrievedHistory, "");
    }

    /**
     * @param resolvedReferenceBlock W1.7 pre-rendered [Resolved Reference] block(s); kept as a
     *        plain block next to [Current User Message] until wave 2 turns sections into
     *        first-class ContextSection objects.
     */
    public String build(
            AiChatRequest request,
            String stateJson,
            List<AiMessageEntity> recentMessages,
            List<AiConversationSummaryEntity> summaries,
            String longTermMemories,
            String retrievedHistory,
            String resolvedReferenceBlock
    ) {
        StringBuilder pack = new StringBuilder();
        appendSection(pack, "Current User Message", request.message(), 2000);
        appendClarificationAnswer(pack, request.clarificationAnswer());
        appendSubmissionFocus(pack, request.submissionContext());
        appendSelectedContext(pack, request);
        if (resolvedReferenceBlock != null && !resolvedReferenceBlock.isBlank()) {
            pack.append(resolvedReferenceBlock);
            if (!resolvedReferenceBlock.endsWith("\n\n")) {
                pack.append(resolvedReferenceBlock.endsWith("\n") ? "\n" : "\n\n");
            }
        }
        appendState(pack, stateJson);
        appendRecentTurns(pack, recentMessages);
        appendSummaries(pack, summaries);
        appendSection(pack, "Relevant Long-Term Memories", longTermMemories, 2600);
        appendSection(pack, "Retrieved Past Context", retrievedHistory, 2400);
        appendTeachingStrategy(pack, request, stateJson, longTermMemories);
        return pack.toString().trim();
    }

    private void appendClarificationAnswer(StringBuilder pack, AiChatRequest.ClarificationAnswer answer) {
        if (answer == null) {
            return;
        }
        pack.append("[Clarification Answer Just Submitted]\n")
                .append("- Previous question: ").append(truncate(answer.question(), 600)).append('\n')
                .append("- User answer: ").append(truncate(firstNonBlank(answer.answerText(), answer.customText()), 800)).append('\n')
                .append("- Selected options: ").append(answer.selectedOptionIds() == null ? "[]" : answer.selectedOptionIds()).append('\n')
                .append("- 中文说明：用户是在回答你之前的问题，不是在提出一个全新问题。\n")
                .append("- How to use it: Treat this as the user's answer to the previous clarification. ")
                .append("First evaluate whether it is correct, partially correct, incorrect, or unclear; then continue. ")
                .append("Do not repeat the same question.\n\n");
    }

    private void appendSubmissionFocus(StringBuilder pack, AiChatRequest.SubmissionContext submission) {
        if (submission == null || submission.submissionId() == null) {
            return;
        }
        pack.append("[Submission Focus]\n")
                .append("- submissionId: ").append(submission.submissionId()).append('\n')
                .append("- intent: ").append(submission.intent() == null ? "DEBUG" : submission.intent()).append('\n')
                .append("- userSelected: ").append(Boolean.TRUE.equals(submission.userSelected())).append('\n');
        if (submission.note() != null && !submission.note().isBlank()) {
            pack.append("- userNote: ").append(truncate(submission.note(), 500)).append('\n');
        }
        pack.append("- How to use it: analyze the selected submission facts from server-resolved context. ")
                .append("Do not ask for full code if CURRENT_SUBMISSION_CODE is present. ")
                .append("If code is redacted because of contest policy, give debugging direction without complete implementation.\n\n");
    }

    private void appendSelectedContext(StringBuilder pack, AiChatRequest request) {
        String block = selectedContextService.contextPackBlock(request);
        if (!block.isBlank()) {
            pack.append(block);
        }
    }

    private void appendState(StringBuilder pack, String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            return;
        }
        Map<String, Object> state = stateMerger.readState(stateJson);
        pack.append("[Current Conversation State]\n");
        appendJsonField(pack, "Problem", state.get("problem"));
        Map<String, Object> flow = map(state.get("learningFlow"));
        appendJsonField(pack, "Learning flow", flow);
        appendJsonField(pack, "用户仍卡住", flow.get("userStuckPoints"));
        appendJsonField(pack, "Algorithm progress", state.get("algorithmState"));
        appendJsonField(pack, "Code state", state.get("codeState"));
        appendJsonField(pack, "Clarifications", state.get("clarifications"));
        appendJsonField(pack, "Previous inactive problems", state.get("previousProblems"));
        pack.append('\n');
    }

    private void appendRecentTurns(StringBuilder pack, List<AiMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        pack.append("[Recent Raw Turns]\n");
        for (AiMessageEntity message : messages) {
            pack.append("- ")
                    .append(message.getRole())
                    .append(": ")
                    .append(truncate(message.getContent(), 420))
                    .append('\n');
        }
        pack.append('\n');
    }

    private void appendSummaries(StringBuilder pack, List<AiConversationSummaryEntity> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        pack.append("[Relevant Compressed Session Summaries]\n");
        for (AiConversationSummaryEntity summary : summaries) {
            pack.append("- ")
                    .append(summary.summaryType)
                    .append(" messages ")
                    .append(summary.messageStartId)
                    .append("..")
                    .append(summary.messageEndId)
                    .append(": ")
                    .append(truncate(summary.narrativeSummary, 520))
                    .append('\n');
        }
        pack.append('\n');
    }

    private void appendTeachingStrategy(StringBuilder pack, AiChatRequest request, String stateJson, String longTermMemories) {
        Map<String, Object> state = stateMerger.readState(stateJson);
        AiTeachingStrategyRouter.StrategyDecision strategy = teachingStrategyRouter.route(request, state);
        String stateText = state.toString();
        String memoryText = longTermMemories == null ? "" : longTermMemories;
        pack.append("[Teaching Strategy]\n")
                .append("- Routed strategy: ").append(strategy.strategy()).append(" (").append(strategy.reason()).append(")\n")
                .append(teachingStrategyRouter.policyBlock(strategy))
                .append("- Always use the current conversation state before giving generic advice.\n")
                .append("- Explicit user instructions override the default Socratic style.\n")
                .append("- If doNotRepeatQuestions contains a question, do not ask it again.\n")
                .append("- Assistant suggestions are unverified until the user confirms them or problem facts support them.\n");
        Map<String, Object> codeState = map(state.get("codeState"));
        if (Boolean.TRUE.equals(codeState.get("latestAssistantProvidedCode"))) {
            pack.append("- Important responsibility context: the assistant previously provided code in this conversation. ")
                    .append("If the user now reports WA/TLE/RE/CE or selects a failed submission, explicitly acknowledge that the previous assistant-provided code may be wrong and debug that version.\n");
        }
        if (request.submissionContext() != null) {
            pack.append("- Selected-submission rule: answer around the selected submission status, judge details, and available code; do not give generic advice before addressing the concrete failure.\n");
        }
        if (containsAny(stateText, "explain_greedy_check_d", "二分候选距离")) {
            pack.append("- For the current step, explain check(d): sort, scan left to right, pick the leftmost feasible next position, and why not choosing the farthest is important.\n");
        }
        if (containsAny(memoryText, "不要直接给完整答案", "先给提示", "hint")) {
            pack.append("- Long-term preference: give hints first and avoid full code unless the user explicitly asks.\n");
        }
        if (containsAny(memoryText, "二分", "边界", "单调")) {
            pack.append("- Weakness focus: emphasize binary-search boundaries, monotonicity, and feasibility-check reasoning.\n");
        }
        pack.append('\n');
    }

    private void appendJsonField(StringBuilder pack, String label, Object value) {
        try {
            pack.append("- ").append(label).append(": ").append(objectMapper.writeValueAsString(value)).append('\n');
        } catch (Exception ignored) {
            pack.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> existing) {
            return (Map<String, Object>) existing;
        }
        return Map.of();
    }

    private void appendSection(StringBuilder pack, String title, String content, int max) {
        if (content == null || content.isBlank()) {
            return;
        }
        pack.append('[').append(title).append("]\n")
                .append(truncate(content, max))
                .append("\n\n");
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
