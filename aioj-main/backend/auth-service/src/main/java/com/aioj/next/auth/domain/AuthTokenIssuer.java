package com.aioj.next.auth.domain;

import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.contract.auth.TokenResponse;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuthTokenIssuer {
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;
    private final UserAccountService userAccountService;

    public AuthTokenIssuer(JwtTokenService jwtTokenService, JwtProperties jwtProperties,
                           UserAccountService userAccountService) {
        this.jwtTokenService = jwtTokenService;
        this.jwtProperties = jwtProperties;
        this.userAccountService = userAccountService;
    }

    public TokenResponse issue(UserAccount user) {
        String accessToken = jwtTokenService.createAccessToken(user.id(), user.account(), user.roles(),
                user.passwordResetRequired());
        String refreshToken = jwtTokenService.createRefreshToken(user.id(), user.account(), user.roles(),
                user.passwordResetRequired());
        Claims refreshClaims = jwtTokenService.parse(refreshToken);
        userAccountService.storeRefreshToken(user.id(), refreshToken, refreshClaims.getExpiration().toInstant());
        return new TokenResponse(accessToken, refreshToken, "Bearer", Instant.now().plus(jwtProperties.getAccessTtl()),
                user.id(), user.account(), user.displayName(), user.roles(), user.passwordResetRequired());
    }
}
