package com.aioj.next.ai.domain;

import com.aioj.next.contract.ai.AiChatRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.ai.domain.problem.ProblemDraftRepairPatch;
import com.aioj.next.ai.domain.problem.ProblemDraftStressGeneratorResult;

import java.util.List;
import java.util.Optional;

public interface AiProvider {
    AiCompletion chat(AiChatRequest request);

    default AiCompletion chat(AiChatRequest request, AiChatContext context) {
        return chat(request);
    }

    default AiCompletion chat(AiChatRequest request, AiChatContext context, AiModelScope scope) {
        return chat(request, context);
    }

    ProblemDraftResponse generateProblemDraft(Long id, ProblemDraftRequest request);

    default ProblemDraftStressGeneratorResult generateProblemDraftStressGenerator(Long id, ProblemDraftRequest request,
                                                                                 ProblemDraftResponse draft) {
        throw new UnsupportedOperationException("Provider does not support problem draft stress generator");
    }

    default ProblemDraftResponse regenerateProblemDraft(Long id, ProblemDraftResponse parentDraft, String feedback) {
        throw new UnsupportedOperationException("Provider does not support regeneration");
    }

    default ProblemDraftRepairPatch repairProblemDraft(Long id, ProblemDraftResponse draft, String verificationReportJson,
                                                       String originalRequestJson, int attempt, int maxAttempts) {
        throw new UnsupportedOperationException("Provider does not support problem draft repair");
    }

    default List<AiCompletion.MemorySignal> extractMemories(String userMessage, String assistantMessage) {
        return List.of();
    }

    default Optional<List<Double>> embed(String input) {
        return Optional.empty();
    }

    /**
     * The exact user-side content that will be sent to the model for a chat turn (without
     * the static system prompt). Leak detection must inspect this same text so the checked
     * surface always matches what the model actually receives.
     */
    default String assistantInputPreview(AiChatRequest request, AiChatContext context) {
        return request == null || request.message() == null ? "" : request.message();
    }

    String providerName();

    String model();
}
