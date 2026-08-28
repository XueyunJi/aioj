package com.aioj.next.judge.domain;

import java.io.IOException;
import java.io.InputStream;

public interface OutputComparator {
    ComparisonResult compare(InputStream expectedOutput, InputStream actualOutput) throws IOException;

    record ComparisonResult(boolean accepted, String message) {
    }
}
