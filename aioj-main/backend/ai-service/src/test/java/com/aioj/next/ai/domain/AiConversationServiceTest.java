package com.aioj.next.ai.domain;

import com.aioj.next.ai.domain.response.AiAssistantResponseNormalizer;
import com.aioj.next.ai.persistence.entity.AiConversationEntity;
import com.aioj.next.ai.persistence.mapper.AiConversationMapper;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.ai.AiConversationCreateRequest;
import com.aioj.next.contract.ai.AiConversationResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConversationServiceTest {
    private static final Long USER_ID = 7L;
    private static final Long PROBLEM_ID = 101L;

    @Test
    void createStoresSubmissionSourceReference() {
        Fixture fixture = new Fixture();
        when(fixture.conversationMapper.insert(any(AiConversationEntity.class))).thenReturn(1);
        when(fixture.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(fixture.messageMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        AiConversationResponse response = fixture.service.create(USER_ID, new AiConversationCreateRequest(
                PROBLEM_ID,
                "分析提交",
                "submission_analysis",
                "SUBMISSION",
                "2072331818178818049",
                "qa"
        ));

        ArgumentCaptor<AiConversationEntity> captor = ArgumentCaptor.forClass(AiConversationEntity.class);
        verify(fixture.conversationMapper).insert(captor.capture());
        AiConversationEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getProblemId()).isEqualTo(PROBLEM_ID);
        assertThat(saved.getSource()).isEqualTo("submission_analysis");
        assertThat(saved.getSourceRefType()).isEqualTo("SUBMISSION");
        assertThat(saved.getSourceRefId()).isEqualTo("2072331818178818049");

        assertThat(response.source()).isEqualTo("submission_analysis");
        assertThat(response.sourceRefType()).isEqualTo("SUBMISSION");
        assertThat(response.sourceRefId()).isEqualTo("2072331818178818049");
    }

    @Test
    void listFiltersBySubmissionSourceReference() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation(
                "c-submission",
                "submission_analysis",
                "SUBMISSION",
                "2072331818178818049"
        );
        when(fixture.conversationMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(fixture.conversationMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(conversation));
        when(fixture.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(fixture.messageMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        PageResponse<AiConversationResponse> page = fixture.service.list(
                USER_ID,
                1,
                20,
                PROBLEM_ID,
                "submission_analysis",
                "SUBMISSION",
                "2072331818178818049",
                null,
                false
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AiConversationEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(fixture.conversationMapper).selectCount(captor.capture());
        verify(fixture.conversationMapper).selectList(captor.capture());
        String countSql = captor.getAllValues().get(0).getTargetSql();
        String listSql = captor.getAllValues().get(1).getTargetSql();
        assertThat(countSql).contains("user_id", "problem_id", "source", "source_ref_type", "source_ref_id", "deleted_at");
        assertThat(listSql).contains("source_ref_type", "source_ref_id");
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).conversationId()).isEqualTo("c-submission");
        assertThat(page.records().get(0).sourceRefId()).isEqualTo("2072331818178818049");
    }

    @Test
    void createReusesExistingSubmissionConversationAndTouchesIt() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation(
                "c-existing",
                "submission_analysis",
                "SUBMISSION",
                "2072331818178818049"
        );
        LocalDateTime before = conversation.getUpdatedAt();
        when(fixture.conversationMapper.selectOne(any(QueryWrapper.class))).thenReturn(conversation);
        when(fixture.conversationMapper.updateById(any(AiConversationEntity.class))).thenReturn(1);
        when(fixture.messageMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(fixture.messageMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        AiConversationResponse response = fixture.service.create(USER_ID, new AiConversationCreateRequest(
                PROBLEM_ID,
                "分析提交",
                "submission_analysis",
                "SUBMISSION",
                "2072331818178818049",
                "qa"
        ));

        verify(fixture.conversationMapper, never()).insert(any(AiConversationEntity.class));
        verify(fixture.conversationMapper).updateById(conversation);
        assertThat(response.conversationId()).isEqualTo("c-existing");
        assertThat(conversation.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(conversation.getRecentProblemId()).isEqualTo(PROBLEM_ID);
    }

    @Test
    void bindContestContextBindsUnboundConversationAndFillsBlankProblem() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation("c-bind", "chat", null, null);
        conversation.setProblemId(null);
        conversation.setRecentProblemId(null);

        fixture.service.bindContestContext(conversation, 501L, 601L, PROBLEM_ID);

        assertThat(conversation.getContestId()).isEqualTo(501L);
        assertThat(conversation.getContestRunId()).isEqualTo(601L);
        assertThat(conversation.getProblemId()).isEqualTo(PROBLEM_ID);
        assertThat(conversation.getRecentProblemId()).isEqualTo(PROBLEM_ID);
        verify(fixture.conversationMapper).updateById(conversation);
    }

    @Test
    void bindContestContextSameBindingIsNoOp() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation("c-bound", "chat", null, null);
        conversation.setContestId(501L);
        conversation.setContestRunId(601L);

        fixture.service.bindContestContext(conversation, 501L, 601L, 202L);

        verify(fixture.conversationMapper, never()).updateById(any(AiConversationEntity.class));
    }

    @Test
    void bindContestContextRebindsWhenGuardAttributionChanges() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation("c-rebind", "chat", null, null);
        conversation.setContestId(501L);
        conversation.setContestRunId(601L);
        conversation.setProblemId(101L);
        conversation.setRecentProblemId(101L);

        fixture.service.bindContestContext(conversation, 501L, 602L, 202L);

        assertThat(conversation.getContestId()).isEqualTo(501L);
        assertThat(conversation.getContestRunId()).isEqualTo(602L);
        assertThat(conversation.getProblemId()).isEqualTo(202L);
        assertThat(conversation.getRecentProblemId()).isEqualTo(202L);
        verify(fixture.conversationMapper).updateById(conversation);
    }

    @Test
    void bindContestContextRebindKeepsProblemWhenTurnHasNone() {
        Fixture fixture = new Fixture();
        AiConversationEntity conversation = conversation("c-rebind-keep", "chat", null, null);
        conversation.setContestId(501L);
        conversation.setContestRunId(601L);
        conversation.setProblemId(101L);
        conversation.setRecentProblemId(101L);

        fixture.service.bindContestContext(conversation, 501L, 602L, null);

        assertThat(conversation.getContestRunId()).isEqualTo(602L);
        assertThat(conversation.getProblemId()).isEqualTo(101L);
        assertThat(conversation.getRecentProblemId()).isEqualTo(101L);
        verify(fixture.conversationMapper).updateById(conversation);
    }

    private static AiConversationEntity conversation(String id, String source, String sourceRefType, String sourceRefId) {
        LocalDateTime now = LocalDateTime.now();
        AiConversationEntity conversation = new AiConversationEntity();
        conversation.setId(id);
        conversation.setUserId(USER_ID);
        conversation.setProblemId(PROBLEM_ID);
        conversation.setTitle("提交分析");
        conversation.setSource(source);
        conversation.setSourceRefType(sourceRefType);
        conversation.setSourceRefId(sourceRefId);
        conversation.setMode("qa");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        return conversation;
    }

    private static final class Fixture {
        private final AiConversationMapper conversationMapper = mock(AiConversationMapper.class);
        private final AiMessageMapper messageMapper = mock(AiMessageMapper.class);
        private final AiConversationService service = new AiConversationService(
                conversationMapper,
                messageMapper,
                new AiAssistantResponseNormalizer(new ObjectMapper(), new com.aioj.next.ai.domain.clarification.ClarificationSchemaRepairer())
        );
    }
}
