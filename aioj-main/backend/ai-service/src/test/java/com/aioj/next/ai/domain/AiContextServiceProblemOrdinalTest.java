package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.context.AiContextReportBuilder;
import com.aioj.next.ai.domain.context.AiConversationContextV2Service;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.entity.AiConversationProblemEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiConversationProblemMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.contract.ai.AiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1.7 ordinal assignment on the ai_conversation_problems write path
 * (AiContextService.afterTurn -> upsertProblemContext): set_id = source user message id,
 * set_ordinal per set, conversation_ordinal per conversation, immutable on updates, and the
 * problem-set focus patch handed to the V2 task state.
 */
@ExtendWith(MockitoExtension.class)
class AiContextServiceProblemOrdinalTest {
    private static final Long USER_ID = 7L;
    private static final String CONVERSATION_ID = "c-ord";

    @Mock
    private AiConversationMapper conversationMapper;
    @Mock
    private AiMessageMapper messageMapper;
    @Mock
    private AiConversationProblemMapper problemMapper;
    @Mock
    private AiMemoryService memoryService;
    @Mock
    private AiRetrievalService retrievalService;
    @Mock
    private AiConversationContextV2Service contextV2Service;
    @Mock
    private AiProblemContextResolver problemContextResolver;
    @Mock
    private AiSubmissionContextResolver submissionContextResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiContextService service;

    @BeforeEach
    void setUp() {
        service = new AiContextService(
                conversationMapper,
                messageMapper,
                problemMapper,
                memoryService,
                retrievalService,
                contextV2Service,
                problemContextResolver,
                submissionContextResolver,
                new AiContextReportBuilder(),
                objectMapper
        );
        lenient().when(conversationMapper.updateById(any(AiConversationEntity.class))).thenReturn(1);
        lenient().when(problemMapper.selectCount(any())).thenReturn(0L);
        lenient().when(problemMapper.insert(any(AiConversationProblemEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiConversationProblemEntity.class).setId(900L);
            return 1;
        });
    }

    @Test
    void firstProblemGetsMessageSetAndOrdinals() {
        when(problemMapper.selectOne(any())).thenReturn(null);
        when(problemMapper.selectObjs(any())).thenReturn(Collections.singletonList(null));

        afterTurn(1001L, "两数之和", 555L);

        AiConversationProblemEntity inserted = capturedInsert();
        assertThat(inserted.getSetId()).isEqualTo("555");
        assertThat(inserted.getSetOrdinal()).isEqualTo(1);
        assertThat(inserted.getConversationOrdinal()).isEqualTo(1);
        verify(contextV2Service).patchProblemSetFocus(USER_ID, CONVERSATION_ID, "555");
    }

    @Test
    void laterMessageGetsNextConversationOrdinalAndOwnSet() {
        when(problemMapper.selectOne(any())).thenReturn(null);
        when(problemMapper.selectObjs(any())).thenReturn(List.of(3));

        afterTurn(1002L, "星港建设", 556L);

        AiConversationProblemEntity inserted = capturedInsert();
        assertThat(inserted.getSetId()).isEqualTo("556");
        assertThat(inserted.getSetOrdinal()).isEqualTo(1);
        assertThat(inserted.getConversationOrdinal()).isEqualTo(4);
        verify(contextV2Service).patchProblemSetFocus(USER_ID, CONVERSATION_ID, "556");
    }

    @Test
    void multipleProblemsFromOneMessageShareSetAndIncrementSetOrdinal() {
        when(problemMapper.selectOne(any())).thenReturn(null);
        when(problemMapper.selectObjs(any())).thenReturn(List.of(3));
        // One row of this message's set already registered: the next one takes set_ordinal 2.
        when(problemMapper.selectCount(any())).thenReturn(1L);

        afterTurn(1003L, "第二道题", 556L);

        AiConversationProblemEntity inserted = capturedInsert();
        assertThat(inserted.getSetId()).isEqualTo("556");
        assertThat(inserted.getSetOrdinal()).isEqualTo(2);
        assertThat(inserted.getConversationOrdinal()).isEqualTo(4);
    }

    @Test
    void updatingExistingRowNeverOverwritesOrdinals() {
        AiConversationProblemEntity existing = new AiConversationProblemEntity();
        existing.setId(42L);
        existing.setConversationId(CONVERSATION_ID);
        existing.setUserId(USER_ID);
        existing.setProblemId(1001L);
        existing.setSetId("m0");
        existing.setSetOrdinal(2);
        existing.setConversationOrdinal(2);
        when(problemMapper.selectOne(any())).thenReturn(existing);
        when(problemMapper.updateById(any(AiConversationProblemEntity.class))).thenReturn(1);

        afterTurn(1001L, "两数之和", 777L);

        ArgumentCaptor<AiConversationProblemEntity> captor = ArgumentCaptor.forClass(AiConversationProblemEntity.class);
        verify(problemMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSetId()).isEqualTo("m0");
        assertThat(captor.getValue().getSetOrdinal()).isEqualTo(2);
        assertThat(captor.getValue().getConversationOrdinal()).isEqualTo(2);
        verify(problemMapper, never()).insert(any(AiConversationProblemEntity.class));
        verify(problemMapper, never()).selectObjs(any());
        verify(contextV2Service).patchProblemSetFocus(USER_ID, CONVERSATION_ID, "m0");
    }

    @Test
    void legacyNullOrdinalRowUpdateSkipsFocusPatch() {
        AiConversationProblemEntity legacy = new AiConversationProblemEntity();
        legacy.setId(43L);
        legacy.setConversationId(CONVERSATION_ID);
        legacy.setUserId(USER_ID);
        legacy.setProblemId(1001L);
        when(problemMapper.selectOne(any())).thenReturn(legacy);
        when(problemMapper.updateById(any(AiConversationProblemEntity.class))).thenReturn(1);

        afterTurn(1001L, "两数之和", 778L);

        verify(problemMapper).updateById(any(AiConversationProblemEntity.class));
        verify(contextV2Service, never()).patchProblemSetFocus(any(), any(), any());
    }

    @Test
    void messageWithoutProblemSignalRegistersNothing() {
        AiChatRequest request = new AiChatRequest(
                CONVERSATION_ID, null, "今天天气怎么样", "hint", null, null, null, "cm-x", null);

        service.afterTurn(USER_ID, conversation(), request, completion(), context(), 555L, 556L);

        verify(problemMapper, never()).insert(any(AiConversationProblemEntity.class));
        verify(problemMapper, never()).updateById(any(AiConversationProblemEntity.class));
        verify(contextV2Service, never()).patchProblemSetFocus(any(), any(), any());
    }

    private void afterTurn(Long problemId, String title, Long userMessageId) {
        AiChatRequest.ProblemContext problem = new AiChatRequest.ProblemContext(
                String.valueOf(problemId), title, "medium", "题面：" + title, null, List.of("tag"), null, null, null);
        AiChatRequest request = new AiChatRequest(
                CONVERSATION_ID, problemId, "这题怎么做", "hint", problem, null, null, "cm-" + userMessageId, null);
        service.afterTurn(USER_ID, conversation(), request, completion(), context(), userMessageId, userMessageId + 1);
    }

    private AiConversationProblemEntity capturedInsert() {
        ArgumentCaptor<AiConversationProblemEntity> captor = ArgumentCaptor.forClass(AiConversationProblemEntity.class);
        verify(problemMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AiConversationEntity conversation() {
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        return conversation;
    }

    private AiCompletion completion() {
        return new AiCompletion("这是思路。", "mock", "mock", 0, 0);
    }

    private AiChatContext context() {
        return new AiChatContext("", "", "", "", "");
    }
}
