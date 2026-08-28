package com.aioj.next.ai.domain.memory;

public final class AiMemoryJobTypes {
    public static final String EVENT_AI_CHAT_TURN_COMPLETED = "AI_CHAT_TURN_COMPLETED";
    public static final String JOB_AI_AFTER_TURN_MEMORY_PROFILE = "AI_AFTER_TURN_MEMORY_PROFILE";
    public static final String EVENT_SUBMISSION_JUDGED_SAFE = "SUBMISSION_JUDGED_SAFE";
    public static final String JOB_AI_JUDGED_SUBMISSION_ANALYSIS = "AI_JUDGED_SUBMISSION_ANALYSIS";
    public static final String EVENT_AI_MEMORY_MERGE_REQUESTED = "AI_MEMORY_MERGE_REQUESTED";
    public static final String JOB_AI_MEMORY_MERGE_REVIEW = "AI_MEMORY_MERGE_REVIEW";

    private AiMemoryJobTypes() {
    }
}
