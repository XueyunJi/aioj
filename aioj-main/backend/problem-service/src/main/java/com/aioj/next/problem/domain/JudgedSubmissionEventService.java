package com.aioj.next.problem.domain;

import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiJudgedSubmissionEventRequest;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.contract.tutor.TutorJudgeEventRequest;
import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionMapper;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JudgedSubmissionEventService {
    private final SubmissionMapper submissionMapper;
    private final JudgedSubmissionAiEventClient aiEventClient;
    private final TutorJudgeEventClient tutorEventClient;

    public JudgedSubmissionEventService(SubmissionMapper submissionMapper,
                                        JudgedSubmissionAiEventClient aiEventClient) {
        this(submissionMapper, aiEventClient, null);
    }

    @Autowired
    public JudgedSubmissionEventService(SubmissionMapper submissionMapper,
                                        JudgedSubmissionAiEventClient aiEventClient,
                                        TutorJudgeEventClient tutorEventClient) {
        this.submissionMapper = submissionMapper;
        this.aiEventClient = aiEventClient;
        this.tutorEventClient = tutorEventClient;
    }

    public void handle(AiJudgedSubmissionEventRequest request) {
        if (request == null || request.submissionId() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission id is required");
        }
        SubmissionEntity submission = submissionMapper.selectById(request.submissionId());
        if (submission == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Submission not found");
        }
        if (submission.getStatus() == SubmissionStatus.QUEUED || submission.getStatus() == SubmissionStatus.RUNNING) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Submission is still being judged");
        }
        AiJudgedSubmissionEventRequest aiEvent = new AiJudgedSubmissionEventRequest(
                submission.getId(),
                submission.getProblemId(),
                submission.getUserId(),
                submission.getStatus(),
                submission.getLanguage(),
                submission.getContestId(),
                submission.getContestRunId(),
                submission.getContestProblemId(),
                submission.getJudgedAt()
        );
        aiEventClient.notifyJudgedSubmission(aiEvent);
        if (tutorEventClient != null) {
            tutorEventClient.notifyJudgeEvent(toTutorEvent(submission));
        }
    }

    private TutorJudgeEventRequest toTutorEvent(SubmissionEntity submission) {
        String status = submission.getStatus().name();
        String evidence = status.equals("ACCEPTED") ? "correct"
                : status.equals("WRONG_ANSWER") ? "incorrect" : "none";
        String eventId = "submission.judged:" + submission.getId() + ":"
                + status.toLowerCase(Locale.ROOT) + ":" + String.valueOf(submission.getJudgedAt());
        return new TutorJudgeEventRequest(
                eventId,
                TutorJudgeEventRequest.EVENT_TYPE,
                String.valueOf(submission.getId()),
                String.valueOf(submission.getUserId()),
                String.valueOf(submission.getProblemId()),
                status,
                evidence,
                submission.getScore(),
                submission.getJudgedAt(),
                TutorJudgeEventRequest.SCHEMA_VERSION
        );
    }
}
