package com.aioj.next.auth.controller;

import com.aioj.next.auth.domain.LearningGroupService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.contract.learning.LearningGroupCreateRequest;
import com.aioj.next.contract.learning.LearningGroupMemberAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddResponse;
import com.aioj.next.contract.learning.LearningGroupMemberResponse;
import com.aioj.next.contract.learning.LearningGroupResponse;
import com.aioj.next.contract.learning.LearningGroupStatus;
import com.aioj.next.contract.learning.LearningGroupUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/classes")
public class LearningGroupController {
    private final LearningGroupService learningGroupService;

    public LearningGroupController(LearningGroupService learningGroupService) {
        this.learningGroupService = learningGroupService;
    }

    @GetMapping
    public ApiResponse<List<LearningGroupResponse>> classes(@RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) LearningGroupStatus status) {
        return ApiResponse.ok(learningGroupService.listClasses(keyword, status));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupResponse> createClass(@RequestBody @Valid LearningGroupCreateRequest request) {
        return ApiResponse.ok(learningGroupService.createClass(request));
    }

    @GetMapping("/{classId}")
    public ApiResponse<LearningGroupResponse> getClass(@PathVariable Long classId) {
        return ApiResponse.ok(learningGroupService.getClass(classId));
    }

    @PatchMapping("/{classId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupResponse> updateClass(@PathVariable Long classId,
                                                          @RequestBody @Valid LearningGroupUpdateRequest request) {
        return ApiResponse.ok(learningGroupService.updateClass(classId, request));
    }

    @PostMapping("/{classId}/archive")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupResponse> archiveClass(@PathVariable Long classId) {
        return ApiResponse.ok(learningGroupService.archiveClass(classId));
    }

    @PostMapping("/{classId}/restore")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupResponse> restoreClass(@PathVariable Long classId) {
        return ApiResponse.ok(learningGroupService.restoreClass(classId));
    }

    @DeleteMapping("/{classId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<Void> deleteClass(@PathVariable Long classId) {
        learningGroupService.softDeleteClass(classId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{classId}/members")
    public ApiResponse<List<LearningGroupMemberResponse>> classMembers(@PathVariable Long classId) {
        return ApiResponse.ok(learningGroupService.listClassMembers(classId));
    }

    @PostMapping("/{classId}/members")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupMemberResponse> addClassMember(@PathVariable Long classId,
                                                                   @RequestBody @Valid LearningGroupMemberAddRequest request) {
        return ApiResponse.ok(learningGroupService.addClassMember(classId, request));
    }

    @PostMapping("/{classId}/members/batch")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<LearningGroupMemberBatchAddResponse> addClassMembers(@PathVariable Long classId,
                                                                            @RequestBody @Valid LearningGroupMemberBatchAddRequest request) {
        return ApiResponse.ok(learningGroupService.addClassMembers(classId, request));
    }

    @DeleteMapping("/{classId}/members/{userId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<Boolean> removeClassMember(@PathVariable Long classId, @PathVariable Long userId) {
        learningGroupService.removeClassMember(classId, userId);
        return ApiResponse.ok(Boolean.TRUE);
    }

}
