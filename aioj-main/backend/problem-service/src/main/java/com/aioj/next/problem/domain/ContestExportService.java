package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.contest.ContestExportFormat;
import com.aioj.next.contract.contest.ContestExportResponse;
import com.aioj.next.contract.contest.ContestMode;
import com.aioj.next.contract.contest.ContestScoreboardCellResponse;
import com.aioj.next.contract.contest.ContestScoreboardProblemResponse;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
import com.aioj.next.contract.contest.ContestScoreboardRowResponse;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestSubmissionResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.persistence.entity.ContestParticipantEntity;
import com.aioj.next.problem.persistence.mapper.ContestParticipantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ContestExportService {
    private static final int EXPORT_PAGE_SIZE = 100;
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final ContestScoreboardService contestScoreboardService;
    private final ContestSubmissionAccessService contestSubmissionAccessService;
    private final ContestParticipantMapper contestParticipantMapper;

    public ContestExportService(ContestScoreboardService contestScoreboardService,
                                ContestSubmissionAccessService contestSubmissionAccessService,
                                ContestParticipantMapper contestParticipantMapper) {
        this.contestScoreboardService = contestScoreboardService;
        this.contestSubmissionAccessService = contestSubmissionAccessService;
        this.contestParticipantMapper = contestParticipantMapper;
    }

    public ContestExportResponse exportScoreboard(Long contestId, ContestExportFormat format, Long runId,
                                                  ContestScoreboardView view, Long atMillis, Long snapshotId) {
        assertStaff();
        ContestExportFormat effectiveFormat = format == null ? ContestExportFormat.CSV : format;
        ContestScoreboardView effectiveView = view == null ? ContestScoreboardView.PUBLIC : view;
        ContestScoreboardResponse scoreboard = contestScoreboardService.scoreboard(contestId, runId, effectiveView, atMillis, snapshotId);
        Map<Long, ContestParticipantEntity> participants = participantLookup(contestId, runId);
        List<List<String>> rows = scoreboardRows(scoreboard, participants);
        String runPart = runId == null ? "" : "-run-" + runId;
        String fileName = "contest-" + contestId + runPart + "-scoreboard-" + scoreboard.view().name().toLowerCase(Locale.ROOT)
                + "-" + FILE_TIME_FORMATTER.format(Instant.now()) + extension(effectiveFormat);
        return export(fileName, effectiveFormat, "scoreboard", rows);
    }

    public ContestExportResponse exportScoreboard(Long contestId, ContestExportFormat format,
                                                  ContestScoreboardView view, Long atMillis, Long snapshotId) {
        return exportScoreboard(contestId, format, null, view, atMillis, snapshotId);
    }

    public ContestExportResponse exportSubmissions(Long contestId, ContestExportFormat format, Long runId,
                                                   Long contestProblemId, Long participantId, Long userId,
                                                   SubmissionStatus status, String language) {
        assertStaff();
        ContestExportFormat effectiveFormat = format == null ? ContestExportFormat.CSV : format;
        Map<Long, ContestParticipantEntity> participants = participantLookup(contestId, runId);
        List<ContestSubmissionResponse> submissions = new ArrayList<>();
        long page = 1;
        while (true) {
            PageResponse<ContestSubmissionResponse> result = contestSubmissionAccessService.listSubmissions(contestId, runId, page,
                    EXPORT_PAGE_SIZE, contestProblemId, participantId, userId, status, language);
            submissions.addAll(result.records());
            if (result.records().size() < EXPORT_PAGE_SIZE || submissions.size() >= result.total()) {
                break;
            }
            page++;
        }
        List<List<String>> rows = submissionRows(submissions, participants);
        String runPart = runId == null ? "" : "-run-" + runId;
        String fileName = "contest-" + contestId + runPart + "-submissions-" + FILE_TIME_FORMATTER.format(Instant.now())
                + extension(effectiveFormat);
        return export(fileName, effectiveFormat, "submissions", rows);
    }

    public ContestExportResponse exportSubmissions(Long contestId, ContestExportFormat format,
                                                   Long contestProblemId, Long participantId, Long userId,
                                                   SubmissionStatus status, String language) {
        return exportSubmissions(contestId, format, null, contestProblemId, participantId, userId, status, language);
    }

    private void assertStaff() {
        if (!SecuritySupport.hasRole(Role.TEACHER) && !SecuritySupport.hasRole(Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only teachers and admins can export contest data");
        }
    }

    private Map<Long, ContestParticipantEntity> participantLookup(Long contestId, Long runId) {
        Map<Long, ContestParticipantEntity> result = new HashMap<>();
        for (ContestParticipantEntity participant : contestParticipantMapper.selectList(new LambdaQueryWrapper<ContestParticipantEntity>()
                .eq(ContestParticipantEntity::getContestId, contestId)
                .eq(runId != null, ContestParticipantEntity::getContestRunId, runId))) {
            result.put(participant.getId(), participant);
        }
        return result;
    }

    private List<List<String>> scoreboardRows(ContestScoreboardResponse scoreboard,
                                              Map<Long, ContestParticipantEntity> participants) {
        boolean ioi = scoreboard.mode() == ContestMode.IOI;
        List<String> header = new ArrayList<>(List.of(
                "rank",
                "participantId",
                "participantType",
                "userId",
                "accountSnapshot",
                "displayNameSnapshot",
                "groupNameSnapshot",
                ioi ? "fullScoreProblems" : "solved",
                ioi ? "totalScore" : "penalty",
                ioi ? "lastScoreImprovedAtMillis" : "lastAcceptedAtMillis"
        ));
        for (ContestScoreboardProblemResponse problem : scoreboard.problems()) {
            String label = problem.label();
            header.add(label + ".status");
            header.add(label + ".attempts");
            header.add(label + ".wrongAttempts");
            header.add(label + ".pendingAttempts");
            if (ioi) {
                header.add(label + ".score");
                header.add(label + ".maxScore");
                header.add(label + ".bestSubmissionId");
                header.add(label + ".lastScoreImprovedAtMillis");
            } else {
                header.add(label + ".acceptedAtMillis");
                header.add(label + ".penaltyMinutes");
            }
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        for (ContestScoreboardRowResponse row : scoreboard.rows()) {
            ContestParticipantEntity participant = participants.get(row.participantId());
            List<String> values = row(
                    string(row.rank()),
                    string(row.participantId()),
                    participant == null || participant.getParticipantType() == null ? "" : participant.getParticipantType().name(),
                    string(row.userId()),
                    row.accountSnapshot(),
                    row.displayNameSnapshot(),
                    participant == null ? "" : participant.getGroupNameSnapshot(),
                    string(row.solvedCount()),
                    ioi ? string(row.totalScore()) : string(row.penaltyMinutes()),
                    ioi ? string(row.lastScoreImprovedAtMillis()) : string(row.lastAcceptedAtMillis())
            );
            for (ContestScoreboardCellResponse cell : row.cells()) {
                values.add(cell.status() == null ? "" : cell.status().name());
                values.add(string(cell.attempts()));
                values.add(string(cell.wrongAttempts()));
                values.add(string(cell.pendingAttempts()));
                if (ioi) {
                    values.add(string(cell.score()));
                    values.add(string(cell.maxScore()));
                    values.add(string(cell.bestSubmissionId()));
                    values.add(string(cell.lastScoreImprovedAtMillis()));
                } else {
                    values.add(string(cell.acceptedAtMillis()));
                    values.add(string(cell.penaltyMinutes()));
                }
            }
            rows.add(values);
        }
        return rows;
    }

    private List<List<String>> submissionRows(List<ContestSubmissionResponse> submissions,
                                              Map<Long, ContestParticipantEntity> participants) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(
                "submissionId",
                "participantId",
                "participantType",
                "userId",
                "accountSnapshot",
                "displayNameSnapshot",
                "groupNameSnapshot",
                "problemLabel",
                "problemTitle",
                "language",
                "status",
                "judgeMessage",
                "timeMillis",
                "memoryKb",
                "score",
                "maxScore",
                "submittedAtContestMillis",
                "createdAt",
                "judgedAt"
        ));
        for (ContestSubmissionResponse submission : submissions) {
            ContestParticipantEntity participant = participants.get(submission.contestParticipantId());
            rows.add(row(
                    string(submission.id()),
                    string(submission.contestParticipantId()),
                    participant == null || participant.getParticipantType() == null ? "" : participant.getParticipantType().name(),
                    string(submission.userId()),
                    submission.accountSnapshot(),
                    submission.displayNameSnapshot(),
                    participant == null ? "" : participant.getGroupNameSnapshot(),
                    submission.problemLabel(),
                    submission.problemTitle(),
                    submission.language(),
                    submission.status() == null ? "" : submission.status().name(),
                    submission.judgeMessage(),
                    string(submission.timeMillis()),
                    string(submission.memoryKb()),
                    string(submission.score()),
                    string(submission.maxScore()),
                    string(submission.submittedAtContestMillis()),
                    string(submission.createdAt()),
                    string(submission.judgedAt())
            ));
        }
        return rows;
    }

    private ContestExportResponse export(String fileName, ContestExportFormat format, String sheetName,
                                         List<List<String>> rows) {
        byte[] content = switch (format) {
            case CSV -> csv(rows);
            case XLSX -> xlsx(sheetName, rows);
        };
        return new ContestExportResponse(fileName, contentType(format),
                Base64.getEncoder().encodeToString(content), content.length);
    }

    private byte[] csv(List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        for (List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(csvCell(row.get(index)));
            }
            builder.append("\r\n");
        }
        byte[] body = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private byte[] xlsx(String sheetName, List<List<String>> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(safeSheetName(sheetName));
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row sheetRow = sheet.createRow(rowIndex);
                List<String> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    Cell cell = sheetRow.createCell(columnIndex);
                    cell.setCellStyle(textStyle);
                    cell.setCellValue(sanitizeSpreadsheetValue(values.get(columnIndex)));
                }
            }
            if (!rows.isEmpty()) {
                for (int columnIndex = 0; columnIndex < Math.min(rows.get(0).size(), 40); columnIndex++) {
                    sheet.autoSizeColumn(columnIndex);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Failed to export contest data");
        }
    }

    private String csvCell(String value) {
        String safe = sanitizeSpreadsheetValue(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String sanitizeSpreadsheetValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') {
            return "'" + value;
        }
        return value;
    }

    private String safeSheetName(String sheetName) {
        String safe = sheetName == null || sheetName.isBlank() ? "export" : sheetName.replaceAll("[\\\\/?*\\[\\]:]", "-");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private String contentType(ContestExportFormat format) {
        return switch (format) {
            case CSV -> "text/csv;charset=UTF-8";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    private String extension(ContestExportFormat format) {
        return switch (format) {
            case CSV -> ".csv";
            case XLSX -> ".xlsx";
        };
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> row(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
