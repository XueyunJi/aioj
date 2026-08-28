package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.contest.ContestExportFormat;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestParticipantType;
import com.aioj.next.contract.contest.ContestScoreboardCellResponse;
import com.aioj.next.contract.contest.ContestScoreboardCellStatus;
import com.aioj.next.contract.contest.ContestScoreboardProblemResponse;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
import com.aioj.next.contract.contest.ContestScoreboardRowResponse;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotKind;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestSubmissionResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestExportServiceTest {
    @Mock
    private ContestScoreboardService scoreboardService;
    @Mock
    private ContestSubmissionAccessService submissionAccessService;
    @Mock
    private ContestParticipantMapper participantMapper;

    private ContestExportService service;

    @BeforeEach
    void setUp() {
        service = new ContestExportService(scoreboardService, submissionAccessService, participantMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void teacherExportsPrivateScoreboardCsvWithBomAndFormulaEscaped() {
        authenticate(7L, Role.TEACHER);
        when(scoreboardService.scoreboard(301L, null, ContestScoreboardView.PRIVATE, 120_000L, null)).thenReturn(scoreboard());
        when(participantMapper.selectList(any())).thenReturn(List.of(participant()));

        var exported = service.exportScoreboard(301L, ContestExportFormat.CSV, ContestScoreboardView.PRIVATE, 120_000L, null);

        byte[] bytes = Base64.getDecoder().decode(exported.base64Content());
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, new byte[]{bytes[0], bytes[1], bytes[2]});
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals("text/csv;charset=UTF-8", exported.contentType());
        assertFalse(csv.contains("int main"));
        assertEquals(bytes.length, exported.byteSize());
        assertEquals(true, exported.fileName().startsWith("contest-301-scoreboard-private-"));
        assertEquals(true, csv.contains("\"2062426978519449601\""));
        assertEquals(true, csv.contains("\"'=alice\""));
        assertEquals(true, csv.contains("\"A.status\""));
        assertEquals(true, csv.contains("\"SOLVED\""));
    }

    @Test
    void adminExportsScoreboardXlsxWithTextIds() throws Exception {
        authenticate(99L, Role.ADMIN);
        when(scoreboardService.scoreboard(301L, null, ContestScoreboardView.PRIVATE, null, null)).thenReturn(scoreboard());
        when(participantMapper.selectList(any())).thenReturn(List.of(participant()));

        var exported = service.exportScoreboard(301L, ContestExportFormat.XLSX, ContestScoreboardView.PRIVATE, null, null);

        byte[] bytes = Base64.getDecoder().decode(exported.base64Content());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exported.contentType());
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("scoreboard");
            assertEquals("participantId", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("2062426978519449601", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("'=alice", sheet.getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void submissionsExportForwardsFiltersAndOmitsSourceDetails() {
        authenticate(7L, Role.TEACHER);
        when(participantMapper.selectList(any())).thenReturn(List.of(participant()));
        when(submissionAccessService.listSubmissions(301L, null, 1, 100, 401L, 601L, 91L,
                SubmissionStatus.ACCEPTED, "cpp"))
                .thenReturn(new PageResponse<>(List.of(submission()), 1, 1, 100));

        var exported = service.exportSubmissions(301L, ContestExportFormat.CSV, 401L, 601L, 91L,
                SubmissionStatus.ACCEPTED, "cpp");

        String csv = csv(exported);
        verify(submissionAccessService).listSubmissions(301L, null, 1, 100, 401L, 601L, 91L,
                SubmissionStatus.ACCEPTED, "cpp");
        assertEquals(true, csv.contains("\"submissionId\""));
        assertEquals(true, csv.contains("\"2060996478465212418\""));
        assertEquals(true, csv.contains("\"cpp\""));
        assertFalse(csv.contains("code"));
        assertFalse(csv.contains("stdout"));
        assertFalse(csv.contains("stderr"));
        assertFalse(csv.contains("exitStatus"));
        assertFalse(csv.contains("runTimeMillis"));
    }

    @Test
    void studentCannotExportPublicScoreboard() {
        authenticate(91L, Role.STUDENT);

        DomainException error = assertThrows(DomainException.class,
                () -> service.exportScoreboard(301L, ContestExportFormat.CSV, ContestScoreboardView.PUBLIC, null, null));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        verifyNoInteractions(scoreboardService, submissionAccessService, participantMapper);
    }

    private ContestScoreboardResponse scoreboard() {
        return new ContestScoreboardResponse(301L, null, ContestMode.ACM, ContestScoreboardView.PRIVATE, null,
                ContestScoreboardSnapshotKind.LIVE, 120_000L, Instant.parse("2026-06-10T09:02:00Z"),
                false, null, 20, false,
                List.of(new ContestScoreboardProblemResponse(401L, 1001L, "A", "Warmup", 0, 0)),
                List.of(new ContestScoreboardRowResponse(1, 2062426978519449601L, 91L, "=alice", "Alice",
                        1, 20, 1_200_000L, null, null,
                        List.of(new ContestScoreboardCellResponse(401L, ContestScoreboardCellStatus.SOLVED,
                                2, 1, 0, 1_200_000L, 20, null, null, null, null)))));
    }

    private ContestSubmissionResponse submission() {
        return new ContestSubmissionResponse(2060996478465212418L, 301L, null, 401L, 1001L, "A", "Warmup",
                601L, 91L, "alice", "Alice", "alice@example.com", "cpp", SubmissionStatus.ACCEPTED,
                "Accepted", 12L, 2048L, null, null, null, 1_800_000L, Instant.parse("2026-06-10T09:30:00Z"),
                Instant.parse("2026-06-10T09:31:00Z"), false, null, null, null, null, null);
    }

    private ContestParticipantEntity participant() {
        ContestParticipantEntity participant = new ContestParticipantEntity();
        participant.setId(601L);
        participant.setContestId(301L);
        participant.setUserId(91L);
        participant.setParticipantType(ContestParticipantType.INDIVIDUAL);
        participant.setAccountSnapshot("alice");
        participant.setDisplayNameSnapshot("Alice");
        participant.setGroupNameSnapshot("class01");
        return participant;
    }

    private String csv(com.aioj.next.contract.contest.ContestExportResponse exported) {
        byte[] bytes = Base64.getDecoder().decode(exported.base64Content());
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    private void authenticate(Long userId, Role role) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "user-" + userId, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
    }
}
