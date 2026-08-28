package com.aioj.next.judge.domain;

import com.aioj.next.contract.problem.TestcaseCheckerProtocol;
import com.aioj.next.contract.problem.TestcaseCheckerType;

import java.nio.file.Path;
import java.util.List;

public record PreparedTestcasePackage(
        Long packageId,
        Long problemId,
        String sha256,
        Path cachePath,
        TestcaseCheckerType checkerType,
        String checkerLanguage,
        Path checkerSourceFile,
        TestcaseCheckerProtocol checkerProtocol,
        List<PreparedTestcaseCase> cases
) {
}
