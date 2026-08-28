package com.aioj.next.problem.domain;

import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.operation.OperationAuditEventResponse;
import com.aioj.next.problem.persistence.entity.OperationAuditEventEntity;
import com.aioj.next.problem.persistence.mapper.OperationAuditEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class OperationAuditService {
    private static final Map<String, String> ACTION_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("SUBMISSION_CODE_ACCESS", "查看提交源码"),
            Map.entry("AI_MEMORY_MARKDOWN_EXPORT", "下载 AI 学习档案"),
            Map.entry("AI_MEMORY_REVIEW_APPROVE", "AI 记忆审查通过"),
            Map.entry("AI_MEMORY_REVIEW_REJECT", "AI 记忆审查拒绝"),
            Map.entry("AI_MEMORY_REVIEW_EDIT_ACCEPT", "AI 记忆编辑后通过"),
            Map.entry("AI_MEMORY_REVIEW_MERGE", "AI 记忆合并"),
            Map.entry("AI_MEMORY_REVIEW_REQUEST_USER_CONFIRMATION", "AI 记忆请求用户确认"),
            Map.entry("AI_CONTEST_REQUEST_BLOCKED", "比赛 AI 请求被拦截"),
            Map.entry("AI_CONTEST_LEAK_PARTICIPANT_BLOCKED", "比赛参赛者 AI 提问被拦截"),
            Map.entry("AI_CONTEST_GUARD_EVALUATED", "比赛参赛者 AI 轮次评估"),
            Map.entry("AI_CONTEST_GUARD_DEGRADED", "比赛 AI 防护降级"),
            Map.entry("AI_CONTEST_RESPONSE_REPLACED", "比赛 AI 回答已替换"),
            Map.entry("PROBLEM_DRAFT_GENERATION_JOB_CREATED", "AI 题目草稿生成已排队"),
            Map.entry("PROBLEM_DRAFT_GENERATION_JOB_RUNNING", "AI 题目草稿生成进行中"),
            Map.entry("PROBLEM_DRAFT_GENERATION_JOB_COMPLETED", "AI 题目草稿生成完成"),
            Map.entry("PROBLEM_DRAFT_GENERATION_JOB_FAILED", "AI 题目草稿生成失败"),
            Map.entry("PROBLEM_DRAFT_REGENERATION_JOB_CREATED", "AI 题目草稿改写已排队"),
            Map.entry("PROBLEM_DRAFT_REGENERATION_JOB_RUNNING", "AI 题目草稿改写进行中"),
            Map.entry("PROBLEM_DRAFT_REGENERATION_JOB_COMPLETED", "AI 题目草稿改写完成"),
            Map.entry("PROBLEM_DRAFT_REGENERATION_JOB_FAILED", "AI 题目草稿改写失败"),
            Map.entry("EXPORT_SCOREBOARD_QUEUED", "导出榜单已排队"),
            Map.entry("EXPORT_SCOREBOARD_COMPLETED", "导出榜单完成"),
            Map.entry("EXPORT_SCOREBOARD_FAILED", "导出榜单失败"),
            Map.entry("EXPORT_SUBMISSIONS_QUEUED", "导出提交已排队"),
            Map.entry("EXPORT_SUBMISSIONS_COMPLETED", "导出提交完成"),
            Map.entry("EXPORT_SUBMISSIONS_FAILED", "导出提交失败"),
            Map.entry("EXPORT_PLAGIARISM_REPORT_QUEUED", "导出查重报告已排队"),
            Map.entry("EXPORT_PLAGIARISM_REPORT_COMPLETED", "导出查重报告完成"),
            Map.entry("EXPORT_PLAGIARISM_REPORT_FAILED", "导出查重报告失败"),
            Map.entry("RUN_PLAGIARISM_CHECK_QUEUED", "查重任务已排队"),
            Map.entry("RUN_PLAGIARISM_CHECK_COMPLETED", "查重完成"),
            Map.entry("RUN_PLAGIARISM_CHECK_FAILED", "查重失败"),
            Map.entry("GENERATE_SCOREBOARD_TIMELINE_QUEUED", "生成榜单时间轴已排队"),
            Map.entry("GENERATE_SCOREBOARD_TIMELINE_COMPLETED", "生成榜单时间轴完成"),
            Map.entry("GENERATE_SCOREBOARD_TIMELINE_FAILED", "生成榜单时间轴失败"),
            Map.entry("GENERATE_CONTEST_POSTMORTEM_QUEUED", "生成教师复盘已排队"),
            Map.entry("GENERATE_CONTEST_POSTMORTEM_COMPLETED", "生成教师复盘完成"),
            Map.entry("GENERATE_CONTEST_POSTMORTEM_FAILED", "生成教师复盘失败"),
            Map.entry("GENERATE_STUDENT_POSTMORTEM_QUEUED", "生成学生复盘已排队"),
            Map.entry("GENERATE_STUDENT_POSTMORTEM_COMPLETED", "生成学生复盘完成"),
            Map.entry("GENERATE_STUDENT_POSTMORTEM_FAILED", "生成学生复盘失败"),
            Map.entry("BATCH_GENERATE_STUDENT_POSTMORTEMS_QUEUED", "批量生成学生复盘已排队"),
            Map.entry("BATCH_GENERATE_STUDENT_POSTMORTEMS_COMPLETED", "批量生成学生复盘完成"),
            Map.entry("BATCH_GENERATE_STUDENT_POSTMORTEMS_FAILED", "批量生成学生复盘失败"),
            Map.entry("OPERATION_JOB_RETRY", "重试异步任务"),
            Map.entry("OPERATION_ARTIFACT_DOWNLOAD", "下载运维产物"),
            Map.entry("ARCHIVE", "归档"),
            Map.entry("RESTORE", "恢复"),
            Map.entry("SOFT_DELETE", "软删除"),
            Map.entry("DELETE", "删除"),
            Map.entry("CREATE", "创建"),
            Map.entry("UPDATE", "更新"),
            Map.entry("APPROVE", "通过"),
            Map.entry("REJECT", "拒绝"),
            Map.entry("IMPORT", "导入"),
            Map.entry("EXPORT", "导出"),
            Map.entry("DOWNLOAD", "下载"),
            Map.entry("GENERATE", "生成"),
            Map.entry("UNFREEZE_PUBLIC_SCOREBOARD", "解冻公开榜"),
            Map.entry("REFREEZE_PUBLIC_SCOREBOARD", "重新封榜")
    );
    private static final String UNKNOWN_ACTION_DISPLAY_NAME = "未识别审计操作";

    private final OperationAuditEventMapper auditEventMapper;
    private final ObjectMapper objectMapper;

    public OperationAuditService(OperationAuditEventMapper auditEventMapper, ObjectMapper objectMapper) {
        this.auditEventMapper = auditEventMapper;
        this.objectMapper = objectMapper;
    }

    public void recordCurrentUser(String action, String resourceType, Long resourceId,
                                  Long contestId, Long contestRunId, Long targetUserId,
                                  String status, Map<String, Object> summary) {
        record(SecuritySupport.currentUserId(), action, resourceType, resourceId, contestId, contestRunId,
                targetUserId, status, summary);
    }

    public void record(Long actorUserId, String action, String resourceType, Long resourceId,
                       Long contestId, Long contestRunId, Long targetUserId,
                       String status, Map<String, Object> summary) {
        OperationAuditEventEntity event = new OperationAuditEventEntity();
        event.setActorUserId(actorUserId);
        event.setAction(action);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setContestId(contestId);
        event.setContestRunId(contestRunId);
        event.setTargetUserId(targetUserId);
        event.setStatus(status);
        event.setTraceId(TraceIds.current());
        event.setSummaryJson(toJson(summary));
        event.setCreatedAt(Instant.now());
        auditEventMapper.insert(event);
    }

    public PageResponse<OperationAuditEventResponse> list(long page, long pageSize, String action, String resourceType,
                                                          Long contestId, Long contestRunId, Long actorUserId) {
        if (!SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Cannot view operation audit events");
        }
        Long effectiveActor = SecuritySupport.hasRole(Role.ADMIN) ? actorUserId : SecuritySupport.currentUserId();
        LambdaQueryWrapper<OperationAuditEventEntity> query = new LambdaQueryWrapper<OperationAuditEventEntity>()
                .eq(action != null && !action.isBlank(), OperationAuditEventEntity::getAction, action)
                .eq(resourceType != null && !resourceType.isBlank(), OperationAuditEventEntity::getResourceType, resourceType)
                .eq(contestId != null, OperationAuditEventEntity::getContestId, contestId)
                .eq(contestRunId != null, OperationAuditEventEntity::getContestRunId, contestRunId)
                .eq(effectiveActor != null, OperationAuditEventEntity::getActorUserId, effectiveActor)
                .orderByDesc(OperationAuditEventEntity::getCreatedAt)
                .orderByDesc(OperationAuditEventEntity::getId);
        Page<OperationAuditEventEntity> result = auditEventMapper.selectPage(new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    private OperationAuditEventResponse toResponse(OperationAuditEventEntity event) {
        return new OperationAuditEventResponse(event.getId(), event.getActorUserId(), event.getAction(),
                displayName(event.getAction()),
                event.getResourceType(), event.getResourceId(), event.getContestId(), event.getContestRunId(),
                event.getTargetUserId(), event.getStatus(), event.getTraceId(), event.getSummaryJson(),
                event.getCreatedAt());
    }

    private String displayName(String action) {
        if (action == null || action.isBlank()) {
            return UNKNOWN_ACTION_DISPLAY_NAME;
        }
        return ACTION_DISPLAY_NAMES.getOrDefault(action, UNKNOWN_ACTION_DISPLAY_NAME);
    }

    private String toJson(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"summary serialization failed\"}";
        }
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private long normalizePageSize(long pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
