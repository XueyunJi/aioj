package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.contract.contest.RunningContestParticipation;
import com.aioj.next.contract.contest.RunningContestProblemStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProblemServiceClientTest {
    private static final Long USER_ID = 7L;
    private static final String PARTICIPATIONS_URL =
            "http://localhost:8202/api/v1/internal/ai/users/7/running-participations";
    private static final String STATEMENTS_URL =
            "http://localhost:8202/api/v1/internal/ai/users/7/running-contest-problem-statements";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OperationAuditWriter auditWriter = mock(OperationAuditWriter.class);

    private ProblemServiceClient client(RestClient.Builder builder, long cacheTtlMs) {
        InternalApiProperties internalApiProperties = new InternalApiProperties();
        internalApiProperties.setApiToken("test-token");
        return new ProblemServiceClient(objectMapper, internalApiProperties, auditWriter, cacheTtlMs, builder.build());
    }

    @Test
    void runningParticipationsServesCachedValueWithinTtl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 60_000L);
        server.expect(once(), requestTo(PARTICIPATIONS_URL))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":[{"contestId":501,"contestRunId":601,"startAt":"2026-01-01T00:00:00Z","endAt":"2026-01-01T02:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));

        List<RunningContestParticipation> first = client.runningParticipations(USER_ID);
        List<RunningContestParticipation> second = client.runningParticipations(USER_ID);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).contestRunId()).isEqualTo(601L);
        assertThat(second).isSameAs(first);
        server.verify();
    }

    @Test
    void runningParticipationsFallsBackToCachedValueOnFailure() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 1L);
        server.expect(once(), requestTo(PARTICIPATIONS_URL))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":[{"contestId":501,"contestRunId":601,"startAt":"2026-01-01T00:00:00Z","endAt":"2026-01-01T02:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        List<RunningContestParticipation> first = client.runningParticipations(USER_ID);
        assertThat(first).hasSize(1);

        Thread.sleep(5);
        server.reset();
        server.expect(once(), requestTo(PARTICIPATIONS_URL)).andRespond(withServerError());
        List<RunningContestParticipation> second = client.runningParticipations(USER_ID);

        assertThat(second).hasSize(1);
        assertThat(second.get(0).contestRunId()).isEqualTo(601L);
        verify(auditWriter, never()).record(eq("AI_CONTEST_GUARD_DEGRADED"), any(), any(), any(), any(), any(), any(), any(), any());
        server.verify();
    }

    @Test
    void runningParticipationsDegradesWithAuditWhenFetchFailsWithoutCache() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 60_000L);
        server.expect(once(), requestTo(PARTICIPATIONS_URL)).andRespond(withServerError());

        List<RunningContestParticipation> result = client.runningParticipations(USER_ID);

        assertThat(result).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_DEGRADED"), eq("CONTEST_AI_POLICY"), isNull(),
                eq("DEGRADED"), summaryCaptor.capture(), eq(USER_ID), isNull(), isNull(), eq(USER_ID));
        assertThat(summaryCaptor.getValue()).containsKeys("endpoint", "error");
        server.verify();
    }

    @Test
    void runningContestProblemStatementsServesCachedValueWithinTtl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 60_000L);
        server.expect(once(), requestTo(STATEMENTS_URL))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":[{"problemId":1001,"statement":"text","contestId":501,"contestRunId":601,"contestProblemId":701,"visibility":"PRIVATE","aiPolicyMode":"DEFAULT","aiPolicyNotes":null,"occurrences":[{"contestId":501,"contestRunId":601,"contestProblemId":701}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<RunningContestProblemStatement> first = client.runningContestProblemStatements(USER_ID);
        List<RunningContestProblemStatement> second = client.runningContestProblemStatements(USER_ID);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).problemId()).isEqualTo(1001L);
        assertThat(first.get(0).occurrences()).hasSize(1);
        assertThat(second).isSameAs(first);
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        server.verify();
    }

    @Test
    void runningParticipationsStrictThrowsWhenFetchFailsWithoutCache() {
        // Frozen Q5 (P3-6): the V3 policy layer must fail closed on a total miss —
        // an unverifiable contest user is never silently treated as unrestricted.
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 60_000L);
        server.expect(once(), requestTo(PARTICIPATIONS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.runningParticipationsStrict(USER_ID))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("fail-closed");
        // The degraded audit is still recorded before the throw.
        verify(auditWriter).record(eq("AI_CONTEST_GUARD_DEGRADED"), eq("CONTEST_AI_POLICY"), isNull(),
                eq("DEGRADED"), any(), eq(USER_ID), isNull(), isNull(), eq(USER_ID));
        server.verify();
    }

    @Test
    void runningParticipationsStrictStillServesStaleCacheOnFailure() throws Exception {
        // Stale-on-failure is preserved in strict mode: last-known-good policy keeps
        // guarding the turn; only a total miss fails closed.
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8202");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProblemServiceClient client = client(builder, 1L);
        server.expect(once(), requestTo(PARTICIPATIONS_URL))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","data":[{"contestId":501,"contestRunId":601,"startAt":"2026-01-01T00:00:00Z","endAt":"2026-01-01T02:00:00Z"}]}
                        """, MediaType.APPLICATION_JSON));
        List<RunningContestParticipation> first = client.runningParticipationsStrict(USER_ID);
        assertThat(first).hasSize(1);

        Thread.sleep(5);
        server.reset();
        server.expect(once(), requestTo(PARTICIPATIONS_URL)).andRespond(withServerError());
        List<RunningContestParticipation> second = client.runningParticipationsStrict(USER_ID);

        assertThat(second).hasSize(1);
        assertThat(second.get(0).contestRunId()).isEqualTo(601L);
        server.verify();
    }
}
