package com.aioj.next.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "aioj.ai")
public class AiProperties {
    private String provider = "deepseek";
    private String baseUrl = "https://api.deepseek.com/chat/completions";
    private String apiKey = "";
    private String model = "deepseek-v4-pro";
    private String problemServiceUri = "http://localhost:8202";
    private String judgeWorkerUri = "http://localhost:8203";
    private int problemServiceConnectTimeoutMs = 2000;
    private int problemServiceReadTimeoutMs = 5000;
    private long problemServiceGuardCacheTtlMs = 30000;
    private long dailyLimit = 50;
    private long rollingLimit = 50;
    private int rollingWindowHours = 2;
    private long monthlyLimit = 1000;
    private Http http = new Http();
    private DeepSeek deepseek = new DeepSeek();
    private Intent intent = new Intent();
    private Embedding embedding = new Embedding();
    private Capacity capacity = new Capacity();
    private ContestLeakGuard contestLeakGuard = new ContestLeakGuard();
    private Context context = new Context();
    private Recall recall = new Recall();
    private MemoryJobs memoryJobs = new MemoryJobs();
    private AgentJobs agentJobs = new AgentJobs();
    private ProblemDraft problemDraft = new ProblemDraft();
    private AgentCore agentCore = new AgentCore();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProblemServiceUri() {
        return problemServiceUri;
    }

    public void setProblemServiceUri(String problemServiceUri) {
        this.problemServiceUri = problemServiceUri;
    }

    public String getJudgeWorkerUri() {
        return judgeWorkerUri;
    }

    public void setJudgeWorkerUri(String judgeWorkerUri) {
        this.judgeWorkerUri = judgeWorkerUri;
    }

    public int getProblemServiceConnectTimeoutMs() {
        return problemServiceConnectTimeoutMs;
    }

    public void setProblemServiceConnectTimeoutMs(int problemServiceConnectTimeoutMs) {
        this.problemServiceConnectTimeoutMs = problemServiceConnectTimeoutMs;
    }

    public int getProblemServiceReadTimeoutMs() {
        return problemServiceReadTimeoutMs;
    }

    public void setProblemServiceReadTimeoutMs(int problemServiceReadTimeoutMs) {
        this.problemServiceReadTimeoutMs = problemServiceReadTimeoutMs;
    }

    public long getProblemServiceGuardCacheTtlMs() {
        return problemServiceGuardCacheTtlMs;
    }

    public void setProblemServiceGuardCacheTtlMs(long problemServiceGuardCacheTtlMs) {
        this.problemServiceGuardCacheTtlMs = problemServiceGuardCacheTtlMs;
    }

    public long getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public long getRollingLimit() {
        return rollingLimit;
    }

    public void setRollingLimit(long rollingLimit) {
        this.rollingLimit = rollingLimit;
    }

    public int getRollingWindowHours() {
        return rollingWindowHours;
    }

    public void setRollingWindowHours(int rollingWindowHours) {
        this.rollingWindowHours = rollingWindowHours;
    }

    public long getMonthlyLimit() {
        return monthlyLimit;
    }

    public void setMonthlyLimit(long monthlyLimit) {
        this.monthlyLimit = monthlyLimit;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http == null ? new Http() : http;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek == null ? new DeepSeek() : deepseek;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent == null ? new Intent() : intent;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding == null ? new Embedding() : embedding;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public void setCapacity(Capacity capacity) {
        this.capacity = capacity == null ? new Capacity() : capacity;
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context == null ? new Context() : context;
    }

    public MemoryJobs getMemoryJobs() {
        return memoryJobs;
    }

    public void setMemoryJobs(MemoryJobs memoryJobs) {
        this.memoryJobs = memoryJobs == null ? new MemoryJobs() : memoryJobs;
    }

    public AgentJobs getAgentJobs() {
        return agentJobs;
    }

    public void setAgentJobs(AgentJobs agentJobs) {
        this.agentJobs = agentJobs == null ? new AgentJobs() : agentJobs;
    }

    public Recall getRecall() {
        return recall;
    }

    public void setRecall(Recall recall) {
        this.recall = recall == null ? new Recall() : recall;
    }

    public ProblemDraft getProblemDraft() {
        return problemDraft;
    }

    public void setProblemDraft(ProblemDraft problemDraft) {
        this.problemDraft = problemDraft == null ? new ProblemDraft() : problemDraft;
    }

    public AgentCore getAgentCore() {
        return agentCore;
    }

    public void setAgentCore(AgentCore agentCore) {
        this.agentCore = agentCore == null ? new AgentCore() : agentCore;
    }

    /** Agent Core V3 (new pipeline) budgets and limits; prefix aioj.ai.agent-core.*. */
    public static class AgentCore {
        private int maxAgentSteps = 8;
        private int maxToolCalls = 6;
        private int maxSearchCalls = 3;
        private int maxFetchCalls = 3;
        private int toolResultMaxTokens = 4000;
        private int bootstrapBudgetTokens = 6000;
        private int fetchBudgetTokens = 8000;
        private int recentTurnsLimit = 6;
        private long turnTimeoutMs = 210_000L;
        private String curatorScope = "AGENT_CURATOR";
        private double fingerprintContainmentThreshold = 0.45;
        private ContestSearch contestSearch = new ContestSearch();
        private OutputGuard outputGuard = new OutputGuard();
        private PseudoStream pseudoStream = new PseudoStream();

        public int getMaxAgentSteps() {
            return maxAgentSteps;
        }

        public void setMaxAgentSteps(int maxAgentSteps) {
            this.maxAgentSteps = Math.max(1, maxAgentSteps);
        }

        public int getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(int maxToolCalls) {
            this.maxToolCalls = Math.max(0, maxToolCalls);
        }

        public int getMaxSearchCalls() {
            return maxSearchCalls;
        }

        public void setMaxSearchCalls(int maxSearchCalls) {
            this.maxSearchCalls = Math.max(0, maxSearchCalls);
        }

        public int getMaxFetchCalls() {
            return maxFetchCalls;
        }

        public void setMaxFetchCalls(int maxFetchCalls) {
            this.maxFetchCalls = Math.max(0, maxFetchCalls);
        }

        public int getToolResultMaxTokens() {
            return toolResultMaxTokens;
        }

        public void setToolResultMaxTokens(int toolResultMaxTokens) {
            this.toolResultMaxTokens = Math.max(256, toolResultMaxTokens);
        }

        public int getBootstrapBudgetTokens() {
            return bootstrapBudgetTokens;
        }

        public void setBootstrapBudgetTokens(int bootstrapBudgetTokens) {
            this.bootstrapBudgetTokens = Math.max(500, bootstrapBudgetTokens);
        }

        public int getFetchBudgetTokens() {
            return fetchBudgetTokens;
        }

        public void setFetchBudgetTokens(int fetchBudgetTokens) {
            this.fetchBudgetTokens = Math.max(500, fetchBudgetTokens);
        }

        public String getCuratorScope() {
            return curatorScope;
        }

        public void setCuratorScope(String curatorScope) {
            this.curatorScope = curatorScope == null || curatorScope.isBlank() ? "AGENT_CURATOR" : curatorScope.trim();
        }

        public int getRecentTurnsLimit() {
            return recentTurnsLimit;
        }

        public void setRecentTurnsLimit(int recentTurnsLimit) {
            this.recentTurnsLimit = Math.max(0, recentTurnsLimit);
        }

        public long getTurnTimeoutMs() {
            return turnTimeoutMs;
        }

        public void setTurnTimeoutMs(long turnTimeoutMs) {
            this.turnTimeoutMs = Math.max(1L, turnTimeoutMs);
        }

        public double getFingerprintContainmentThreshold() {
            return fingerprintContainmentThreshold;
        }

        public void setFingerprintContainmentThreshold(double fingerprintContainmentThreshold) {
            if (Double.isNaN(fingerprintContainmentThreshold) || fingerprintContainmentThreshold <= 0) {
                return;
            }
            this.fingerprintContainmentThreshold = Math.min(1.0, fingerprintContainmentThreshold);
        }

        public ContestSearch getContestSearch() {
            return contestSearch;
        }

        public void setContestSearch(ContestSearch contestSearch) {
            this.contestSearch = contestSearch == null ? new ContestSearch() : contestSearch;
        }

        public OutputGuard getOutputGuard() {
            return outputGuard;
        }

        public void setOutputGuard(OutputGuard outputGuard) {
            this.outputGuard = outputGuard == null ? new OutputGuard() : outputGuard;
        }

        public PseudoStream getPseudoStream() {
            return pseudoStream;
        }

        public void setPseudoStream(PseudoStream pseudoStream) {
            this.pseudoStream = pseudoStream == null ? new PseudoStream() : pseudoStream;
        }

        /**
         * L4 output-guard settings (P3-5, design doc §5.4);
         * prefix aioj.ai.agent-core.output-guard.*.
         */
        public static class OutputGuard {
            private FullCode fullCode = new FullCode();

            public FullCode getFullCode() {
                return fullCode;
            }

            public void setFullCode(FullCode fullCode) {
                this.fullCode = fullCode == null ? new FullCode() : fullCode;
            }

            /** FullCodeHeuristicDetector threshold (aioj.ai.agent-core.output-guard.full-code.*). */
            public static class FullCode {
                /** Feature families hit to judge a draft as complete submittable code; clamped to 1..5. */
                private int minFeatures = 4;

                public int getMinFeatures() {
                    return minFeatures;
                }

                public void setMinFeatures(int minFeatures) {
                    this.minFeatures = Math.min(5, Math.max(1, minFeatures));
                }
            }
        }

        /**
         * PseudoStreamReplayer slicing (P3-5, Q2);
         * prefix aioj.ai.agent-core.pseudo-stream.*.
         */
        public static class PseudoStream {
            /** Characters per SSE delta chunk; clamped to at least 50. */
            private int chunkSize = 300;

            public int getChunkSize() {
                return chunkSize;
            }

            public void setChunkSize(int chunkSize) {
                this.chunkSize = Math.max(50, chunkSize);
            }
        }

        /**
         * problem.search anti-enumeration controls (P3-3, design doc §5.5);
         * prefix aioj.ai.agent-core.contest-search.*.
         */
        public static class ContestSearch {
            private RateLimit rateLimit = new RateLimit();

            public RateLimit getRateLimit() {
                return rateLimit;
            }

            public void setRateLimit(RateLimit rateLimit) {
                this.rateLimit = rateLimit == null ? new RateLimit() : rateLimit;
            }

            /** In-memory sliding-window limiter settings (aioj.ai.agent-core.contest-search.rate-limit.*). */
            public static class RateLimit {
                private long windowSeconds = 60;
                private int maxCallsPerWindow = 5;

                public long getWindowSeconds() {
                    return windowSeconds;
                }

                public void setWindowSeconds(long windowSeconds) {
                    this.windowSeconds = Math.max(1L, windowSeconds);
                }

                public int getMaxCallsPerWindow() {
                    return maxCallsPerWindow;
                }

                public void setMaxCallsPerWindow(int maxCallsPerWindow) {
                    this.maxCallsPerWindow = Math.max(1, maxCallsPerWindow);
                }
            }
        }
    }

    /** Agent Core V3 async job worker (ai_async_jobs); prefix aioj.ai.agent-jobs.*. */
    public static class AgentJobs {
        private boolean enabled = true;
        private long pollIntervalMs = 5000;
        private int batchSize = 4;
        private long leaseSeconds = 120;
        private int maxAttempts = 5;
        private long backoffBaseSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = Math.max(1, leaseSeconds);
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        public long getBackoffBaseSeconds() {
            return backoffBaseSeconds;
        }

        public void setBackoffBaseSeconds(long backoffBaseSeconds) {
            this.backoffBaseSeconds = Math.max(1, backoffBaseSeconds);
        }
    }

    public static class DeepSeek {
        private boolean thinkingEnabled = false;
        private String reasoningEffort = "high";

        public boolean isThinkingEnabled() {
            return thinkingEnabled;
        }

        public void setThinkingEnabled(boolean thinkingEnabled) {
            this.thinkingEnabled = thinkingEnabled;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }
    }

    public static class ProblemDraft {
        private int maxRepairAttempts = 5;
        private String testcaseArtifactStorageRoot = System.getProperty("user.home") + "/.ai-oj-next/ai-draft-testcase-artifacts";
        private long maxOfficialCaseBytes = 8L * 1024L * 1024L;
        private long maxOfficialPackageBytes = 50L * 1024L * 1024L;
        private List<String> allowedTags = new ArrayList<>(List.of(
                "implementation",
                "math",
                "greedy",
                "dp",
                "binary_search",
                "graphs",
                "shortest_paths",
                "trees",
                "strings",
                "data_structures",
                "sorting",
                "sortings",
                "数组",
                "哈希",
                "排序",
                "实现",
                "数学",
                "贪心",
                "动态规划",
                "二分",
                "图论",
                "最短路",
                "树",
                "字符串",
                "数据结构"
        ));

        public int getMaxRepairAttempts() {
            return maxRepairAttempts;
        }

        public void setMaxRepairAttempts(int maxRepairAttempts) {
            this.maxRepairAttempts = Math.max(0, maxRepairAttempts);
        }

        public String getTestcaseArtifactStorageRoot() {
            return testcaseArtifactStorageRoot;
        }

        public void setTestcaseArtifactStorageRoot(String testcaseArtifactStorageRoot) {
            this.testcaseArtifactStorageRoot = testcaseArtifactStorageRoot;
        }

        public long getMaxOfficialCaseBytes() {
            return maxOfficialCaseBytes;
        }

        public void setMaxOfficialCaseBytes(long maxOfficialCaseBytes) {
            this.maxOfficialCaseBytes = Math.max(1L, maxOfficialCaseBytes);
        }

        public long getMaxOfficialPackageBytes() {
            return maxOfficialPackageBytes;
        }

        public void setMaxOfficialPackageBytes(long maxOfficialPackageBytes) {
            this.maxOfficialPackageBytes = Math.max(1L, maxOfficialPackageBytes);
        }

        public List<String> getAllowedTags() {
            return allowedTags;
        }

        public void setAllowedTags(List<String> allowedTags) {
            this.allowedTags = allowedTags == null ? new ArrayList<>() : new ArrayList<>(allowedTags);
        }
    }

    public static class Http {
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 180000;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Intent {
        private boolean enabled = true;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int maxContextChars = 6000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }
    }

    public static class Embedding {
        private boolean enabled = true;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
        private String apiKey = "";
        private String model = "text-embedding-v3";
        private int dimension = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

    public static class ContestLeakGuard {
        private boolean enabled = true;
        private double matchThreshold = 0.75;
        private double recallThreshold = 0.45;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMatchThreshold() {
            return matchThreshold;
        }

        public void setMatchThreshold(double matchThreshold) {
            this.matchThreshold = matchThreshold;
        }

        public double getRecallThreshold() {
            return recallThreshold;
        }

        public void setRecallThreshold(double recallThreshold) {
            this.recallThreshold = recallThreshold;
        }
    }

    public ContestLeakGuard getContestLeakGuard() {
        return contestLeakGuard;
    }

    public void setContestLeakGuard(ContestLeakGuard contestLeakGuard) {
        this.contestLeakGuard = contestLeakGuard == null ? new ContestLeakGuard() : contestLeakGuard;
    }

    public static class Capacity {
        private int chatCorePoolSize = 4;
        private int chatMaxPoolSize = 8;
        private int chatQueueCapacity = 64;
        private int studentAssistantConcurrency = 8;
        private int problemDraftConcurrency = 2;
        private int intentMemoryConcurrency = 4;
        private int batchReportConcurrency = 1;

        public int getChatCorePoolSize() {
            return chatCorePoolSize;
        }

        public void setChatCorePoolSize(int chatCorePoolSize) {
            this.chatCorePoolSize = chatCorePoolSize;
        }

        public int getChatMaxPoolSize() {
            return chatMaxPoolSize;
        }

        public void setChatMaxPoolSize(int chatMaxPoolSize) {
            this.chatMaxPoolSize = chatMaxPoolSize;
        }

        public int getChatQueueCapacity() {
            return chatQueueCapacity;
        }

        public void setChatQueueCapacity(int chatQueueCapacity) {
            this.chatQueueCapacity = chatQueueCapacity;
        }

        public int getStudentAssistantConcurrency() {
            return studentAssistantConcurrency;
        }

        public void setStudentAssistantConcurrency(int studentAssistantConcurrency) {
            this.studentAssistantConcurrency = studentAssistantConcurrency;
        }

        public int getProblemDraftConcurrency() {
            return problemDraftConcurrency;
        }

        public void setProblemDraftConcurrency(int problemDraftConcurrency) {
            this.problemDraftConcurrency = problemDraftConcurrency;
        }

        public int getIntentMemoryConcurrency() {
            return intentMemoryConcurrency;
        }

        public void setIntentMemoryConcurrency(int intentMemoryConcurrency) {
            this.intentMemoryConcurrency = intentMemoryConcurrency;
        }

        public int getBatchReportConcurrency() {
            return batchReportConcurrency;
        }

        public void setBatchReportConcurrency(int batchReportConcurrency) {
            this.batchReportConcurrency = batchReportConcurrency;
        }
    }

    public static class Context {
        private int defaultWindowTokens = 64_000;
        private double compressionThresholdRatio = 0.70;
        private double outputReserveRatio = 0.20;
        private double estimatorSafetyFactor = 1.20;
        private int hardMaxMemoryTokens = 2_500;
        private int hardMaxRetrievedHistoryTokens = 3_000;
        private int hardMaxSubmissionCodeTokens = 12_000;
        private double estimatorEwmaAlpha = 0.20;

        public int getDefaultWindowTokens() {
            return defaultWindowTokens;
        }

        public void setDefaultWindowTokens(int defaultWindowTokens) {
            this.defaultWindowTokens = defaultWindowTokens;
        }

        public double getCompressionThresholdRatio() {
            return compressionThresholdRatio;
        }

        public void setCompressionThresholdRatio(double compressionThresholdRatio) {
            this.compressionThresholdRatio = compressionThresholdRatio;
        }

        public double getOutputReserveRatio() {
            return outputReserveRatio;
        }

        public void setOutputReserveRatio(double outputReserveRatio) {
            this.outputReserveRatio = outputReserveRatio;
        }

        public double getEstimatorSafetyFactor() {
            return estimatorSafetyFactor;
        }

        public void setEstimatorSafetyFactor(double estimatorSafetyFactor) {
            this.estimatorSafetyFactor = estimatorSafetyFactor;
        }

        public int getHardMaxMemoryTokens() {
            return hardMaxMemoryTokens;
        }

        public void setHardMaxMemoryTokens(int hardMaxMemoryTokens) {
            this.hardMaxMemoryTokens = hardMaxMemoryTokens;
        }

        public int getHardMaxRetrievedHistoryTokens() {
            return hardMaxRetrievedHistoryTokens;
        }

        public void setHardMaxRetrievedHistoryTokens(int hardMaxRetrievedHistoryTokens) {
            this.hardMaxRetrievedHistoryTokens = hardMaxRetrievedHistoryTokens;
        }

        public int getHardMaxSubmissionCodeTokens() {
            return hardMaxSubmissionCodeTokens;
        }

        public double getEstimatorEwmaAlpha() {
            return estimatorEwmaAlpha;
        }

        public void setEstimatorEwmaAlpha(double estimatorEwmaAlpha) {
            this.estimatorEwmaAlpha = estimatorEwmaAlpha;
        }

        public void setHardMaxSubmissionCodeTokens(int hardMaxSubmissionCodeTokens) {
            this.hardMaxSubmissionCodeTokens = hardMaxSubmissionCodeTokens;
        }
    }

    public static class MemoryJobs {
        private boolean enabled = true;
        private long pollIntervalMs = 5000;
        private int batchSize = 8;
        private long leaseSeconds = 60;
        private int maxAttempts = 3;
        private long backoffBaseSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffBaseSeconds() {
            return backoffBaseSeconds;
        }

        public void setBackoffBaseSeconds(long backoffBaseSeconds) {
            this.backoffBaseSeconds = backoffBaseSeconds;
        }
    }

    public static class Recall {
        private long cooldownWindowMinutes = 30;
        private double cooldownPenalty = 0.35;
        private double confirmedBoost = 0.10;

        public long getCooldownWindowMinutes() {
            return cooldownWindowMinutes;
        }

        public void setCooldownWindowMinutes(long cooldownWindowMinutes) {
            this.cooldownWindowMinutes = Math.max(0, cooldownWindowMinutes);
        }

        public double getCooldownPenalty() {
            return cooldownPenalty;
        }

        public void setCooldownPenalty(double cooldownPenalty) {
            this.cooldownPenalty = Math.max(0, cooldownPenalty);
        }

        public double getConfirmedBoost() {
            return confirmedBoost;
        }

        public void setConfirmedBoost(double confirmedBoost) {
            this.confirmedBoost = Math.max(0, confirmedBoost);
        }
    }
}
