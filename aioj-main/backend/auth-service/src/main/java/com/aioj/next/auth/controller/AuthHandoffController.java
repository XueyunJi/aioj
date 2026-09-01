package com.aioj.next.auth.controller;

import com.aioj.next.auth.domain.AuthHandoffService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.auth.HandoffExchangeRequest;
import com.aioj.next.contract.auth.HandoffExchangeResponse;
import com.aioj.next.contract.auth.HandoffTicketIssueRequest;
import com.aioj.next.contract.auth.HandoffTicketIssueResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthHandoffController {
    private final AuthHandoffService authHandoffService;

    public AuthHandoffController(AuthHandoffService authHandoffService) {
        this.authHandoffService = authHandoffService;
    }

    @PostMapping("/handoff-tickets")
    public ApiResponse<HandoffTicketIssueResponse> issue(@RequestBody @Valid HandoffTicketIssueRequest request) {
        return ApiResponse.ok(authHandoffService.issue(SecuritySupport.currentUserId(), request));
    }

    @PostMapping("/handoff/exchange")
    public ApiResponse<HandoffExchangeResponse> exchange(@RequestBody @Valid HandoffExchangeRequest request) {
        return ApiResponse.ok(authHandoffService.exchange(request.ticket()));
    }
}
