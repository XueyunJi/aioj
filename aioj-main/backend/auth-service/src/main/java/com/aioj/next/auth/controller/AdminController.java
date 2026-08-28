package com.aioj.next.auth.controller;

import com.aioj.next.auth.domain.UserAccountService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.AdminUserBatchActionRequest;
import com.aioj.next.contract.auth.AdminUserBatchActionResponse;
import com.aioj.next.contract.auth.AdminUserBatchCreateRequest;
import com.aioj.next.contract.auth.AdminUserBatchCreateResponse;
import com.aioj.next.contract.auth.AdminUserBatchPreviewRequest;
import com.aioj.next.contract.auth.AdminUserBatchPreviewResponse;
import com.aioj.next.contract.auth.AdminUserCreateRequest;
import com.aioj.next.contract.auth.AdminUserResponse;
import com.aioj.next.contract.auth.AdminUserUpdateRequest;
import com.aioj.next.contract.auth.DailyUserActivityResponse;
import com.aioj.next.contract.auth.RoleCapabilityResponse;
import com.aioj.next.contract.auth.RoleResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final UserAccountService userAccountService;

    public AdminController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleResponse>> roles() {
        return ApiResponse.ok(Arrays.stream(Role.values())
                .map(role -> new RoleResponse(role, label(role)))
                .toList());
    }

    /**
     * Server-owned capability matrix. The frontend renders this verbatim instead of
     * hardcoding permissions, so the documented matrix cannot drift from the backend.
     * Keep this mapping aligned with the actual @PreAuthorize rules.
     */
    @GetMapping("/roles/capabilities")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<RoleCapabilityResponse>> roleCapabilities() {
        return ApiResponse.ok(List.of(
                new RoleCapabilityResponse(Role.STUDENT, List.of(
                        RoleCapabilityResponse.SOLVE_SUBMIT,
                        RoleCapabilityResponse.USE_AI_CHAT)),
                new RoleCapabilityResponse(Role.TEACHER, List.of(
                        RoleCapabilityResponse.SOLVE_SUBMIT,
                        RoleCapabilityResponse.USE_AI_CHAT,
                        RoleCapabilityResponse.REVIEW_DRAFTS,
                        RoleCapabilityResponse.EDIT_PROBLEMS)),
                new RoleCapabilityResponse(Role.ADMIN, List.of(
                        RoleCapabilityResponse.SOLVE_SUBMIT,
                        RoleCapabilityResponse.USE_AI_CHAT,
                        RoleCapabilityResponse.REVIEW_DRAFTS,
                        RoleCapabilityResponse.EDIT_PROBLEMS,
                        RoleCapabilityResponse.MANAGE_USERS,
                        RoleCapabilityResponse.DISABLE_ACCOUNTS))
        ));
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> users(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "ACTIVE") String lifecycle) {
        return ApiResponse.ok(userAccountService.listUsers(page, pageSize, search, role, enabled, lifecycle));
    }

    @PostMapping("/users")
    public ApiResponse<AdminUserResponse> createUser(@RequestBody @Valid AdminUserCreateRequest request) {
        return ApiResponse.ok(userAccountService.createAdminUser(request));
    }

    @PostMapping("/users/batch/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminUserBatchPreviewResponse> previewBatchUsers(
            @RequestBody @Valid AdminUserBatchPreviewRequest request) {
        return ApiResponse.ok(userAccountService.previewBatchUsers(request));
    }

    @PostMapping("/users/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminUserBatchCreateResponse> createBatchUsers(
            @RequestBody @Valid AdminUserBatchCreateRequest request) {
        return ApiResponse.ok(userAccountService.createBatchUsers(request));
    }

    @PostMapping("/users/batch/actions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminUserBatchActionResponse> batchUserActions(
            @RequestBody @Valid AdminUserBatchActionRequest request) {
        return ApiResponse.ok(userAccountService.applyBatchAction(SecuritySupport.currentUserId(), request));
    }

    @GetMapping("/users/analytics/activity")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<DailyUserActivityResponse>> userActivity(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(userAccountService.userActivity(days));
    }

    @GetMapping("/users/{id}")
    public ApiResponse<AdminUserResponse> getUser(@PathVariable Long id) {
        return ApiResponse.ok(userAccountService.getAdminUser(id));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<AdminUserResponse> updateUser(@PathVariable Long id,
                                                     @RequestBody @Valid AdminUserUpdateRequest request) {
        return ApiResponse.ok(userAccountService.updateAdminUser(id, request, SecuritySupport.currentUserId()));
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Boolean> deleteUser(@PathVariable Long id) {
        userAccountService.disableUser(id, SecuritySupport.currentUserId());
        return ApiResponse.ok(Boolean.TRUE);
    }

    private String label(Role role) {
        return switch (role) {
            case STUDENT -> "Student";
            case TEACHER -> "Teacher";
            case ADMIN -> "Admin";
        };
    }
}
