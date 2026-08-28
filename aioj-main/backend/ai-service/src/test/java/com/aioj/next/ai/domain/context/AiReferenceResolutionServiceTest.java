package com.aioj.next.ai.domain.context;

import com.aioj.next.ai.domain.AiCompletion;
import com.aioj.next.ai.persistence.entity.AiClarificationRequestEntity;
import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.ai.persistence.entity.AiConversationTaskStateEntity;
import com.aioj.next.ai.persistence.entity.AiTurnEntity;
import com.aioj.next.ai.persistence.mapper.AiClarificationRequestMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationTaskStateMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1.7 orchestration: manifest persistence on ai_turns, ambiguity clarification reuse,
 * [Resolved Reference] rendering and pending-clarification attachment for the SSE event.
 */
@ExtendWith(MockitoExtension.class)
class AiReferenceResolutionServiceTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-ref-svc";

    @Mock
    private AiConversationProblemMapper problemMapper;
    @Mock
    private AiConversationTaskStateMapper taskStateMapper;
    @Mock
    private AiTurnMapper turnMapper;
    @Mock
    private AiClarificationRequestMapper clarificationRequestMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger = new ConversationStateMerger(objectMapper);
    private AiReferenceResolutionService service;

    @BeforeEach
    void setUp() {
        service = new AiReferenceResolutionService(
                problemMapper,
                taskStateMapper,
                turnMapper,
                clarificationRequestMapper,
                merger,
                new ReferenceResolver(),
                objectMapper
        );
        lenient().when(turnMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        lenient().when(clarificationRequestMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
    }

    @Test
    void hitTurnPersistsManifestOnTurnRow() {
        stubProblems(List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2)
        ));
        stubFocus("m1", null);
        when(turnMapper.selectOne(any())).thenReturn(turn("t-1"));

        service.processTurn(USER_ID, CONVERSATION_ID, request("讲一下第2题", "cm-9", null));

        UpdateWrapper<AiTurnEntity> update = capturedTurnUpdate();
        assertThat(update.getSqlSet()).contains("context_manifest_json");
        String manifest = capturedManifestPayload();
        assertThat(manifest).contains("referenceResolutions");
        assertThat(manifest).contains("active_set_ordinal");
        assertThat(manifest).contains("102");
        verify(clarificationRequestMapper, never()).insert(any(AiClarificationRequestEntity.class));
    }

    @Test
    void noPatternTurnWritesNothing() {
        stubProblems(List.of(row(1L, 101L, "题一", "m1", 1, 1)));
        stubFocus("m1", null);

        service.processTurn(USER_ID, CONVERSATION_ID, request("这道题怎么入手", "cm-10", null));

        verify(turnMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(clarificationRequestMapper, never()).insert(any(AiClarificationRequestEntity.class));
    }

    @Test
    void ambiguousTurnIssuesClarificationAndManifestWithoutBinding() {
        stubProblems(twoBatchProblems());
        stubFocus("m2", "m1");
        when(turnMapper.selectOne(any())).thenReturn(turn("t-2"));
        when(clarificationRequestMapper.selectOne(any())).thenReturn(null);
        when(clarificationRequestMapper.insert(any(AiClarificationRequestEntity.class))).thenReturn(1);

        service.processTurn(USER_ID, CONVERSATION_ID, request("第2题的思路是什么", "cm-11", null));

        ArgumentCaptor<AiClarificationRequestEntity> captor = ArgumentCaptor.forClass(AiClarificationRequestEntity.class);
        verify(clarificationRequestMapper).insert(captor.capture());
        AiClarificationRequestEntity issued = captor.getValue();
        assertThat(issued.status).isEqualTo("PENDING");
        assertThat(issued.requestKey).isEqualTo("ref_resolve_cm-11");
        assertThat(issued.question).contains("题B").contains("题D");
        assertThat(issued.inputSchema).contains("single_choice").contains("candidate");
        // Older resolver questions of this conversation are superseded.
        verify(clarificationRequestMapper).update(isNull(), any(UpdateWrapper.class));
        // Manifest records the ambiguity (no silent binding).
        assertThat(capturedManifestPayload()).contains("ambiguous_set_ordinal");
    }

    @Test
    void clarificationAnswerTurnResolvesFromStoredCandidates() {
        stubProblems(twoBatchProblems());
        stubFocus("m2", "m1");
        when(turnMapper.selectOne(any())).thenReturn(turn("t-3"));
        AiClarificationRequestEntity stored = storedAmbiguityRow();
        when(clarificationRequestMapper.selectOne(any())).thenReturn(stored);
        AiChatRequest.ClarificationAnswer answer = new AiChatRequest.ClarificationAnswer(
                "ref_resolve_cm-11", stored.question, null, List.of("《题D》（这一批第 2 题）"), null);

        service.processTurn(USER_ID, CONVERSATION_ID, request("选这一批那道", "cm-12", answer));

        assertThat(capturedManifestPayload()).contains("clarification_answer").contains("104");
        String block = service.injectionBlock(USER_ID, CONVERSATION_ID, request("选这一批那道", "cm-12", answer));
        assertThat(block).contains("[Resolved Reference]").contains("题D");
    }

    @Test
    void injectionBlockContainsTitleAndStatementExcerpt() {
        stubProblems(List.of(
                row(1L, 101L, "题一", "m1", 1, 1),
                row(2L, 102L, "题二", "m1", 2, 2)
        ));
        stubFocus("m1", null);

        String block = service.injectionBlock(USER_ID, CONVERSATION_ID, request("讲一下第2题", "cm-9", null));

        assertThat(block).contains("[Resolved Reference]");
        assertThat(block).contains("题二");
        assertThat(block).contains("题面：题二");
        assertThat(block).contains("来源消息：m1");
        assertThat(block).contains("全会话第 2 题");
    }

    @Test
    void legacyNullOrdinalRowsProduceNoBlockAndNoWrites() {
        stubProblems(List.of(
                row(1L, 101L, "题一", null, null, null),
                row(2L, 102L, "题二", null, null, null)
        ));
        stubFocus(null, null);

        String block = service.injectionBlock(USER_ID, CONVERSATION_ID, request("讲一下第2题", "cm-9", null));
        service.processTurn(USER_ID, CONVERSATION_ID, request("讲一下第2题", "cm-9", null));

        assertThat(block).isBlank();
        verify(turnMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(clarificationRequestMapper, never()).insert(any(AiClarificationRequestEntity.class));
    }

    @Test
    void pendingClarificationAttachesToCompletion() {
        when(clarificationRequestMapper.selectOne(any())).thenReturn(storedAmbiguityRow());

        Optional<AiCompletion.Clarification> attachment = service.pendingClarificationAttachment(
                USER_ID, CONVERSATION_ID, request("第2题的思路是什么", "cm-11", null));

        assertThat(attachment).isPresent();
        assertThat(attachment.get().id()).isEqualTo("ref_resolve_cm-11");
        assertThat(attachment.get().options()).hasSize(2);
        assertThat(attachment.get().prompt()).contains("你指的是哪一道");
    }

    @Test
    void pendingClarificationSkippedWhenRequestCarriesAnswer() {
        Optional<AiCompletion.Clarification> attachment = service.pendingClarificationAttachment(
                USER_ID,
                CONVERSATION_ID,
                request("选第一个", "cm-12", new AiChatRequest.ClarificationAnswer(
                        "ref_resolve_cm-11", "你指的是哪一道？", "第一个", List.of(), null)));

        assertThat(attachment).isEmpty();
        verify(clarificationRequestMapper, never()).selectOne(any());
    }

    private void stubProblems(List<AiConversationProblemEntity> rows) {
        lenient().when(problemMapper.selectList(any())).thenReturn(rows);
    }

    private void stubFocus(String activeSetId, String lastSetId) {
        AiConversationTaskStateEntity state = new AiConversationTaskStateEntity();
        state.id = 100L;
        state.userId = USER_ID;
        state.conversationId = CONVERSATION_ID;
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (activeSetId != null) {
            map.put("activeProblemSetId", activeSetId);
        }
        if (lastSetId != null) {
            map.put("lastProblemSetId", lastSetId);
        }
        state.stateJson = merger.writeJson(map);
        lenient().when(taskStateMapper.selectOne(any())).thenReturn(state);
    }

    private UpdateWrapper<AiTurnEntity> capturedTurnUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<AiTurnEntity>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(turnMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    /** MyBatis-Plus stores .set() values as params; the manifest JSON lives there. */
    private String capturedManifestPayload() {
        return String.valueOf(capturedTurnUpdate().getParamNameValuePairs().values());
    }

    private AiTurnEntity turn(String id) {
        AiTurnEntity turn = new AiTurnEntity();
        turn.setId(id);
        turn.setConversationId(CONVERSATION_ID);
        turn.setStatus("BUILDING_CONTEXT");
        return turn;
    }

    private List<AiConversationProblemEntity> twoBatchProblems() {
        return List.of(
                row(1L, 101L, "题A", "m1", 1, 1),
                row(2L, 102L, "题B", "m1", 2, 2),
                row(3L, 103L, "题C", "m2", 1, 3),
                row(4L, 104L, "题D", "m2", 2, 4)
        );
    }

    private AiClarificationRequestEntity storedAmbiguityRow() {
        AiClarificationRequestEntity row = new AiClarificationRequestEntity();
        row.id = 500L;
        row.userId = USER_ID;
        row.conversationId = CONVERSATION_ID;
        row.requestKey = "ref_resolve_cm-11";
        row.status = "PENDING";
        row.question = "你提到的「第2题」在这次会话里对应多道题：1) 《题B》（上一批第 2 题）；2) 《题D》（这一批第 2 题）。你指的是哪一道？";
        row.inputSchema = """
                {"kind":"single_choice","options":[
                  {"type":"choice","label":"《题B》（上一批第 2 题）","message":"指 《题B》（上一批第 2 题）",
                   "candidate":{"rowId":2,"problemId":102,"title":"题B","setId":"m1","setOrdinal":2,"conversationOrdinal":2}},
                  {"type":"choice","label":"《题D》（这一批第 2 题）","message":"指 《题D》（这一批第 2 题）",
                   "candidate":{"rowId":4,"problemId":104,"title":"题D","setId":"m2","setOrdinal":2,"conversationOrdinal":4}}
                ]}
                """;
        row.createdAt = LocalDateTime.now();
        return row;
    }

    private AiChatRequest request(String message, String clientMessageId, AiChatRequest.ClarificationAnswer answer) {
        return new AiChatRequest(CONVERSATION_ID, null, message, "hint", null, null, answer, clientMessageId, null);
    }

    private AiConversationProblemEntity row(Long id, Long problemId, String title, String setId, Integer setOrdinal, Integer conversationOrdinal) {
        AiConversationProblemEntity row = new AiConversationProblemEntity();
        row.setId(id);
        row.setConversationId(CONVERSATION_ID);
        row.setUserId(USER_ID);
        row.setProblemId(problemId);
        row.setTitle(title);
        row.setStatementSnapshot("题面：" + title);
        row.setSetId(setId);
        row.setSetOrdinal(setOrdinal);
        row.setConversationOrdinal(conversationOrdinal);
        return row;
    }
}
