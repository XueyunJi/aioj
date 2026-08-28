package com.aioj.next.auth.controller;

import com.aioj.next.auth.config.AuthProperties;
import com.aioj.next.auth.domain.AuthTokenIssuer;
import com.aioj.next.auth.domain.UserAccountService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.auth.LoginRequest;
import com.aioj.next.contract.auth.RegisterRequest;
import com.aioj.next.contract.auth.TokenResponse;
import com.aioj.next.contract.auth.UserProfileResponse;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserAccountService userAccountService;
    private final JwtTokenService jwtTokenService;
    private final AuthTokenIssuer authTokenIssuer;
    private final AuthProperties authProperties;

    public AuthController(UserAccountService userAccountService, JwtTokenService jwtTokenService,
                          AuthTokenIssuer authTokenIssuer, AuthProperties authProperties) {
        this.userAccountService = userAccountService;
        this.jwtTokenService = jwtTokenService;
        this.authTokenIssuer = authTokenIssuer;
        this.authProperties = authProperties;
    }

    @PostMapping("/register")
    public ApiResponse<TokenResponse> register(@RequestBody @Valid RegisterRequest request) {
        if (!authProperties.isRegistrationEnabled()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Public registration is disabled");
        }
        return ApiResponse.ok(authTokenIssuer.issue(userAccountService.register(request.account(), request.password(),
                request.displayName(), request.email(), request.role())));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.ok(authTokenIssuer.issue(userAccountService.login(request.account(), request.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = bearerToken(authorization);
        Claims claims = parseToken(token);
        if (!JwtTokenService.TOKEN_TYPE_REFRESH.equals(claims.get("typ", String.class))) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token required");
        }
        return ApiResponse.ok(authTokenIssuer.issue(userAccountService.refresh(token, claims)));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!token.isBlank()) {
            try {
                Claims claims = jwtTokenService.parse(token);
                if (JwtTokenService.TOKEN_TYPE_REFRESH.equals(claims.get("typ", String.class))) {
                    userAccountService.revokeRefreshToken(token);
                } else if (JwtTokenService.TOKEN_TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                    userAccountService.revokeUserRefreshTokens(Long.valueOf(claims.getSubject()));
                }
            } catch (RuntimeException ignored) {
                // Logout remains idempotent even if the presented token is already invalid.
            }
        }
        return ApiResponse.ok(Boolean.TRUE);
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me() {
        return ApiResponse.ok(userAccountService.getProfile(SecuritySupport.currentUserId()));
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Bearer token required");
        }
        return authorization.substring(7);
    }

    private Claims parseToken(String token) {
        try {
            return jwtTokenService.parse(token);
        } catch (RuntimeException ex) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Invalid token");
        }
    }
}
