package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.context.AiContextBudgetReport;
import com.aioj.next.ai.domain.context.AiTokenEstimator;
import com.aioj.next.ai.domain.context.AiContextBuildReport;
import com.aioj.next.ai.domain.context.AiContextSection;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiContextBudgetService {
    private static final int SYSTEM_PROMPT_ESTIMATE_TOKENS = 900;
    /** EWMA self-calibration bounds: never trust a ratio that more than doubles or halves the estimate. */
    private static final double MIN_ESTIMATION_RATIO = 0.5;
    private static final double MAX_ESTIMATION_RATIO = 2.0;

    private final AiProperties properties;
    /**
     * W1.8: process-local EWMA of actual/estimated prompt tokens reported by
     * the provider. Converges the heuristic estimate towards real usage;
     * starts at 1.0 (no correction).
     */
    private final AtomicReference<Double> estimationRatio = new AtomicReference<>(1.0);
    private final AiModelContextWindowRegistry windowRegistry;

    public AiContextBudgetService(AiProperties properties, AiModelContextWindowRegistry windowRegistry) {
        this.properties = properties;
        this.windowRegistry = windowRegistry;
    }

    public BudgetEvaluation evaluate(String model, AiChatRequest request, AiContextBuildReport report, String renderedContextPack) {
        AiProperties.Context config = properties.getContext();
        int window = windowRegistry.resolve(model);
        int compressionThreshold = ratio(window, config.getCompressionThresholdRatio());
        int outputReserve = ratio(window, config.getOutputReserveRatio());
        int maxPromptBudget = Math.min(compressionThreshold, Math.max(1, window - outputReserve));
        Map<String, Integer> bySection = estimateBySection(request, report, renderedContextPack, config);
        int total = applyEstimationRatio(bySection.values().stream().mapToInt(Integer::intValue).sum());
        return new BudgetEvaluation(
                model == null ? "" : model,
                window,
                compressionThreshold,
                maxPromptBudget,
                total,
                bySection
        );
    }

    public AiContextBudgetReport report(
            BudgetEvaluation before,
            BudgetEvaluation after,
            boolean compressionApplied,
            List<String> trimmedSections,
            List<String> droppedSections,
            List<String> warnings
    ) {
        BudgetEvaluation effectiveAfter = after == null ? before : after;
        if (effectiveAfter == null) {
            return AiContextBudgetReport.empty();
        }
        return new AiContextBudgetReport(
                effectiveAfter.model(),
                effectiveAfter.modelWindowTokens(),
                effectiveAfter.compressionThresholdTokens(),
                effectiveAfter.maxPromptBudgetTokens(),
                before == null ? effectiveAfter.estimatedPromptTokens() : before.estimatedPromptTokens(),
                effectiveAfter.estimatedPromptTokens(),
                compressionApplied,
                trimmedSections == null ? List.of() : List.copyOf(trimmedSections),
                droppedSections == null ? List.of() : List.copyOf(droppedSections),
                effectiveAfter.estimatedBySection(),
                warnings == null ? List.of() : List.copyOf(warnings)
        );
    }

    private Map<String, Integer> estimateBySection(
            AiChatRequest request,
            AiContextBuildReport report,
            String renderedContextPack,
            AiProperties.Context config
    ) {
        Map<String, Integer> estimates = new LinkedHashMap<>();
        estimates.put("system.prompt", applySafety(SYSTEM_PROMPT_ESTIMATE_TOKENS));
        if (report != null && report.sections() != null) {
            for (AiContextSection section : report.sections()) {
                if (section == null || section.id() == null || section.id().isBlank()) {
                    continue;
                }
                estimates.put(section.id(), applySafety(section.estimatedTokens()));
            }
        }
        putEstimate(estimates, "rendered.conversation_context_pack", estimateTextTokens(renderedContextPack));
        if (request != null) {
            putEstimate(estimates, "request.problem_context", estimateProblemContext(request.problemContext()));
            putEstimate(estimates, "request.selection_context", estimateSelectionContext(request.selectionContext()));
            putEstimate(estimates, "request.code_context", capped(estimateCodeContext(request.codeContext()), config.getHardMaxSubmissionCodeTokens()));
        }
        return estimates;
    }

    private void putEstimate(Map<String, Integer> estimates, String key, int tokens) {
        if (tokens > 0) {
            estimates.merge(key, applySafety(tokens), Integer::sum);
        }
    }

    private int estimateProblemContext(AiChatRequest.ProblemContext context) {
        if (context == null) {
            return 0;
        }
        return estimateTextTokens(join(
                context.title(),
                context.difficulty(),
                context.statement(),
                context.notes(),
                context.tags() == null ? "" : String.join("\n", context.tags())
        ));
    }

    private int estimateSelectionContext(AiChatRequest.SelectionContext context) {
        if (context == null) {
            return 0;
        }
        AiChatRequest.SurroundingContext surrounding = context.surroundingContext();
        return estimateTextTokens(join(
                context.selectedText(),
                context.selectedMarkdown(),
                surrounding == null ? "" : join(
                        surrounding.before(),
                        surrounding.after(),
                        surrounding.sectionTitle(),
                        surrounding.messagePreview()
                ),
                context.codeContext() == null ? "" : join(
                        context.codeContext().language(),
                        context.codeContext().functionName(),
                        context.codeContext().enclosingSymbol(),
                        context.codeContext().latestCodeMessageId(),
                        context.codeContext().codeHash()
                )
        ));
    }

    private int estimateCodeContext(AiChatRequest.CodeContext context) {
        if (context == null) {
            return 0;
        }
        return estimateTextTokens(context.code());
    }

    /**
     * Feeds the EWMA self-calibration with the provider-reported actual
     * prompt_tokens of a completed turn. {@code estimatedPromptTokens} is the
     * final estimate that was produced for that same prompt (post safety
     * factor and post current ratio); the current ratio is stripped before
     * sampling so the EWMA converges exactly to actual/base instead of a
     * geometric mean.
     */
    public void recordActualUsage(int estimatedPromptTokens, long actualPromptTokens) {
        if (estimatedPromptTokens <= 0 || actualPromptTokens <= 0) {
            return;
        }
        double alpha = properties.getContext().getEstimatorEwmaAlpha();
        if (Double.isNaN(alpha) || alpha <= 0 || alpha > 1) {
            alpha = 0.20;
        }
        final double effectiveAlpha = alpha;
        estimationRatio.updateAndGet(current -> {
            double baseEstimate = Math.max(1.0, estimatedPromptTokens / current);
            double sample = actualPromptTokens / baseEstimate;
            return clampRatio(current + effectiveAlpha * (sample - current));
        });
    }

    double estimationRatio() {
        return estimationRatio.get();
    }

    private int applyEstimationRatio(int rawTotal) {
        if (rawTotal <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(rawTotal * estimationRatio.get()));
    }

    private double clampRatio(double ratio) {
        return Math.min(MAX_ESTIMATION_RATIO, Math.max(MIN_ESTIMATION_RATIO, ratio));
    }

    private int estimateTextTokens(String value) {
        return AiTokenEstimator.estimate(value);
    }

    private int capped(int value, int max) {
        return max <= 0 ? value : Math.min(value, max);
    }

    private int applySafety(int tokens) {
        if (tokens <= 0) {
            return 0;
        }
        double factor = properties.getContext().getEstimatorSafetyFactor();
        return Math.max(1, (int) Math.ceil(tokens * Math.max(0.1, factor)));
    }

    private int ratio(int window, double ratio) {
        double normalized = ratio <= 0 ? 0.70 : ratio;
        return Math.max(1, (int) Math.floor(window * normalized));
    }

    private String join(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(value);
            }
        }
        return builder.toString();
    }

    public record BudgetEvaluation(
            String model,
            int modelWindowTokens,
            int compressionThresholdTokens,
            int maxPromptBudgetTokens,
            int estimatedPromptTokens,
            Map<String, Integer> estimatedBySection
    ) {
        public boolean overBudget() {
            return estimatedPromptTokens >= maxPromptBudgetTokens;
        }
    }
}
