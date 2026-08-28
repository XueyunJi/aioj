package com.aioj.next.contract.ai;

import com.aioj.next.contract.problem.TestCaseDto;

import java.time.Instant;
import java.util.List;

public record AiProblemContextResponse(
        Long problemId,
        Long contestId,
        Long contestRunId,
        Long contestProblemId,
        String title,
        String difficulty,
        String statement,
        String statementSummary,
        List<String> tags,
        List<String> constraints,
        List<TestCaseDto> samples,
        Integer timeLimitMillis,
        Integer memoryLimitKb,
        String source,
        Instant snapshotAt
) {
}
