package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiProblemContextRequest;
import com.aioj.next.contract.ai.AiProblemContextResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AiProblemContextResolver {
    private final ProblemServiceClient problemServiceClient;

    public AiProblemContextResolver(ProblemServiceClient problemServiceClient) {
        this.problemServiceClient = problemServiceClient;
    }

    public AiProblemContextResponse resolve(Long userId, AiChatRequest request) {
        Long problemId = problemId(request);
        if (problemId == null) {
            return null;
        }
        AiChatRequest.ContestContext contest = request.contestContext();
        return problemServiceClient.aiProblemContext(new AiProblemContextRequest(
                userId,
                problemId,
                contest == null ? null : contest.contestId(),
                contest == null ? null : contest.contestRunId(),
                contest == null ? null : contest.contestProblemId(),
                "AI_CHAT"
        ));
    }

    public Map<String, Object> safeSummary(AiProblemContextResponse context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (context == null) {
            return payload;
        }
        putIfPresent(payload, "problemId", context.problemId() == null ? null : String.valueOf(context.problemId()));
        putIfPresent(payload, "contestId", context.contestId() == null ? null : String.valueOf(context.contestId()));
        putIfPresent(payload, "contestRunId", context.contestRunId() == null ? null : String.valueOf(context.contestRunId()));
        putIfPresent(payload, "contestProblemId", context.contestProblemId() == null ? null : String.valueOf(context.contestProblemId()));
        putIfPresent(payload, "title", context.title());
        putIfPresent(payload, "difficulty", context.difficulty());
        if (context.tags() != null && !context.tags().isEmpty()) {
            payload.put("tags", context.tags());
        }
        if (context.constraints() != null && !context.constraints().isEmpty()) {
            payload.put("constraints", context.constraints());
        }
        putIfPresent(payload, "statementSummary", truncate(firstNonBlank(context.statementSummary(), context.statement()), 700));
        putIfPresent(payload, "timeLimitMillis", context.timeLimitMillis());
        putIfPresent(payload, "memoryLimitKb", context.memoryLimitKb());
        putIfPresent(payload, "source", context.source());
        return payload;
    }

    public String contextBlock(AiProblemContextResponse context) {
        if (context == null) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        block.append("[Server Resolved Problem Context]\n");
        append(block, "problemId", context.problemId());
        append(block, "contestId", context.contestId());
        append(block, "contestRunId", context.contestRunId());
        append(block, "contestProblemId", context.contestProblemId());
        append(block, "title", context.title());
        append(block, "difficulty", context.difficulty());
        append(block, "tags", context.tags());
        append(block, "constraints", context.constraints());
        append(block, "timeLimitMillis", context.timeLimitMillis());
        append(block, "memoryLimitKb", context.memoryLimitKb());
        append(block, "source", context.source());
        if (context.statementSummary() != null && !context.statementSummary().isBlank()) {
            append(block, "statementSummary", context.statementSummary());
        } else {
            append(block, "statementSummary", truncate(context.statement(), 900));
        }
        if (context.samples() != null && !context.samples().isEmpty()) {
            block.append("samples:\n");
            context.samples().stream().limit(5).forEach(sample -> block
                    .append("- input=").append(truncate(sample.input(), 180))
                    .append("; expectedOutput=").append(truncate(sample.expectedOutput(), 180))
                    .append('\n'));
        }
        return block.toString().trim();
    }

    private Long problemId(AiChatRequest request) {
        if (request == null) {
            return null;
        }
        if (request.problemId() != null) {
            return request.problemId();
        }
        if (request.submissionContext() != null) {
            return null;
        }
        AiChatRequest.ProblemContext problem = request.problemContext();
        if (problem != null) {
            return parseLong(problem.id());
        }
        return null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
