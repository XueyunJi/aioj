package com.aioj.next.problem.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.notification.UserNotificationMarkReadRequest;
import com.aioj.next.contract.notification.UserNotificationMarkReadResponse;
import com.aioj.next.contract.notification.UserNotificationResponse;
import com.aioj.next.contract.notification.UserNotificationType;
import com.aioj.next.contract.notification.UserNotificationUnreadCountResponse;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.aioj.next.problem.domain.notification.UserNotificationStreamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/notifications")
public class UserNotificationController {
    private final UserNotificationService notificationService;
    private final UserNotificationStreamService streamService;

    public UserNotificationController(UserNotificationService notificationService,
                                      UserNotificationStreamService streamService) {
        this.notificationService = notificationService;
        this.streamService = streamService;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserNotificationResponse>> list(
            @RequestParam(required = false) UserNotificationType type,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize
    ) {
        return ApiResponse.ok(notificationService.list(type, subjectType, subjectId, scopeType, scopeId,
                unreadOnly, page, pageSize));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UserNotificationUnreadCountResponse> unreadCount(
            @RequestParam(required = false) UserNotificationType type
    ) {
        return ApiResponse.ok(notificationService.unreadCount(type));
    }

    @PostMapping("/mark-read")
    public ApiResponse<UserNotificationMarkReadResponse> markRead(
            @Valid @RequestBody UserNotificationMarkReadRequest request
    ) {
        return ApiResponse.ok(notificationService.markCurrentUserRead(request));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(streamService.connect(SecuritySupport.currentUserId()));
    }

    @ExceptionHandler(IOException.class)
    public void handleStreamClientDisconnect(IOException ignored) {
        // Refreshing or navigating away closes the browser's SSE connection. The
        // container redispatches that transport failure to this controller even
        // after the emitter has been removed; the committed stream needs no body.
    }
}
