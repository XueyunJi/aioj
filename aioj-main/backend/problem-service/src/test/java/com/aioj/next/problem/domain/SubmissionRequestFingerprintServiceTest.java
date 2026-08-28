package com.aioj.next.problem.domain;

import com.aioj.next.problem.persistence.entity.SubmissionEntity;
import com.aioj.next.problem.persistence.entity.SubmissionRequestFingerprintEntity;
import com.aioj.next.problem.persistence.mapper.SubmissionRequestFingerprintMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SubmissionRequestFingerprintServiceTest {
    @Mock
    private SubmissionRequestFingerprintMapper mapper;

    @Test
    void recordStoresHashedPrivacyPreservingFingerprint() {
        SubmissionRequestFingerprintService service = new SubmissionRequestFingerprintService(mapper);
        SubmissionEntity submission = new SubmissionEntity();
        submission.setId(901L);
        submission.setContestId(11L);
        submission.setContestRunId(12L);
        submission.setContestParticipantId(13L);
        submission.setUserId(14L);

        service.record(submission, new SubmissionRequestMetadata("10.0.0.5:51234", "192.168.1.25, 10.0.0.5",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/148.0.0.0 Safari/537.36"),
                Instant.parse("2026-06-08T01:00:00Z"));

        ArgumentCaptor<SubmissionRequestFingerprintEntity> captor = ArgumentCaptor.forClass(SubmissionRequestFingerprintEntity.class);
        verify(mapper).insert(captor.capture());
        SubmissionRequestFingerprintEntity stored = captor.getValue();
        assertEquals(901L, stored.getSubmissionId());
        assertEquals(11L, stored.getContestId());
        assertEquals(12L, stored.getContestRunId());
        assertEquals(13L, stored.getContestParticipantId());
        assertEquals(14L, stored.getUserId());
        assertEquals("192.168.1.0/24", stored.getIpPrefix());
        assertEquals("Chrome / Windows", stored.getUserAgentSummary());
        assertNotNull(stored.getIpHash());
        assertNotNull(stored.getUserAgentHash());
        assertNotEquals("192.168.1.25", stored.getIpHash());
        assertNotEquals(stored.getUserAgentSummary(), stored.getUserAgentHash());
    }
}
