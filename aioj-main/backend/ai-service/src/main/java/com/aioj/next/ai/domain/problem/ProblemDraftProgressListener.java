package com.aioj.next.ai.domain.problem;

public interface ProblemDraftProgressListener {
    ProblemDraftProgressListener NOOP = (stage, current, total, message) -> {
    };

    void onProgress(String stage, int current, int total, String message);
}
