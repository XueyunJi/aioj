package com.aioj.next.judge.domain;

import java.nio.file.Path;

public record TestcaseRunContext(
        Long packageId,
        Long caseId,
        Path stdinFile,
        Path expectedOutputFile,
        Path stdoutFile
) {
}
