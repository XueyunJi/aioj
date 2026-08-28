package com.aioj.next.ai.agent;

import com.aioj.next.ai.agent.runtime.TurnCoordinator;
import com.aioj.next.ai.domain.AiChatContext;
import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.aioj.next.contract.ai.AiChatRequest;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Thin facade over the Agent Core V3 pipeline (design doc §2): the only type
 * AiController talks to for chat turns. The legacy AiChatTurnService bean is
 * kept in the codebase (archive round will remove it) but is no longer part of
 * the request path — never mix the two pipelines.
 */
@Service
public class AgentChatFacade {

    private final TurnCoordinator turnCoordinator;

    public AgentChatFacade(TurnCoordinator turnCoordinator) {
        this.turnCoordinator = turnCoordinator;
    }

    public TurnHandle start(Long userId, AiChatRequest request) {
        return turnCoordinator.start(userId, request);
    }

    public TurnHandle resume(Long userId, String turnId, AiChatRequest request) {
        return turnCoordinator.resume(userId, turnId, request);
    }

    /** Same shape as the legacy handle so the controller surface is unchanged. */
    public record TurnHandle(
            AiConversationEntity conversation,
            AiChatContext context,
            AiChatMessageResponse user,
            AiChatMessageResponse assistant,
            CompletableFuture<TurnResult> result,
            String turnId
    ) {
    }

    /** Same shape as the legacy result so the controller surface is unchanged. */
    public record TurnResult(
            AiCompletion completion,
            AiAssistantResponseNormalizer.NormalizedResponse normalized,
            AiChatMessageResponse assistant,
            String conversationMode,
            int memoryCount,
            /**
             * P3-5: true for restricted contest turns (L4 checked, BUFFERED): the
             * SSE endpoint replays the verified content as delta events before the
             * full message event. Also true when the turn ended in a safe refusal,
             * so the refusal rides the same replay path.
             */
            boolean pseudoStream
    ) {
        /** Pre-P3-5 signature, kept so existing callers/tests compile unchanged. */
        public TurnResult(AiCompletion completion, AiAssistantResponseNormalizer.NormalizedResponse normalized,
                          AiChatMessageResponse assistant, String conversationMode, int memoryCount) {
            this(completion, normalized, assistant, conversationMode, memoryCount, false);
        }
    }
}
