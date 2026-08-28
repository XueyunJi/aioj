package com.aioj.next.ai.agent.digest;

import com.aioj.next.ai.agent.memory.MemoryCandidateIngestionService;
import com.aioj.next.ai.agent.model.GatewayRequest;
import com.aioj.next.ai.agent.model.GatewayResponse;
import com.aioj.next.ai.agent.model.ModelGateway;
import com.aioj.next.ai.agent.profile.ProfileAggregateJobProducer;
import com.aioj.next.ai.agent.profile.ProfileSignalIngestionService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.AiModelEffectiveConfig;
import com.aioj.next.ai.domain.AiModelScope;
import com.aioj.next.ai.persistence.entity.AiAsyncJobEntity;
import com.aioj.next.ai.persistence.entity.AiTurnDigestEntity;
import com.aioj.next.ai.persistence.mapper.AiTurnDigestMapper;
import com.aioj.next.contract.ai.AiChatMessageResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnDigestCuratorHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiTurnDigestMapper digestMapper = mock(AiTurnDigestMapper.class);
    private final AiConversationService conversationService = mock(AiConversationService.class);
    private final ModelGateway modelGateway = mock(ModelGateway.class);
    private final com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService jobService =
            mock(com.aioj.next.ai.agent.asyncjob.AgentAsyncJobService.class);
    private final MemoryCandidateIngestionService memoryIngestion = mock(MemoryCandidateIngestionService.class);
    private final ProfileSignalIngestionService profileIngestion = mock(ProfileSignalIngestionService.class);
    private final ProfileAggregateJobProducer aggregateProducer = mock(ProfileAggregateJobProducer.class);
    private final TurnDigestCuratorHandler handler = new TurnDigestCuratorHandler(
            digestMapper, conversationService, modelGateway, jobService, objectMapper, new AiProperties(),
            memoryIngestion, profileIngestion, aggregateProducer);

    @BeforeEach
    void setUp() {
        lenient().when(modelGateway.configFor(AiModelScope.AGENT_CURATOR)).thenReturn(config());
        lenient().when(memoryIngestion.ingest(any(), any(), any(), anyList(), any(), any()))
                .thenReturn(new MemoryCandidateIngestionService.IngestResult(0, 0, 0, 0));
        lenient().when(profileIngestion.recordChatTurnSignals(any(), any(), anyList(), anyString())).thenReturn(0);
    }

    @Test
    void curatesStubIntoReadyDigestV2() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "讲一下第二道异或题"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "这题用异或前缀和……"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "dialogueAct": "NEW_REQUEST",
                  "userIntents": ["EXPLAIN_PROBLEM"],
                  "topicPath": ["algorithm", "xor"],
                  "summary": "用户要求讲解第二道异或题，助手给出异或前缀和解法",
                  "searchKeywords": ["异或", "前缀和", "第二题"],
                  "entities": [{"type": "PROBLEM", "canonicalName": "区间异或", "aliases": ["第二题"]}],
                  "userAssertions": [{"text": "用户不理解异或前缀和", "sourceMessageId": "user"}],
                  "assistantClaims": [{"text": "该题可用异或前缀和", "verification": "UNVERIFIED_ASSISTANT_CLAIM"}],
                  "openTasks": [],
                  "problemRefs": [],
                  "safetyTags": ["NORMAL_PRACTICE"],
                  "memoryCandidates": [{"text": "用户喜欢先给思路", "category": "PREFERENCE", "memoryKey": "guidance_preference", "confidence": 0.9, "longTerm": true, "evidenceType": "EXPLICIT_PREFERENCE"}],
                  "profileSignals": [{"signal": "对位运算不熟", "signalType": "WEAKNESS", "knowledgeNode": "位运算", "polarity": "NEGATIVE", "score": 0.7}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        ArgumentCaptor<AiTurnDigestEntity> captor = ArgumentCaptor.forClass(AiTurnDigestEntity.class);
        verify(digestMapper).insert(captor.capture());
        AiTurnDigestEntity ready = captor.getValue();
        assertThat(ready.getDigestVersion()).isEqualTo(2);
        assertThat(ready.getStatus()).isEqualTo(StubDigestFactory.STATUS_READY);
        assertThat(ready.getCuratorModel()).isEqualTo("deepseek-v4-pro");
        assertThat(ready.getCuratorPromptVersion()).isEqualTo(TurnDigestCuratorHandler.CURATOR_PROMPT_VERSION);
        assertThat(ready.getSourceHash()).isEqualTo("hash-stub");
        assertThat(ready.getSummary()).contains("异或");

        var root = objectMapper.readTree(ready.getStructuredDigest());
        // Deterministic stub fields survive the merge.
        assertThat(root.get("source").get("userMessageId").asText()).isEqualTo("100");
        assertThat(root.get("codeRefs").get(0).get("language").asText()).isEqualTo("cpp");
        assertThat(root.get("explicitSelection").get("text").asText()).contains("异或");
        // Curator semantics landed.
        assertThat(root.get("dialogueAct").asText()).isEqualTo("NEW_REQUEST");
        assertThat(root.get("topicPath").toString()).contains("xor");
        // P2-2: memory/profile outputs are kept in the digest; episodes still dropped.
        assertThat(root.get("memoryCandidates")).hasSize(1);
        assertThat(root.get("memoryCandidates").get(0).get("memoryKey").asText()).isEqualTo("guidance_preference");
        assertThat(root.get("profileSignals")).hasSize(1);
        assertThat(root.get("profileSignals").get(0).get("signalType").asText()).isEqualTo("WEAKNESS");
        assertThat(root.has("episodeBoundaryProposal")).isFalse();
        assertThat(ready.getSearchText()).contains("异或").contains("区间异或");
        // P1-6: the READY digest hands off to the dense lane.
        verify(jobService).enqueue(eq(TurnDigestService.JOB_TYPE_EMBED_DIGEST), any(String.class),
                any(String.class), eq(5));

        // P2-2: candidates/signals are handed to the ingestion sinks before READY persists.
        ArgumentCaptor<List> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryIngestion).ingest(eq(7L), eq("c-1"), eq(100L), candidatesCaptor.capture(),
                eq("讲一下第二道异或题"), eq("这题用异或前缀和……"));
        List<MemoryCandidateIngestionService.CandidateProposal> candidates = candidatesCaptor.getValue();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).text()).isEqualTo("用户喜欢先给思路");
        assertThat(candidates.get(0).category()).isEqualTo("PREFERENCE");
        assertThat(candidates.get(0).memoryKey()).isEqualTo("guidance_preference");
        assertThat(candidates.get(0).confidence()).isEqualTo(0.9);
        assertThat(candidates.get(0).longTerm()).isTrue();
        assertThat(candidates.get(0).evidenceType()).isEqualTo("EXPLICIT_PREFERENCE");

        ArgumentCaptor<List> signalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(profileIngestion).recordChatTurnSignals(eq(7L), eq("t-1"), signalsCaptor.capture(),
                eq(ProfileSignalIngestionService.SOURCE_TYPE_CHAT_TURN));
        List<ProfileSignalIngestionService.SignalProposal> signals = signalsCaptor.getValue();
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).signal()).isEqualTo("对位运算不熟");
        assertThat(signals.get(0).signalType()).isEqualTo("WEAKNESS");
        assertThat(signals.get(0).knowledgeNode()).isEqualTo("位运算");
        assertThat(signals.get(0).polarity()).isEqualTo("NEGATIVE");
        assertThat(signals.get(0).score()).isEqualTo(0.7);
    }

    @Test
    void keepsCuratorMemoryOutputsCappedAtEight() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        ObjectNode curated = objectMapper.createObjectNode();
        curated.put("summary", "摘要");
        ArrayNode candidateNodes = curated.putArray("memoryCandidates");
        for (int i = 0; i < 10; i++) {
            candidateNodes.addObject().put("text", "长期偏好" + i).put("category", "PREFERENCE");
        }
        ArrayNode signalNodes = curated.putArray("profileSignals");
        for (int i = 0; i < 9; i++) {
            signalNodes.addObject().put("signal", "观察" + i);
        }
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                curated.toString(), List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        ArgumentCaptor<AiTurnDigestEntity> digestCaptor = ArgumentCaptor.forClass(AiTurnDigestEntity.class);
        verify(digestMapper).insert(digestCaptor.capture());
        var root = objectMapper.readTree(digestCaptor.getValue().getStructuredDigest());
        assertThat(root.get("memoryCandidates")).hasSize(8);
        assertThat(root.get("profileSignals")).hasSize(8);

        ArgumentCaptor<List> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryIngestion).ingest(any(), any(), any(), candidatesCaptor.capture(), any(), any());
        assertThat(candidatesCaptor.getValue()).hasSize(8);
        ArgumentCaptor<List> signalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(profileIngestion).recordChatTurnSignals(any(), any(), signalsCaptor.capture(), anyString());
        assertThat(signalsCaptor.getValue()).hasSize(8);
    }

    @Test
    void toleratesMalformedCuratorOutputEntries() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "summary": "摘要",
                  "memoryCandidates": ["plain-string", {"noText": true}, {"text": "   "}, {"text": "有效偏好", "category": "RULE"}],
                  "profileSignals": [42, {"signal": ""}, {"signal": "有效观察"}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        ArgumentCaptor<List> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryIngestion).ingest(any(), any(), any(), candidatesCaptor.capture(), any(), any());
        List<MemoryCandidateIngestionService.CandidateProposal> candidates = candidatesCaptor.getValue();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).text()).isEqualTo("有效偏好");
        assertThat(candidates.get(0).category()).isEqualTo("RULE");
        assertThat(candidates.get(0).memoryKey()).isNull();
        assertThat(candidates.get(0).confidence()).isEqualTo(0.5);
        assertThat(candidates.get(0).longTerm()).isNull();
        assertThat(candidates.get(0).evidenceType()).isNull();

        ArgumentCaptor<List> signalsCaptor = ArgumentCaptor.forClass(List.class);
        verify(profileIngestion).recordChatTurnSignals(any(), any(), signalsCaptor.capture(), anyString());
        List<ProfileSignalIngestionService.SignalProposal> signals = signalsCaptor.getValue();
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).signal()).isEqualTo("有效观察");
        assertThat(signals.get(0).signalType()).isNull();
        assertThat(signals.get(0).score()).isEqualTo(0.5);
    }

    @Test
    void memorySinkFailurePropagatesAndSkipsReadyPersist() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "summary": "摘要",
                  "memoryCandidates": [{"text": "用户喜欢先给思路", "category": "PREFERENCE"}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));
        when(memoryIngestion.ingest(any(), any(), any(), anyList(), any(), any()))
                .thenThrow(new RuntimeException("candidate db down"));

        assertThatThrownBy(() -> handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("candidate db down");
        // READY never persisted, so the retry is not skipped by the no-op short circuit.
        verify(digestMapper, never()).insert(any(AiTurnDigestEntity.class));
        verify(profileIngestion, never()).recordChatTurnSignals(any(), any(), anyList(), anyString());
    }

    @Test
    void signalSinkFailurePropagatesAndSkipsReadyPersist() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "summary": "摘要",
                  "profileSignals": [{"signal": "对位运算不熟"}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));
        when(profileIngestion.recordChatTurnSignals(any(), any(), anyList(), anyString()))
                .thenThrow(new RuntimeException("signal db down"));

        assertThatThrownBy(() -> handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("signal db down");
        verify(digestMapper, never()).insert(any(AiTurnDigestEntity.class));
    }

    @Test
    void signalSinkTriggersProfileAggregateEnqueue() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "summary": "摘要",
                  "profileSignals": [{"signal": "对位运算不熟", "signalType": "WEAKNESS", "score": 0.7}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));
        when(profileIngestion.recordChatTurnSignals(any(), any(), anyList(), anyString())).thenReturn(1);

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        // P2-6: a non-empty signal batch schedules the PROFILE_AGGREGATE job for the user.
        verify(aggregateProducer).enqueueForUser(7L);
    }

    @Test
    void noSignalBatchSkipsProfileAggregateEnqueue() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class))).thenReturn(new GatewayResponse(
                """
                {
                  "summary": "摘要",
                  "memoryCandidates": [{"text": "用户喜欢先给思路", "category": "PREFERENCE"}]
                }
                """,
                List.of(), "stop", 500, 120, 0, "deepseek", "deepseek-v4-pro"));

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        verify(aggregateProducer, never()).enqueueForUser(any());
    }

    @Test
    void alreadyCuratedWithSamePromptIsNoop() throws Exception {
        AiTurnDigestEntity readyRow = stubRow();
        readyRow.setStatus(StubDigestFactory.STATUS_READY);
        readyRow.setDigestVersion(2);
        readyRow.setCuratorPromptVersion(TurnDigestCuratorHandler.CURATOR_PROMPT_VERSION);
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(readyRow));

        handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}"));

        verify(modelGateway, never()).call(any(), any());
        verify(digestMapper, never()).insert(any(AiTurnDigestEntity.class));
        verify(memoryIngestion, never()).ingest(any(), any(), any(), anyList(), any(), any());
        verify(profileIngestion, never()).recordChatTurnSignals(any(), any(), anyList(), anyString());
    }

    @Test
    void invalidCuratorJsonFailsRetryably() throws Exception {
        when(digestMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(stubRow()));
        when(conversationService.getMessage(7L, 100L)).thenReturn(message(100L, "u"));
        when(conversationService.getMessage(7L, 200L)).thenReturn(message(200L, "a"));
        when(modelGateway.call(any(), any(GatewayRequest.class)))
                .thenReturn(new GatewayResponse("not-json", List.of(), "stop", 1, 1, 0, "deepseek", "m"));

        assertThatThrownBy(() -> handler.handle(job("{\"turnId\":\"t-1\",\"conversationId\":\"c-1\",\"userId\":7}")))
                .isInstanceOf(IllegalStateException.class);
        verify(digestMapper, never()).insert(any(AiTurnDigestEntity.class));
    }

    private AiAsyncJobEntity job(String payload) {
        AiAsyncJobEntity job = new AiAsyncJobEntity();
        job.setId(1L);
        job.setJobType(TurnDigestService.JOB_TYPE_TURN_CURATE);
        job.setPayloadJson(payload);
        return job;
    }

    private AiTurnDigestEntity stubRow() throws Exception {
        AiTurnDigestEntity stub = new AiTurnDigestEntity();
        stub.setId(11L);
        stub.setTurnId("t-1");
        stub.setConversationId("c-1");
        stub.setUserId(7L);
        stub.setSummary("用户：讲一下第二道异或题");
        stub.setStructuredDigest(objectMapper.writeValueAsString(objectMapper.readTree("""
                {
                  "schemaVersion": 3, "turnId": "t-1", "conversationId": "c-1",
                  "summary": "用户：讲一下第二道异或题",
                  "searchKeywords": ["异或"],
                  "entities": [], "problemRefs": [], "submissionRefs": [],
                  "codeRefs": [{"role": "ASSISTANT_MESSAGE", "language": "cpp", "lineCount": 3, "firstLine": "int main()", "hash": "h"}],
                  "explicitSelection": {"text": "异或前缀和", "sourceMessageId": "88"},
                  "entryPoint": "CHAT",
                  "source": {"userMessageId": "100", "assistantMessageId": "200", "sourceHash": "hash-stub"}
                }
                """)));
        stub.setSearchText("异或 第二题");
        stub.setSourceHash("hash-stub");
        stub.setDigestVersion(1);
        stub.setStatus(StubDigestFactory.STATUS_STUB);
        stub.setTokenEstimate(120);
        stub.setCreatedAt(LocalDateTime.now());
        stub.setUpdatedAt(LocalDateTime.now());
        return stub;
    }

    private AiChatMessageResponse message(Long id, String content) {
        return new AiChatMessageResponse(id, "c-1", null, "user", content, null, java.time.Instant.now());
    }

    private AiModelEffectiveConfig config() {
        return new AiModelEffectiveConfig(AiModelScope.AGENT_CURATOR, true, false, "DATABASE",
                "deepseek", "https://api.deepseek.com/chat/completions", "sk-test", "sk-***", "environment",
                "DEEPSEEK_API_KEY", "deepseek-v4-pro", false, false, "high", 0.1, 4096, null, null, null);
    }
}
