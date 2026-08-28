package com.aioj.next.ai.agent.digest;

import com.aioj.next.ai.domain.AiModelConfigService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.domain.OpenAiCompatibleProvider;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiRetrievalChunkEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiRetrievalChunkMapper;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnDigestEmbedHandlerTest {

    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final AiRetrievalChunkMapper chunkMapper = mock(AiRetrievalChunkMapper.class);
    private final OpenAiCompatibleProvider provider = mock(OpenAiCompatibleProvider.class);
    private final AiModelConfigService configService = mock(AiModelConfigService.class);
    private final TurnDigestEmbedHandler handler = new TurnDigestEmbedHandler(
            digestMapper, chunkMapper, provider, configService, new ObjectMapper());

    @Test
    void readyDigestIsEmbeddedIntoChunkPlane() throws Exception {
        when(digestMapper.selectById(11L)).thenReturn(digest("READY"));
        when(configService.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config(true, "sk-test"));
        when(provider.embed(anyString())).thenReturn(Optional.of(List.of(0.5, 0.5)));

        handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}"));

        ArgumentCaptor<AiRetrievalChunkEntity> captor = ArgumentCaptor.forClass(AiRetrievalChunkEntity.class);
        verify(chunkMapper).insert(captor.capture());
        AiRetrievalChunkEntity chunk = captor.getValue();
        assertThat(chunk.getOwnerType()).isEqualTo("TURN_DIGEST");
        assertThat(chunk.getOwnerId()).isEqualTo("11");
        assertThat(chunk.getUserId()).isEqualTo(7L);
        assertThat(chunk.getChunkText()).contains("异或");
        assertThat(chunk.getEmbeddingJson()).isEqualTo("[0.5,0.5]");
        assertThat(chunk.getEmbeddingDimension()).isEqualTo(2);
        assertThat(chunk.getEmbeddingModel()).isEqualTo("text-embedding-v3");
        assertThat(chunk.getMetadataJson()).contains("\"turnId\":\"t-1\"");
        assertThat(chunk.getTextHash()).isNotBlank();
    }

    @Test
    void identicalTextAlreadyEmbeddedIsNoop() throws Exception {
        when(digestMapper.selectById(11L)).thenReturn(digest("READY"));
        when(configService.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config(true, "sk-test"));
        when(provider.embed(anyString())).thenReturn(Optional.of(List.of(0.5, 0.5)));

        // First run inserts; second run sees the same textHash and does nothing.
        handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}"));
        ArgumentCaptor<AiRetrievalChunkEntity> captor = ArgumentCaptor.forClass(AiRetrievalChunkEntity.class);
        verify(chunkMapper).insert(captor.capture());
        when(chunkMapper.selectOne(any(QueryWrapper.class))).thenReturn(captor.getValue());

        handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}"));

        verify(chunkMapper).insert(any(AiRetrievalChunkEntity.class)); // still exactly one insert
        verify(chunkMapper, never()).updateById(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void disabledEmbeddingConfigCompletesSilently() throws Exception {
        when(digestMapper.selectById(11L)).thenReturn(digest("READY"));
        when(configService.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config(false, ""));

        handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}"));

        verify(provider, never()).embed(anyString());
        verify(chunkMapper, never()).insert(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void emptyProviderVectorRetriesViaThrow() {
        when(digestMapper.selectById(11L)).thenReturn(digest("READY"));
        when(configService.effectiveConfig(AiModelScope.EMBEDDING)).thenReturn(config(true, "sk-test"));
        when(provider.embed(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}")))
                .isInstanceOf(IllegalStateException.class);
        verify(chunkMapper, never()).insert(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void stubDigestIsSkipped() throws Exception {
        when(digestMapper.selectById(11L)).thenReturn(digest("STUB"));

        handler.handle(job("{\"turnId\":\"t-1\",\"userId\":7,\"digestId\":11}"));

        verify(provider, never()).embed(anyString());
        verify(chunkMapper, never()).insert(any(AiRetrievalChunkEntity.class));
    }

    @Test
    void ownershipMismatchFails() {
        when(digestMapper.selectById(11L)).thenReturn(digest("READY"));

        assertThatThrownBy(() -> handler.handle(job("{\"turnId\":\"t-1\",\"userId\":999,\"digestId\":11}")))
                .isInstanceOf(IllegalStateException.class);
    }

    private AiAsyncJobEntity job(String payload) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(2L);
        job.setJobType(TurnDigestService.JOB_TYPE_EMBED_DIGEST);
        job.setPayloadJson(payload);
        return job;
    }

    private AiTurnDigestEntity digest(String status) {
        AiTurnDigestEntity digest = new AiTurnDigestEntity();
        digest.setId(11L);
        digest.setTurnId("t-1");
        digest.setConversationId("c-1");
        digest.setUserId(7L);
        digest.setSummary("用户要求讲解第二道异或题");
        digest.setSearchText("异或 前缀和 第二题");
        digest.setStatus(status);
        digest.setCreatedAt(LocalDateTime.now());
        digest.setUpdatedAt(LocalDateTime.now());
        return digest;
    }

    private AiModelEffectiveConfig config(boolean enabled, String apiKey) {
        return new AiModelEffectiveConfig(AiModelScope.EMBEDDING, enabled, false, "DATABASE",
                "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey, "sk-***", "environment",
                "DASHSCOPE_API_KEY", "text-embedding-v3", false, false, null, null, null, 1024, null, null);
    }
}
