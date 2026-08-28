package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.AiMemoryService;
import com.aioj.next.ai.domain.ContestPostmortemAnalysisService;
import com.aioj.next.ai.domain.PlagiarismAnalysisService;
import com.aioj.next.ai.domain.StudentPostmortemAnalysisService;
import com.aioj.next.ai.domain.memory.AiJudgedSubmissionEventService;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiJudgedSubmissionEventControllerTest {
    @Test
    void judgedSubmissionEventDelegatesToEventService() {
        AiJudgedSubmissionEventService eventService = mock(AiJudgedSubmissionEventService.class);
        InternalAiController controller = new InternalAiController(
                mock(PlagiarismAnalysisService.class),
                mock(ContestPostmortemAnalysisService.class),
                mock(StudentPostmortemAnalysisService.class),
                mock(AiMemoryService.class),
                eventService
        );
        AiJudgedSubmissionEventRequest request = new AiJudgedSubmissionEventRequest(
                123L,
                99L,
                7L,
                SubmissionStatus.WRONG_ANSWER,
                "cpp",
                null,
                null,
                null,
                Instant.parse("2026-06-25T00:00:00Z")
        );

        controller.judgedSubmissionEvent(request);

        verify(eventService).recordJudgedSubmission(request);
    }
}
