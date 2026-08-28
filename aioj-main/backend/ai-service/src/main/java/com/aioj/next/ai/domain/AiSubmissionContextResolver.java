package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.AiSubmissionCaseContext;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiSubmissionContextResolver {
    private final ProblemServiceClient problemServiceClient;
    private final AiProblemContextResolver problemContextResolver;

    public AiSubmissionContextResolver(ProblemServiceClient problemServiceClient,
                                       AiProblemContextResolver problemContextResolver) {
        this.problemServiceClient = problemServiceClient;
        this.problemContextResolver = problemContextResolver;
    }

    public AiSubmissionContextResponse resolve(Long userId, AiChatRequest request) {
        AiChatRequest.SubmissionContext submission = request == null ? null : request.submissionContext();
        if (submission == null || submission.submissionId() == null) {
            return null;
        }
        AiChatRequest.ContestContext contest = request.contestContext();
        return problemServiceClient.aiSubmissionContext(new AiSubmissionContextRequest(
                userId,
                submission.submissionId(),
                request.problemId(),
                contest == null ? null : contest.contestId(),
                contest == null ? null : contest.contestRunId(),
                contest == null ? null : contest.contestProblemId(),
                firstNonBlank(submission.intent(), "AI_STUDENT_DEBUG")
        ));
    }

    public Map<String, Object> safeSummary(AiSubmissionContextResponse context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (context == null) {
            return payload;
        }
        putIfPresent(payload, "submissionId", context.submissionId() == null ? null : String.valueOf(context.submissionId()));
        putIfPresent(payload, "problemId", context.problemId() == null ? null : String.valueOf(context.problemId()));
        putIfPresent(payload, "scope", context.scope());
        payload.put("contestActive", context.contestActive());
        putIfPresent(payload, "language", context.language());
        putIfPresent(payload, "status", context.status());
        putIfPresent(payload, "judgeMessage", truncate(context.judgeMessage(), 500));
        putIfPresent(payload, "exitStatus", context.exitStatus());
        putIfPresent(payload, "runTimeMillis", context.runTimeMillis());
        putIfPresent(payload, "memoryKb", context.memoryKb());
        putIfPresent(payload, "score", context.score());
        putIfPresent(payload, "maxScore", context.maxScore());
        payload.put("codeAllowedToModel", context.codeAllowedToModel());
        putIfPresent(payload, "codeHash", context.codeHash());
        putIfPresent(payload, "policyMessage", truncate(context.policyMessage(), 300));
        if (context.caseResults() != null && !context.caseResults().isEmpty()) {
            payload.put("caseResults", context.caseResults().stream()
                    .limit(8)
                    .map(this::safeCaseSummary)
                    .toList());
        }
        putIfPresent(payload, "submittedAt", context.submittedAt());
        putIfPresent(payload, "judgedAt", context.judgedAt());
        payload.put("source", "resolved.submissionContext");
        return payload;
    }

    public String contextBlock(AiSubmissionContextResponse context) {
        if (context == null) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("[Selected Submission Context]\n");
        append(block, "submissionId", context.submissionId());
        append(block, "problemId", context.problemId());
        append(block, "scope", context.scope());
        append(block, "contestActive", context.contestActive());
        append(block, "language", context.language());
        append(block, "status", context.status());
        append(block, "judgeMessage", context.judgeMessage());
        append(block, "runtimeMs", context.runTimeMillis());
        append(block, "memoryKb", context.memoryKb());
        append(block, "score", context.score());
        append(block, "maxScore", context.maxScore());
        append(block, "codeHash", context.codeHash());
        append(block, "codeAllowedToModel", context.codeAllowedToModel());
        append(block, "policyMessage", context.policyMessage());
        if (context.caseResults() != null && !context.caseResults().isEmpty()) {
            block.append("caseResults:\n");
            context.caseResults().stream().limit(12).forEach(item -> appendCase(block, item));
        }
        if (hasText(context.stdoutExcerpt()) || hasText(context.stderrExcerpt())) {
            block.append("rawOutputPolicy: raw program output is omitted; use judgeMessage and caseResults summaries only.\n");
        }
        String problemBlock = problemContextResolver.contextBlock(context.problemContext());
        if (!problemBlock.isBlank()) {
            block.append('\n').append(problemBlock).append('\n');
        }
        if (context.codeAllowedToModel() && context.codeText() != null && !context.codeText().isBlank()) {
            block.append("\n<CURRENT_SUBMISSION_CODE language=\"")
                    .append(context.language() == null ? "" : context.language())
                    .append("\">\n")
                    .append(truncate(context.codeText(), 9000))
                    .append("\n</CURRENT_SUBMISSION_CODE>\n");
        } else {
            block.append("\n[Submission Code Redacted]\n")
                    .append("Do not ask the student to paste full source immediately; first use the status, judge message, cases, and problem facts above. ")
                    .append("During active contests, discuss debugging direction only and never output submit-ready code.\n");
        }
        return block.toString().trim();
    }

    private Map<String, Object> safeCaseSummary(AiSubmissionCaseContext item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (item == null) {
            return payload;
        }
        putIfPresent(payload, "caseIndex", item.caseIndex());
        putIfPresent(payload, "caseName", item.caseName());
        putIfPresent(payload, "status", item.status());
        putIfPresent(payload, "score", item.score());
        putIfPresent(payload, "maxScore", item.maxScore());
        putIfPresent(payload, "timeMillis", item.timeMillis());
        putIfPresent(payload, "memoryKb", item.memoryKb());
        putIfPresent(payload, "message", truncate(item.message(), 240));
        return payload;
    }

    private void appendCase(StringBuilder block, AiSubmissionCaseContext item) {
        block.append("- #").append(item.caseIndex() == null ? "?" : item.caseIndex() + 1)
                .append(" ").append(item.caseName() == null ? "" : item.caseName())
                .append(": ").append(item.status())
                .append(", score=").append(item.score()).append('/').append(item.maxScore())
                .append(", timeMs=").append(item.timeMillis())
                .append(", memoryKb=").append(item.memoryKb());
        if (item.message() != null && !item.message().isBlank()) {
            block.append(", message=").append(truncate(item.message(), 240));
        }
        block.append('\n');
    }

    private void append(StringBuilder block, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.isBlank()) {
            block.append(key).append(": ").append(text).append('\n');
        }
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            payload.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
