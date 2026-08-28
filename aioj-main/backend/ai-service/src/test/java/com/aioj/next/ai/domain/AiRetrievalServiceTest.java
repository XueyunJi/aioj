package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRetrievalServiceTest {
    @Mock
    private AiRetrievalChunkMapper chunkMapper;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private AiCapacityService aiCapacityService;

    private AiRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new AiRetrievalService(
                chunkMapper,
                aiProvider,
                aiCapacityService,
                new AiProperties(),
                new ObjectMapper()
        );
        lenient().when(aiCapacityService.call(any(), any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        lenient().when(aiProvider.embed(any())).thenReturn(Optional.empty());
    }

    @Test
    void embeddingCapacityRejectionIsCountedAndIndexingStillSucceeds() {
        AiFailureMetrics.reset();
        when(chunkMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        org.mockito.Mockito.doThrow(new DomainException(ErrorCode.TOO_MANY_REQUESTS, "busy"))
                .when(aiCapacityService).call(any(), any());

        service.indexChunk(7L, "message", "m-1", "这是一段正常的学习对话内容，用于触发索引流程。",
                AiRetrievalService.AiRetrievalChunkMetadata.safe());

        assertThat(AiFailureMetrics.embeddingCapacityRejections()).isEqualTo(1);
        verify(chunkMapper).insert(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void indexChunkStoresMetadataAndRemovesCodeRawOutputAndSecrets() {
        when(chunkMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        AiRetrievalService.AiRetrievalChunkMetadata metadata = AiRetrievalService.AiRetrievalChunkMetadata.of(
                99L,
                123L,
                1L,
                2L,
                3L,
                "binary_search",
                "wrong_answer_binary_search",
                Map.of("source", "submission_analysis", "codeText", "int main(){return 0;}")
        );

        service.indexChunk(7L, "submission_analysis", "123", """
                二分边界没有收缩到正确区间。
                codeText: int main(){return 0;}
                stdout:
                stdout secret
                ```cpp
                #include <bits/stdc++.h>
                int main(){return 0;}
                ```
                token=plain-secret-123
                """, metadata);

        ArgumentCaptor<AiRetrievalChunkEntity> captor = ArgumentCaptor.forClass(AiRetrievalChunkEntity.class);
        verify(chunkMapper).insert(captor.capture());
        AiRetrievalChunkEntity chunk = captor.getValue();

        assertThat(chunk.getProblemId()).isEqualTo(99L);
        assertThat(chunk.getSubmissionId()).isEqualTo(123L);
        assertThat(chunk.getContestRunId()).isEqualTo(2L);
        assertThat(chunk.getAlgorithmKey()).isEqualTo("binary_search");
        assertThat(chunk.getProfileKey()).isEqualTo("wrong_answer_binary_search");
        assertThat(chunk.getSensitivity()).isEqualTo(AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE);
        assertThat(chunk.getMetadataJson())
                .contains("submission_analysis")
                .doesNotContain("int main");
        assertThat(chunk.getChunkText())
                .contains("二分边界")
                .contains("codeText=[omitted]")
                .contains("[raw output omitted from retrieval]")
                .contains("[code block omitted from retrieval]")
                .doesNotContain("stdout secret")
                .doesNotContain("#include")
                .doesNotContain("plain-secret-123");
    }

    @Test
    void indexChunkSkipsNoPersistCodeAndCodeOnlyContent() {
        AiRetrievalService.AiRetrievalChunkMetadata noPersist = new AiRetrievalService.AiRetrievalChunkMetadata(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                AiRetrievalService.SENSITIVITY_NO_PERSIST_CODE,
                Map.of()
        );

        service.indexChunk(7L, "message", "1", "可以持久化的普通文本", noPersist);
        service.indexChunk(7L, "message", "2", """
                ```cpp
                int main(){return 0;}
                ```
                """);

        verify(chunkMapper, never()).insert(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void searchDetailedBoostsSelectedSubmissionProblemAlgorithmAndProfile() {
        when(chunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                chunk("message", "old", "泛泛而谈的调试建议", 99L, null, null, null, null, daysAgo(20), AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE),
                chunk("conversation_summary", "problem", "binary search answer 边界复盘", 99L, null, null, null, null, daysAgo(3), AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE),
                chunk("submission_analysis", "selected", "Wrong answer on case 2，check(mid) 单调性错误", 99L, 123L, "binary_search", "wrong_answer_binary_search", null, daysAgo(1), AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE),
                chunk("submission_analysis", "source", "int main(){return 0;}", 99L, 123L, "binary_search", "wrong_answer_binary_search", null, daysAgo(1), AiRetrievalService.SENSITIVITY_SOURCE_CODE)
        ));

        List<AiRetrievalService.AiRetrievalHit> hits = service.searchDetailed(
                7L,
                "binary search wrong answer",
                List.of("message", "conversation_summary", "submission_analysis"),
                5,
                new AiRetrievalService.AiRetrievalSearchContext(
                        99L,
                        123L,
                        Set.of("binary_search"),
                        Set.of("wrong_answer_binary_search")
                )
        );

        assertThat(hits).extracting("ownerId").containsExactly("selected", "problem", "old");
        assertThat(hits.get(0).reasons())
                .contains("same_submission", "same_problem", "algorithm_key_match", "profile_key_match", "source_priority:submission_analysis");
        assertThat(hits).noneSatisfy(hit -> assertThat(hit.content()).contains("int main"));
    }

    @Test
    void searchKeepsLegacyStringResultCompatible() {
        when(chunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                chunk("memory", "1", "长期记忆：weakness - binary search 边界容易漏", null, null, null, null, null, daysAgo(1), AiRetrievalService.SENSITIVITY_USER_PRIVATE_SAFE)
        ));

        List<String> hits = service.search(7L, "binary search", List.of("memory"), 5);

        assertThat(hits).containsExactly("长期记忆：weakness - binary search 边界容易漏");
    }

    private AiRetrievalChunkEntity chunk(
            String ownerType,
            String ownerId,
            String text,
            Long problemId,
            Long submissionId,
            String algorithmKey,
            String profileKey,
            String metadataJson,
            LocalDateTime updatedAt,
            String sensitivity
    ) {
        AiRetrievalChunkEntity chunk = new AiRetrievalChunkEntity();
        chunk.setUserId(7L);
        chunk.setOwnerType(ownerType);
        chunk.setOwnerId(ownerId);
        chunk.setChunkText(text);
        chunk.setProblemId(problemId);
        chunk.setSubmissionId(submissionId);
        chunk.setAlgorithmKey(algorithmKey);
        chunk.setProfileKey(profileKey);
        chunk.setMetadataJson(metadataJson);
        chunk.setSensitivity(sensitivity);
        chunk.setUpdatedAt(updatedAt);
        return chunk;
    }

    private LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }
}
