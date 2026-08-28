package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.contract.contest.ContestCreateRequest;
import com.aioj.next.contract.contest.ContestAnnouncementRequest;
import com.aioj.next.contract.contest.ContestAnnouncementResponse;
import com.aioj.next.contract.contest.ContestClarificationCreateRequest;
import com.aioj.next.contract.contest.ContestClarificationReplyRequest;
import com.aioj.next.contract.contest.ContestClarificationResponse;
import com.aioj.next.contract.contest.ContestClarificationStatus;
import com.aioj.next.contract.contest.ContestClarificationVisibility;
import com.aioj.next.contract.contest.ContestExportFormat;
import com.aioj.next.contract.contest.ContestExportResponse;
import com.aioj.next.contract.contest.ContestInvitationBatchRequest;
import com.aioj.next.contract.contest.ContestInvitationBatchResponse;
import com.aioj.next.contract.contest.ContestOpenRunResponse;
import com.aioj.next.contract.contest.ContestParticipantAddRequest;
import com.aioj.next.contract.contest.ContestParticipantResponse;
import com.aioj.next.contract.contest.ContestPlagiarismGraphResponse;
import com.aioj.next.contract.contest.ContestPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestProblemBatchUpdateRequest;
import com.aioj.next.contract.contest.ContestProblemResponse;
import com.aioj.next.contract.contest.ContestRegistrationResponse;
import com.aioj.next.contract.contest.ContestRegistrationStatus;
import com.aioj.next.contract.contest.ContestResolverSessionCreateRequest;
import com.aioj.next.contract.contest.ContestResolverSessionDetailResponse;
import com.aioj.next.contract.contest.ContestResolverSessionResponse;
import com.aioj.next.contract.contest.ContestRunCreateRequest;
import com.aioj.next.contract.contest.ContestRunListPurpose;
import com.aioj.next.contract.contest.ContestRunProblemSnapshotResponse;
import com.aioj.next.contract.contest.ContestRunResponse;
import com.aioj.next.contract.contest.ContestRunStatus;
import com.aioj.next.contract.contest.ContestRunUpdateRequest;
import com.aioj.next.contract.contest.ContestResponse;
import com.aioj.next.contract.contest.ContestScoreboardResponse;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotCreateRequest;
import com.aioj.next.contract.contest.ContestScoreboardSnapshotResponse;
import com.aioj.next.contract.contest.ContestScoreboardTimelineResponse;
import com.aioj.next.contract.contest.ContestScoreboardView;
import com.aioj.next.contract.contest.ContestStatus;
import com.aioj.next.contract.contest.ContestStudentPostmortemReportResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemOperationJobResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemSummaryResponse;
import com.aioj.next.contract.contest.ContestStudentPostmortemWeaknessCandidateResponse;
import com.aioj.next.contract.contest.ContestSubmissionCodeResponse;
import com.aioj.next.contract.contest.ContestSubmissionResponse;
import com.aioj.next.contract.contest.ContestUpdateRequest;
import com.aioj.next.contract.contest.FairnessAlertResponse;
import com.aioj.next.contract.contest.FairnessAlertSeverity;
import com.aioj.next.contract.contest.FairnessAlertStatus;
import com.aioj.next.contract.contest.FairnessAlertType;
import com.aioj.next.contract.contest.FairnessAlertUpdateRequest;
import com.aioj.next.contract.contest.PlagiarismJobCreateRequest;
import com.aioj.next.contract.contest.PlagiarismJobResponse;
import com.aioj.next.contract.contest.PlagiarismPairDetailResponse;
import com.aioj.next.contract.contest.PlagiarismPairResponse;
import com.aioj.next.contract.contest.PlagiarismPairUpdateRequest;
import com.aioj.next.contract.contest.PlagiarismReviewStatus;
import com.aioj.next.contract.contest.PlagiarismRiskLevel;
import com.aioj.next.contract.contest.StudentPostmortemBatchJobRequest;
import com.aioj.next.contract.contest.SubmissionCodeAccessLogResponse;
import com.aioj.next.contract.contest.SubmissionCodeAccessRequest;
import com.aioj.next.contract.operation.OperationJobResponse;
import com.aioj.next.contract.submission.SubmissionStatus;
import com.aioj.next.problem.domain.ContestExportService;
import com.aioj.next.problem.domain.ContestCommunicationService;
import com.aioj.next.problem.domain.ContestFairnessAlertService;
import com.aioj.next.problem.domain.ContestPlagiarismGraphService;
import com.aioj.next.problem.domain.ContestPostmortemService;
import com.aioj.next.problem.domain.ContestResolverService;
import com.aioj.next.problem.domain.ContestRunService;
import com.aioj.next.problem.domain.ContestScoreboardService;
import com.aioj.next.problem.domain.ContestService;
import com.aioj.next.problem.domain.ContestSubmissionAccessService;
import com.aioj.next.problem.domain.OperationJobService;
import com.aioj.next.problem.domain.PlagiarismService;
import com.aioj.next.problem.domain.StudentPostmortemService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/contests")
public class ContestController {
    private final ContestService contestService;
    private final ContestRunService contestRunService;
    private final ContestScoreboardService contestScoreboardService;
    private final ContestSubmissionAccessService contestSubmissionAccessService;
    private final ContestExportService contestExportService;
    private final PlagiarismService plagiarismService;
    private final ContestResolverService contestResolverService;
    private final ContestPostmortemService contestPostmortemService;
    private final StudentPostmortemService studentPostmortemService;
    private final ContestPlagiarismGraphService contestPlagiarismGraphService;
    private final ContestFairnessAlertService contestFairnessAlertService;
    private final ContestCommunicationService contestCommunicationService;
    private final OperationJobService operationJobService;

    public ContestController(ContestService contestService, ContestRunService contestRunService,
                             ContestScoreboardService contestScoreboardService,
                             ContestSubmissionAccessService contestSubmissionAccessService,
                             ContestExportService contestExportService,
                             PlagiarismService plagiarismService,
                             ContestResolverService contestResolverService,
                             ContestPostmortemService contestPostmortemService,
                             StudentPostmortemService studentPostmortemService,
                             ContestPlagiarismGraphService contestPlagiarismGraphService,
                             ContestFairnessAlertService contestFairnessAlertService,
                             ContestCommunicationService contestCommunicationService,
                             OperationJobService operationJobService) {
        this.contestService = contestService;
        this.contestRunService = contestRunService;
        this.contestScoreboardService = contestScoreboardService;
        this.contestSubmissionAccessService = contestSubmissionAccessService;
        this.contestExportService = contestExportService;
        this.plagiarismService = plagiarismService;
        this.contestResolverService = contestResolverService;
        this.contestPostmortemService = contestPostmortemService;
        this.studentPostmortemService = studentPostmortemService;
        this.contestPlagiarismGraphService = contestPlagiarismGraphService;
        this.contestFairnessAlertService = contestFairnessAlertService;
        this.contestCommunicationService = contestCommunicationService;
        this.operationJobService = operationJobService;
    }

    @GetMapping("/open")
    public ApiResponse<PageResponse<ContestOpenRunResponse>> openRuns(@RequestParam(defaultValue = "1") long page,
                                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                                       @RequestParam(required = false) ContestRunStatus status,
                                                                       @RequestParam(required = false) String keyword,
                                                                       @RequestParam(required = false) com.aioj.next.contract.contest.ContestMode mode,
                                                                       @RequestParam(required = false) ContestRegistrationStatus registrationStatus) {
        return ApiResponse.ok(contestRunService.openRuns(status, keyword, mode, registrationStatus, page, pageSize));
    }

    @GetMapping
    public ApiResponse<PageResponse<ContestResponse>> list(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long pageSize,
                                                           @RequestParam(required = false) ContestStatus status,
                                                           @RequestParam(required = false) Boolean mine,
                                                           @RequestParam(required = false) Long scopeGroupId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Boolean acm) {
        return ApiResponse.ok(contestService.list(page, pageSize, status, mine, scopeGroupId, keyword, acm));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContestResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(contestService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> create(@RequestBody @Valid ContestCreateRequest request) {
        return ApiResponse.ok(contestService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> update(@PathVariable Long id, @RequestBody @Valid ContestUpdateRequest request) {
        return ApiResponse.ok(contestService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> publish(@PathVariable Long id) {
        return ApiResponse.ok(contestService.publish(id));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> confirm(@PathVariable Long id) {
        return ApiResponse.ok(contestService.confirm(id));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> archive(@PathVariable Long id) {
        return ApiResponse.ok(contestService.archive(id));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> restore(@PathVariable Long id) {
        return ApiResponse.ok(contestService.restore(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResponse> delete(@PathVariable Long id) {
        return ApiResponse.ok(contestService.delete(id));
    }

    @GetMapping("/{id}/problems")
    public ApiResponse<List<ContestProblemResponse>> problems(@PathVariable Long id) {
        return ApiResponse.ok(contestService.listProblems(id));
    }

    @PutMapping("/{id}/problems")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ContestProblemResponse>> replaceProblems(@PathVariable Long id,
                                                                     @RequestBody @Valid ContestProblemBatchUpdateRequest request) {
        return ApiResponse.ok(contestService.replaceProblems(id, request));
    }

    @GetMapping("/{id}/participants")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ContestParticipantResponse>> participants(@PathVariable Long id) {
        return ApiResponse.ok(contestService.listParticipants(id));
    }

    @PostMapping("/{id}/participants/import-from-group")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ContestParticipantResponse>> importParticipants(@PathVariable Long id) {
        return ApiResponse.ok(contestService.importScopeGroupParticipants(id));
    }

    @PostMapping("/{id}/participants")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestParticipantResponse> addParticipant(@PathVariable Long id,
                                                                  @RequestBody ContestParticipantAddRequest request) {
        return ApiResponse.ok(contestService.addParticipant(id, request));
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ContestRunResponse>> runs(@PathVariable Long id,
                                                              @RequestParam(required = false) ContestRunStatus status,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) Instant from,
                                                              @RequestParam(required = false) Instant to,
                                                              @RequestParam(required = false) ContestRunListPurpose purpose,
                                                              @RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestRunService.listRuns(id, status, keyword, from, to, purpose, page, pageSize));
    }

    @GetMapping("/{id}/runs/{runId}")
    public ApiResponse<ContestRunResponse> run(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.getRun(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/open")
    public ApiResponse<ContestOpenRunResponse> openRun(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.openRun(id, runId));
    }

    @PostMapping("/{id}/runs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> createRun(@PathVariable Long id,
                                                     @RequestBody @Valid ContestRunCreateRequest request) {
        return ApiResponse.ok(contestRunService.createRun(id, request));
    }

    @PatchMapping("/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> updateRun(@PathVariable Long id, @PathVariable Long runId,
                                                     @RequestBody @Valid ContestRunUpdateRequest request) {
        return ApiResponse.ok(contestRunService.updateRun(id, runId, request));
    }

    @PostMapping("/{id}/runs/{runId}/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> publishRun(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.publishRun(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> archiveRun(@PathVariable Long id, @PathVariable Long runId,
                                                      @RequestParam(required = false) String reason) {
        return ApiResponse.ok(contestRunService.archiveRun(id, runId, reason));
    }

    @PostMapping("/{id}/runs/{runId}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> restoreRun(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.restoreRun(id, runId));
    }

    @DeleteMapping("/{id}/runs/{runId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> deleteRun(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.deleteRun(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/problems")
    public ApiResponse<List<ContestRunProblemSnapshotResponse>> runProblems(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.problemSnapshots(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/announcements")
    public ApiResponse<List<ContestAnnouncementResponse>> announcements(@PathVariable Long id,
                                                                        @PathVariable Long runId,
                                                                        @RequestParam(defaultValue = "false") boolean includeArchived) {
        return ApiResponse.ok(contestCommunicationService.listAnnouncements(id, runId, includeArchived));
    }

    @PostMapping("/{id}/runs/{runId}/announcements")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestAnnouncementResponse> createAnnouncement(@PathVariable Long id,
                                                                       @PathVariable Long runId,
                                                                       @RequestBody ContestAnnouncementRequest request) {
        return ApiResponse.ok(contestCommunicationService.createAnnouncement(id, runId, request));
    }

    @PatchMapping("/{id}/runs/{runId}/announcements/{announcementId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestAnnouncementResponse> updateAnnouncement(@PathVariable Long id,
                                                                       @PathVariable Long runId,
                                                                       @PathVariable Long announcementId,
                                                                       @RequestBody ContestAnnouncementRequest request) {
        return ApiResponse.ok(contestCommunicationService.updateAnnouncement(id, runId, announcementId, request));
    }

    @PostMapping("/{id}/runs/{runId}/announcements/{announcementId}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestAnnouncementResponse> archiveAnnouncement(@PathVariable Long id,
                                                                        @PathVariable Long runId,
                                                                        @PathVariable Long announcementId) {
        return ApiResponse.ok(contestCommunicationService.archiveAnnouncement(id, runId, announcementId));
    }

    @PostMapping("/{id}/runs/{runId}/announcements/{announcementId}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestAnnouncementResponse> restoreAnnouncement(@PathVariable Long id,
                                                                        @PathVariable Long runId,
                                                                        @PathVariable Long announcementId) {
        return ApiResponse.ok(contestCommunicationService.restoreAnnouncement(id, runId, announcementId));
    }

    @GetMapping("/{id}/runs/{runId}/clarifications")
    public ApiResponse<PageResponse<ContestClarificationResponse>> clarifications(@PathVariable Long id,
                                                                                  @PathVariable Long runId,
                                                                                  @RequestParam(required = false) ContestClarificationStatus status,
                                                                                  @RequestParam(required = false) ContestClarificationVisibility visibility,
                                                                                  @RequestParam(required = false) Long contestProblemId,
                                                                                  @RequestParam(required = false, defaultValue = "false") boolean staffView,
                                                                                  @RequestParam(defaultValue = "1") long page,
                                                                                  @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestCommunicationService.listClarifications(id, runId, status, visibility,
                contestProblemId, staffView, page, pageSize));
    }

    @PostMapping("/{id}/runs/{runId}/clarifications")
    public ApiResponse<ContestClarificationResponse> createClarification(@PathVariable Long id,
                                                                         @PathVariable Long runId,
                                                                         @RequestBody ContestClarificationCreateRequest request) {
        return ApiResponse.ok(contestCommunicationService.createClarification(id, runId, request));
    }

    @PostMapping("/{id}/runs/{runId}/clarifications/{clarificationId}/reply")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestClarificationResponse> replyClarification(@PathVariable Long id,
                                                                        @PathVariable Long runId,
                                                                        @PathVariable Long clarificationId,
                                                                        @RequestBody ContestClarificationReplyRequest request) {
        return ApiResponse.ok(contestCommunicationService.replyClarification(id, runId, clarificationId, request));
    }

    @PostMapping("/{id}/runs/{runId}/clarifications/{clarificationId}/close")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestClarificationResponse> closeClarification(@PathVariable Long id,
                                                                        @PathVariable Long runId,
                                                                        @PathVariable Long clarificationId) {
        return ApiResponse.ok(contestCommunicationService.closeClarification(id, runId, clarificationId));
    }

    @PostMapping("/{id}/runs/{runId}/registrations")
    public ApiResponse<ContestRegistrationResponse> register(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.register(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/invite")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRegistrationResponse> inviteRegistration(@PathVariable Long id,
                                                                       @PathVariable Long runId,
                                                                       @RequestBody ContestParticipantAddRequest request) {
        return ApiResponse.ok(contestRunService.invite(id, runId, request));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/invite/batch")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestInvitationBatchResponse> inviteRegistrationsBatch(@PathVariable Long id,
                                                                                  @PathVariable Long runId,
                                                                                  @RequestBody @Valid ContestInvitationBatchRequest request) {
        return ApiResponse.ok(contestRunService.inviteBatch(id, runId, request));
    }

    @GetMapping("/invitations/me")
    public ApiResponse<PageResponse<ContestRegistrationResponse>> myInvitations(@RequestParam(defaultValue = "1") long page,
                                                                                @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestRunService.listMyInvitations(page, pageSize));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/invitation/accept")
    public ApiResponse<ContestRegistrationResponse> acceptInvitation(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.acceptInvitation(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/invitation/decline")
    public ApiResponse<ContestRegistrationResponse> declineInvitation(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.declineInvitation(id, runId));
    }

    @DeleteMapping("/{id}/runs/{runId}/registrations/me")
    public ApiResponse<ContestRegistrationResponse> cancelRegistration(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestRunService.cancelMyRegistration(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/registrations")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ContestRegistrationResponse>> registrations(@PathVariable Long id,
                                                                                @PathVariable Long runId,
                                                                                @RequestParam(required = false) ContestRegistrationStatus status,
                                                                                @RequestParam(required = false) String keyword,
                                                                                @RequestParam(defaultValue = "1") long page,
                                                                                @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestRunService.listRegistrations(id, runId, status, keyword, page, pageSize));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/{registrationId}/approve")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRegistrationResponse> approveRegistration(@PathVariable Long id, @PathVariable Long runId,
                                                                        @PathVariable Long registrationId) {
        return ApiResponse.ok(contestRunService.approve(id, runId, registrationId));
    }

    @PostMapping("/{id}/runs/{runId}/registrations/{registrationId}/reject")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRegistrationResponse> rejectRegistration(@PathVariable Long id, @PathVariable Long runId,
                                                                       @PathVariable Long registrationId,
                                                                       @RequestParam(required = false) String reason) {
        return ApiResponse.ok(contestRunService.reject(id, runId, registrationId, reason));
    }

    @GetMapping("/{id}/scoreboard")
    public ApiResponse<ContestScoreboardResponse> scoreboard(@PathVariable Long id,
                                                             @RequestParam(required = false) ContestScoreboardView view,
                                                             @RequestParam(required = false) Long runId,
                                                             @RequestParam(required = false) Long atMillis,
                                                             @RequestParam(required = false) Long snapshotId) {
        return ApiResponse.ok(contestScoreboardService.scoreboard(id, runId, view, atMillis, snapshotId));
    }

    @GetMapping("/{id}/operation-jobs/{jobId}")
    public ApiResponse<OperationJobResponse> contestOperationJob(@PathVariable Long id, @PathVariable Long jobId) {
        return ApiResponse.ok(operationJobService.getContestJob(id, jobId));
    }

    @GetMapping("/{id}/scoreboard/export")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestExportResponse> exportScoreboard(@PathVariable Long id,
                                                               @RequestParam(defaultValue = "CSV") ContestExportFormat format,
                                                               @RequestParam(required = false) Long runId,
                                                               @RequestParam(required = false) ContestScoreboardView view,
                                                               @RequestParam(required = false) Long atMillis,
                                                               @RequestParam(required = false) Long snapshotId) {
        return ApiResponse.ok(contestExportService.exportScoreboard(id, format, runId, view, atMillis, snapshotId));
    }

    @PostMapping("/{id}/scoreboard/export-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createScoreboardExportJob(@PathVariable Long id,
                                                                       @RequestParam(defaultValue = "CSV") ContestExportFormat format,
                                                                       @RequestParam(required = false) Long runId,
                                                                       @RequestParam(required = false) ContestScoreboardView view,
                                                                       @RequestParam(required = false) Long atMillis,
                                                                       @RequestParam(required = false) Long snapshotId) {
        return ApiResponse.ok(operationJobService.createScoreboardExportJob(id, format, runId, view, atMillis, snapshotId));
    }

    @GetMapping("/{id}/scoreboard/snapshots")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<ContestScoreboardSnapshotResponse>> scoreboardSnapshots(@PathVariable Long id,
                                                                                   @RequestParam(required = false) Long runId) {
        return ApiResponse.ok(contestScoreboardService.listSnapshots(id, runId));
    }

    @PostMapping("/{id}/scoreboard/snapshots")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestScoreboardResponse> createScoreboardSnapshot(@PathVariable Long id,
                                                                           @RequestParam(required = false) Long runId,
                                                                           @RequestBody(required = false)
                                                                           ContestScoreboardSnapshotCreateRequest request) {
        return ApiResponse.ok(contestScoreboardService.createSnapshot(id, runId, request));
    }

    @GetMapping("/{id}/scoreboard/snapshots/{snapshotId}")
    public ApiResponse<ContestScoreboardResponse> scoreboardSnapshot(@PathVariable Long id, @PathVariable Long snapshotId) {
        return ApiResponse.ok(contestScoreboardService.snapshot(id, snapshotId));
    }

    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestScoreboardResponse> finalizeContest(@PathVariable Long id,
                                                                  @RequestParam(required = false) Long runId) {
        return ApiResponse.ok(contestScoreboardService.finalizeContest(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/scoreboard/timeline")
    public ApiResponse<ContestScoreboardTimelineResponse> scoreboardTimeline(@PathVariable Long id,
                                                                             @PathVariable Long runId,
                                                                             @RequestParam(required = false) ContestScoreboardView view) {
        return ApiResponse.ok(contestScoreboardService.timeline(id, runId, view));
    }

    @PostMapping("/{id}/runs/{runId}/scoreboard/unfreeze-public")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> unfreezePublicScoreboard(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestScoreboardService.unfreezePublicScoreboard(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/scoreboard/refreeze-public")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestRunResponse> refreezePublicScoreboard(@PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(contestScoreboardService.refreezePublicScoreboard(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/resolver-sessions")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResolverSessionDetailResponse> createResolverSession(@PathVariable Long id,
                                                                                   @PathVariable Long runId,
                                                                                   @RequestBody(required = false) @Valid
                                                                                   ContestResolverSessionCreateRequest request) {
        return ApiResponse.ok(contestResolverService.createSession(id, runId, request));
    }

    @GetMapping("/{id}/runs/{runId}/resolver-sessions")
    public ApiResponse<List<ContestResolverSessionResponse>> resolverSessions(@PathVariable Long id,
                                                                              @PathVariable Long runId) {
        return ApiResponse.ok(contestResolverService.listSessions(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/resolver-sessions/{sessionId}")
    public ApiResponse<ContestResolverSessionDetailResponse> resolverSession(@PathVariable Long id,
                                                                             @PathVariable Long runId,
                                                                             @PathVariable Long sessionId) {
        return ApiResponse.ok(contestResolverService.detail(id, runId, sessionId));
    }

    @PostMapping("/{id}/runs/{runId}/resolver-sessions/{sessionId}/publish")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResolverSessionResponse> publishResolverSession(@PathVariable Long id,
                                                                              @PathVariable Long runId,
                                                                              @PathVariable Long sessionId) {
        return ApiResponse.ok(contestResolverService.publish(id, runId, sessionId));
    }

    @PostMapping("/{id}/runs/{runId}/resolver-sessions/{sessionId}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResolverSessionResponse> archiveResolverSession(@PathVariable Long id,
                                                                              @PathVariable Long runId,
                                                                              @PathVariable Long sessionId) {
        return ApiResponse.ok(contestResolverService.archive(id, runId, sessionId));
    }

    @PostMapping("/{id}/runs/{runId}/resolver-sessions/{sessionId}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResolverSessionResponse> restoreResolverSession(@PathVariable Long id,
                                                                              @PathVariable Long runId,
                                                                              @PathVariable Long sessionId) {
        return ApiResponse.ok(contestResolverService.restore(id, runId, sessionId));
    }

    @DeleteMapping("/{id}/runs/{runId}/resolver-sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestResolverSessionResponse> deleteResolverSession(@PathVariable Long id,
                                                                             @PathVariable Long runId,
                                                                             @PathVariable Long sessionId) {
        return ApiResponse.ok(contestResolverService.delete(id, runId, sessionId));
    }

    @PostMapping("/{id}/runs/{runId}/postmortem-reports")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestPostmortemReportResponse> createPostmortemReport(@PathVariable Long id,
                                                                               @PathVariable Long runId) {
        return ApiResponse.ok(contestPostmortemService.createReport(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/postmortem-reports/operation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createPostmortemReportJob(@PathVariable Long id,
                                                                       @PathVariable Long runId) {
        return ApiResponse.ok(operationJobService.createContestPostmortemJob(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/postmortem-reports")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ContestPostmortemReportResponse>> postmortemReports(@PathVariable Long id,
                                                                                        @PathVariable Long runId,
                                                                                        @RequestParam(defaultValue = "1") long page,
                                                                                        @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestPostmortemService.listReports(id, runId, page, pageSize));
    }

    @GetMapping("/{id}/runs/{runId}/postmortem-reports/{reportId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestPostmortemReportResponse> postmortemReport(@PathVariable Long id,
                                                                         @PathVariable Long runId,
                                                                         @PathVariable Long reportId) {
        return ApiResponse.ok(contestPostmortemService.getReport(id, runId, reportId));
    }

    @PostMapping("/{id}/runs/{runId}/postmortem-reports/{reportId}/retry-ai")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestPostmortemReportResponse> retryPostmortemAi(@PathVariable Long id,
                                                                          @PathVariable Long runId,
                                                                          @PathVariable Long reportId) {
        return ApiResponse.ok(contestPostmortemService.retryAi(id, runId, reportId));
    }

    @GetMapping("/{id}/runs/{runId}/student-postmortem-reports")
    public ApiResponse<PageResponse<ContestStudentPostmortemReportResponse>> studentPostmortemReports(@PathVariable Long id,
                                                                                                      @PathVariable Long runId,
                                                                                                      @RequestParam(defaultValue = "1") long page,
                                                                                                      @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(studentPostmortemService.listMyReports(id, runId, page, pageSize));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports")
    public ApiResponse<ContestStudentPostmortemReportResponse> createStudentPostmortemReport(@PathVariable Long id,
                                                                                             @PathVariable Long runId) {
        return ApiResponse.ok(studentPostmortemService.createMyReport(id, runId));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports/operation-jobs")
    public ApiResponse<OperationJobResponse> createStudentPostmortemReportJob(@PathVariable Long id,
                                                                               @PathVariable Long runId) {
        return ApiResponse.ok(operationJobService.createStudentPostmortemJob(id, runId, null));
    }

    @GetMapping("/{id}/runs/{runId}/student-postmortem-reports/operation-jobs/active")
    public ApiResponse<ContestStudentPostmortemOperationJobResponse> activeStudentPostmortemReportJob(
            @PathVariable Long id, @PathVariable Long runId) {
        return ApiResponse.ok(operationJobService.findMyActiveStudentPostmortemJob(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/student-postmortem-reports/{reportId}")
    public ApiResponse<ContestStudentPostmortemReportResponse> studentPostmortemReport(@PathVariable Long id,
                                                                                       @PathVariable Long runId,
                                                                                       @PathVariable Long reportId) {
        return ApiResponse.ok(studentPostmortemService.getReport(id, runId, reportId));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports/{reportId}/retry-ai")
    public ApiResponse<ContestStudentPostmortemReportResponse> retryStudentPostmortemAi(@PathVariable Long id,
                                                                                        @PathVariable Long runId,
                                                                                        @PathVariable Long reportId) {
        return ApiResponse.ok(studentPostmortemService.retryAi(id, runId, reportId));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports/{reportId}/weakness-candidates/{candidateId}/accept")
    public ApiResponse<ContestStudentPostmortemWeaknessCandidateResponse> acceptStudentPostmortemWeakness(@PathVariable Long id,
                                                                                                          @PathVariable Long runId,
                                                                                                          @PathVariable Long reportId,
                                                                                                          @PathVariable Long candidateId) {
        return ApiResponse.ok(studentPostmortemService.acceptCandidate(id, runId, reportId, candidateId));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports/{reportId}/weakness-candidates/{candidateId}/reject")
    public ApiResponse<ContestStudentPostmortemWeaknessCandidateResponse> rejectStudentPostmortemWeakness(@PathVariable Long id,
                                                                                                          @PathVariable Long runId,
                                                                                                          @PathVariable Long reportId,
                                                                                                          @PathVariable Long candidateId) {
        return ApiResponse.ok(studentPostmortemService.rejectCandidate(id, runId, reportId, candidateId));
    }

    @GetMapping("/{id}/runs/{runId}/student-postmortem-summaries")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ContestStudentPostmortemSummaryResponse>> studentPostmortemSummaries(@PathVariable Long id,
                                                                                                         @PathVariable Long runId,
                                                                                                         @RequestParam(defaultValue = "1") long page,
                                                                                                         @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(studentPostmortemService.summaries(id, runId, page, pageSize));
    }

    @PostMapping("/{id}/runs/{runId}/participants/{participantId}/student-postmortem-reports")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestStudentPostmortemReportResponse> createParticipantStudentPostmortemReport(@PathVariable Long id,
                                                                                                        @PathVariable Long runId,
                                                                                                        @PathVariable Long participantId) {
        return ApiResponse.ok(studentPostmortemService.createParticipantReport(id, runId, participantId));
    }

    @PostMapping("/{id}/runs/{runId}/participants/{participantId}/student-postmortem-reports/operation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createParticipantStudentPostmortemReportJob(@PathVariable Long id,
                                                                                        @PathVariable Long runId,
                                                                                        @PathVariable Long participantId) {
        return ApiResponse.ok(operationJobService.createStudentPostmortemJob(id, runId, participantId));
    }

    @PostMapping("/{id}/runs/{runId}/student-postmortem-reports/batch-operation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createBatchStudentPostmortemReportJob(@PathVariable Long id,
                                                                                  @PathVariable Long runId,
                                                                                  @RequestBody(required = false)
                                                                                  StudentPostmortemBatchJobRequest request) {
        return ApiResponse.ok(operationJobService.createBatchStudentPostmortemJob(id, runId,
                request == null ? null : request.participantIds()));
    }

    @GetMapping("/{id}/submissions")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<ContestSubmissionResponse>> contestSubmissions(@PathVariable Long id,
                                                                                   @RequestParam(defaultValue = "1") long page,
                                                                                   @RequestParam(defaultValue = "20") long pageSize,
                                                                                   @RequestParam(required = false) Long runId,
                                                                                   @RequestParam(required = false) Long contestProblemId,
                                                                                   @RequestParam(required = false) Long participantId,
                                                                                   @RequestParam(required = false) Long userId,
                                                                                   @RequestParam(required = false) SubmissionStatus status,
                                                                                   @RequestParam(required = false) String language) {
        return ApiResponse.ok(contestSubmissionAccessService.listSubmissions(id, runId, page, pageSize, contestProblemId,
                participantId, userId, status, language));
    }

    @GetMapping("/{id}/submissions/export")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestExportResponse> exportContestSubmissions(@PathVariable Long id,
                                                                        @RequestParam(defaultValue = "CSV") ContestExportFormat format,
                                                                        @RequestParam(required = false) Long runId,
                                                                        @RequestParam(required = false) Long contestProblemId,
                                                                        @RequestParam(required = false) Long participantId,
                                                                        @RequestParam(required = false) Long userId,
                                                                        @RequestParam(required = false) SubmissionStatus status,
                                                                        @RequestParam(required = false) String language) {
        return ApiResponse.ok(contestExportService.exportSubmissions(id, format, runId, contestProblemId, participantId,
                userId, status, language));
    }

    @PostMapping("/{id}/submissions/export-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createContestSubmissionsExportJob(@PathVariable Long id,
                                                                               @RequestParam(defaultValue = "CSV") ContestExportFormat format,
                                                                               @RequestParam(required = false) Long runId,
                                                                               @RequestParam(required = false) Long contestProblemId,
                                                                               @RequestParam(required = false) Long participantId,
                                                                               @RequestParam(required = false) Long userId,
                                                                               @RequestParam(required = false) SubmissionStatus status,
                                                                               @RequestParam(required = false) String language) {
        return ApiResponse.ok(operationJobService.createSubmissionsExportJob(id, format, runId, contestProblemId,
                participantId, userId, status, language));
    }

    @GetMapping("/{id}/submissions/{submissionId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestSubmissionResponse> contestSubmission(@PathVariable Long id, @PathVariable Long submissionId) {
        return ApiResponse.ok(contestSubmissionAccessService.getSubmission(id, submissionId));
    }

    @PostMapping("/{id}/submissions/{submissionId}/code-accesses")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestSubmissionCodeResponse> accessContestSubmissionCode(@PathVariable Long id,
                                                                                  @PathVariable Long submissionId,
                                                                                  @RequestBody(required = false) @Valid
                                                                                  SubmissionCodeAccessRequest request) {
        return ApiResponse.ok(contestSubmissionAccessService.accessCode(id, submissionId, request));
    }

    @GetMapping("/{id}/submission-code-access-logs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<SubmissionCodeAccessLogResponse>> submissionCodeAccessLogs(@PathVariable Long id,
                                                                                               @RequestParam(defaultValue = "1") long page,
                                                                                               @RequestParam(defaultValue = "20") long pageSize,
                                                                                               @RequestParam(required = false) Long runId,
                                                                                               @RequestParam(required = false) Long submissionId,
                                                                                               @RequestParam(required = false) Long viewerUserId,
                                                                                               @RequestParam(required = false) Long targetUserId) {
        return ApiResponse.ok(contestSubmissionAccessService.listAccessLogs(id, runId, page, pageSize, submissionId,
                viewerUserId, targetUserId));
    }

    @PostMapping("/{id}/plagiarism-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PlagiarismJobResponse> createPlagiarismJob(@PathVariable Long id,
                                                                  @RequestParam(required = false) Long runId,
                                                                  @RequestBody(required = false)
                                                                  PlagiarismJobCreateRequest request) {
        return ApiResponse.ok(plagiarismService.createJob(id, runId, request));
    }

    @PostMapping("/{id}/plagiarism-jobs/operation-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createPlagiarismJobOperation(@PathVariable Long id,
                                                                         @RequestParam(required = false) Long runId,
                                                                         @RequestBody(required = false)
                                                                         PlagiarismJobCreateRequest request) {
        return ApiResponse.ok(operationJobService.createPlagiarismCheckJob(id, runId, request));
    }

    @GetMapping("/{id}/plagiarism-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<PlagiarismJobResponse>> plagiarismJobs(@PathVariable Long id,
                                                                           @RequestParam(defaultValue = "1") long page,
                                                                           @RequestParam(defaultValue = "20") long pageSize,
                                                                           @RequestParam(required = false) Long runId) {
        return ApiResponse.ok(plagiarismService.listJobs(id, runId, page, pageSize));
    }

    @GetMapping("/{id}/plagiarism-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PlagiarismJobResponse> plagiarismJob(@PathVariable Long id, @PathVariable Long jobId) {
        return ApiResponse.ok(plagiarismService.getJob(id, jobId));
    }

    @GetMapping("/{id}/plagiarism-jobs/{jobId}/pairs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<PlagiarismPairResponse>> plagiarismPairs(@PathVariable Long id,
                                                                             @PathVariable Long jobId,
                                                                             @RequestParam(defaultValue = "1") long page,
                                                                             @RequestParam(defaultValue = "20") long pageSize,
                                                                             @RequestParam(required = false) Long contestProblemId,
                                                                             @RequestParam(required = false) String language,
                                                                             @RequestParam(required = false) PlagiarismRiskLevel riskLevel,
                                                                             @RequestParam(required = false) PlagiarismReviewStatus reviewStatus) {
        return ApiResponse.ok(plagiarismService.listPairs(id, jobId, page, pageSize, contestProblemId,
                language, riskLevel, reviewStatus));
    }

    @GetMapping("/{id}/plagiarism-jobs/{jobId}/pairs/{pairId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PlagiarismPairDetailResponse> plagiarismPair(@PathVariable Long id,
                                                                    @PathVariable Long jobId,
                                                                    @PathVariable Long pairId) {
        return ApiResponse.ok(plagiarismService.pairDetail(id, jobId, pairId));
    }

    @PatchMapping("/{id}/plagiarism-jobs/{jobId}/pairs/{pairId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PlagiarismPairResponse> updatePlagiarismPair(@PathVariable Long id,
                                                                    @PathVariable Long jobId,
                                                                    @PathVariable Long pairId,
                                                                    @RequestBody(required = false) @Valid
                                                                    PlagiarismPairUpdateRequest request) {
        return ApiResponse.ok(plagiarismService.updatePair(id, jobId, pairId, request));
    }

    @PostMapping("/{id}/plagiarism-jobs/{jobId}/pairs/{pairId}/ai-analysis/retry")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PlagiarismPairDetailResponse> retryPlagiarismAiAnalysis(@PathVariable Long id,
                                                                               @PathVariable Long jobId,
                                                                               @PathVariable Long pairId) {
        return ApiResponse.ok(plagiarismService.retryAiAnalysis(id, jobId, pairId));
    }

    @GetMapping("/{id}/plagiarism-jobs/{jobId}/export")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestExportResponse> exportPlagiarismJob(@PathVariable Long id,
                                                                  @PathVariable Long jobId,
                                                                  @RequestParam(defaultValue = "CSV")
                                                                  ContestExportFormat format) {
        return ApiResponse.ok(plagiarismService.exportJob(id, jobId, format));
    }

    @PostMapping("/{id}/plagiarism-jobs/{jobId}/export-jobs")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<OperationJobResponse> createPlagiarismExportJob(@PathVariable Long id,
                                                                       @PathVariable Long jobId,
                                                                       @RequestParam(defaultValue = "CSV")
                                                                       ContestExportFormat format) {
        return ApiResponse.ok(operationJobService.createPlagiarismExportJob(id, jobId, format));
    }

    @GetMapping("/{id}/runs/{runId}/plagiarism-graph")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<ContestPlagiarismGraphResponse> plagiarismGraph(@PathVariable Long id,
                                                                       @PathVariable Long runId,
                                                                       @RequestParam(required = false) Long jobId,
                                                                       @RequestParam(required = false) Long problemId,
                                                                       @RequestParam(required = false) Long contestProblemId,
                                                                       @RequestParam(required = false) String language,
                                                                       @RequestParam(required = false) PlagiarismRiskLevel riskLevel,
                                                                       @RequestParam(required = false) PlagiarismReviewStatus reviewStatus) {
        Long effectiveContestProblemId = contestProblemId == null ? problemId : contestProblemId;
        return ApiResponse.ok(contestPlagiarismGraphService.graph(id, runId, jobId, effectiveContestProblemId,
                language, riskLevel, reviewStatus));
    }

    @PostMapping("/{id}/runs/{runId}/fairness-alerts/rebuild")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<FairnessAlertResponse>> rebuildFairnessAlerts(@PathVariable Long id,
                                                                                  @PathVariable Long runId) {
        return ApiResponse.ok(contestFairnessAlertService.rebuild(id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/fairness-alerts")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<FairnessAlertResponse>> fairnessAlerts(@PathVariable Long id,
                                                                           @PathVariable Long runId,
                                                                           @RequestParam(required = false) FairnessAlertSeverity severity,
                                                                           @RequestParam(required = false) FairnessAlertType type,
                                                                           @RequestParam(required = false) FairnessAlertStatus status,
                                                                           @RequestParam(required = false) Long participantId,
                                                                           @RequestParam(defaultValue = "1") long page,
                                                                           @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(contestFairnessAlertService.list(id, runId, severity, type, status, participantId, page, pageSize));
    }

    @PatchMapping("/{id}/runs/{runId}/fairness-alerts/{alertId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<FairnessAlertResponse> updateFairnessAlert(@PathVariable Long id,
                                                                  @PathVariable Long runId,
                                                                  @PathVariable Long alertId,
                                                                  @RequestBody(required = false) @Valid
                                                                  FairnessAlertUpdateRequest request) {
        return ApiResponse.ok(contestFairnessAlertService.update(id, runId, alertId, request));
    }
}
