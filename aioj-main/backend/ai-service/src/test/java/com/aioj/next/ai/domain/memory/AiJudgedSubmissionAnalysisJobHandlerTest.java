package com.aioj.next.ai.domain.memory;

import com.aioj.next.ai.domain.AiLearningProfileService;
import com.aioj.next.ai.persistence.entity.AiMemoryJobEntity;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiJudgedSubmissionAnalysisJobHandlerTest {
    private final AiLearningProfileService learningProfileService = mock(AiLearningProfileService.class);
    private final AiJudgedSubmissionAnalysisJobHandler handler =
            new AiJudgedSubmissionAnalysisJobHandler(learningProfileService, new ObjectMapper());

    @Test
    void handleReadsSafePayloadAndDelegatesToLearningProfileService() {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {
                  "userId": 7,
                  "submissionId": 123,
                  "problemId": 99,
                  "status": "WRONG_ANSWER",
                  "language": "cpp",
                  "contestId": null,
                  "contestRunId": null,
                  "contestProblemId": null,
                  "judgedAt": "2026-06-25T00:00:00Z"
                }
                """);

        handler.handle(job);

        ArgumentCaptor<AiJudgedSubmissionEventRequest> captor =
                ArgumentCaptor.forClass(AiJudgedSubmissionEventRequest.class);
        verify(learningProfileService).recordJudgedSubmissionAnalysis(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(7L);
        assertThat(captor.getValue().submissionId()).isEqualTo(123L);
        assertThat(captor.getValue().problemId()).isEqualTo(99L);
        assertThat(captor.getValue().status()).isEqualTo(SubmissionStatus.WRONG_ANSWER);
    }

    @Test
    void queuedPayloadFailsPermanently() {
        AiMemoryJobEntity job = new AiMemoryJobEntity();
        job.setPayloadJson("""
                {"userId":7,"submissionId":123,"problemId":99,"status":"QUEUED"}
                """);

        assertThatThrownBy(() -> handler.handle(job))
                .isInstanceOf(AiMemoryJobPermanentFailure.class)
                .hasMessageContaining("not terminal");
    }
}
