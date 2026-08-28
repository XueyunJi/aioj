package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiRetrievalService;
import com.aioj.next.ai.persistence.entity.AiConversationTaskStateEntity;
import com.aioj.next.ai.persistence.mapper.AiClarificationAnswerMapper;
import com.aioj.next.ai.persistence.mapper.AiClarificationRequestMapper;
import com.aioj.next.ai.persistence.mapper.AiCodeSnapshotMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationSummaryMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationTaskStateMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * W1.7 wiring: [Resolved Reference] block placement in the context pack and the
 * activeProblemSetId/lastProblemSetId focus patch in the task state.
 */
@ExtendWith(MockitoExtension.class)
class ProblemSetFocusAndPackWiringTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-wire";

    @Mock
    private AiConversationTaskStateMapper taskStateMapper;
    @Mock
    private AiConversationSummaryMapper summaryMapper;
    @Mock
    private AiCodeSnapshotMapper codeSnapshotMapper;
    @Mock
    private AiClarificationRequestMapper clarificationRequestMapper;
    @Mock
    private AiClarificationAnswerMapper clarificationAnswerMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiRetrievalService retrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<AiConversationTaskStateEntity> stateRef = new AtomicReference<>();
    private ConversationStateMerger merger;
    private AiConversationContextV2Service service;

    @BeforeEach
    void setUp() {
        merger = new ConversationStateMerger(objectMapper);
        service = new AiConversationContextV2Service(
                taskStateMapper,
                summaryMapper,
                codeSnapshotMapper,
                clarificationRequestMapper,
                clarificationAnswerMapper,
                messageMapper,
                conversationMapper,
                retrievalService,
                objectMapper,
                merger,
                new ConversationContextPackBuilder(merger, objectMapper),
                new ConversationCompressionService(merger, objectMapper),
                new ClarificationDeduplicator()
        );
        stateRef.set(null);
        lenient().when(taskStateMapper.selectOne(any())).thenAnswer(ignored -> stateRef.get());
        lenient().doAnswer(invocation -> {
            stateRef.set(invocation.getArgument(0));
            return 1;
        }).when(taskStateMapper).insert(any(AiConversationTaskStateEntity.class));
        lenient().doAnswer(invocation -> {
            stateRef.set(invocation.getArgument(0));
            return 1;
        }).when(taskStateMapper).updateById(any(AiConversationTaskStateEntity.class));
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(summaryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void resolvedReferenceBlockLandsNearCurrentUserMessage() {
        service.beforeTurn(USER_ID, CONVERSATION_ID, request("讲一下第2题"));
        String block = "[Resolved Reference]\n- 引用 \"第2题\" 已解析为题目《题二》\n- 题面节选：\n题面：题二\n\n";

        String pack = service.contextPack(USER_ID, CONVERSATION_ID, request("讲一下第2题"), "", "", block);
        String legacyPack = service.contextPack(USER_ID, CONVERSATION_ID, request("讲一下第2题"), "", "");

        assertThat(pack).contains("[Resolved Reference]").contains("题面：题二");
        assertThat(pack.indexOf("[Resolved Reference]")).isGreaterThan(pack.indexOf("[Current User Message]"));
        assertThat(pack.indexOf("[Resolved Reference]")).isLessThan(pack.indexOf("[Current Conversation State]"));
        // Empty block keeps the old rendering byte-identical.
        assertThat(legacyPack).doesNotContain("[Resolved Reference]");
        assertThat(service.contextPack(USER_ID, CONVERSATION_ID, request("讲一下第2题"), "", "", ""))
                .isEqualTo(legacyPack);
    }

    @Test
    void focusPatchSetsActiveThenMovesPreviousActiveToLast() {
        service.beforeTurn(USER_ID, CONVERSATION_ID, request("题一怎么做"));

        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m1");
        Map<String, Object> state = merger.readState(stateRef.get().stateJson);
        assertThat(state.get("activeProblemSetId")).isEqualTo("m1");
        assertThat(state).doesNotContainKey("lastProblemSetId");

        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m2");
        state = merger.readState(stateRef.get().stateJson);
        assertThat(state.get("activeProblemSetId")).isEqualTo("m2");
        assertThat(state.get("lastProblemSetId")).isEqualTo("m1");

        // Topic switch never clears lastProblemSetId.
        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m3");
        state = merger.readState(stateRef.get().stateJson);
        assertThat(state.get("activeProblemSetId")).isEqualTo("m3");
        assertThat(state.get("lastProblemSetId")).isEqualTo("m2");
    }

    @Test
    void focusPatchWithSameOrNullSetIsNoOp() {
        service.beforeTurn(USER_ID, CONVERSATION_ID, request("题一怎么做"));
        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m1");

        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m1");
        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, null);
        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "  ");

        // Only the first patch wrote; same/null/blank set ids never touch the row.
        verify(taskStateMapper, times(1)).updateById(any(AiConversationTaskStateEntity.class));
        Map<String, Object> state = merger.readState(stateRef.get().stateJson);
        assertThat(state.get("activeProblemSetId")).isEqualTo("m1");
    }

    @Test
    void focusPatchWithoutStateRowIsNoOp() {
        service.patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m1");
        assertThat(stateRef.get()).isNull();
    }

    private AiChatRequest request(String message) {
        return new AiChatRequest(CONVERSATION_ID, null, message, "hint", null, null, null);
    }
}
