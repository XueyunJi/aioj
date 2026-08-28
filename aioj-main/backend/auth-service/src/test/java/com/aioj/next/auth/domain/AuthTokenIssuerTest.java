package com.aioj.next.auth.domain;

import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.TokenResponse;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthTokenIssuerTest {
    @Mock
    private UserAccountService userAccountService;

    @Test
    void issueUsesCurrentPasswordResetStateAndStoresRefreshToken() {
        JwtProperties properties = new JwtProperties();
        properties.setAccessTtl(Duration.ofMinutes(15));
        properties.setHmacSecret("auth-token-issuer-test-secret-with-enough-length");
        JwtTokenService jwtTokenService = new JwtTokenService(properties);
        AuthTokenIssuer issuer = new AuthTokenIssuer(jwtTokenService, properties, userAccountService);

        UserAccount user = new UserAccount(42L, "student42", "hash", "Student 42", null, true,
                Set.of(Role.STUDENT), false);

        TokenResponse response = issuer.issue(user);

        Claims accessClaims = jwtTokenService.parse(response.accessToken());
        Claims refreshClaims = jwtTokenService.parse(response.refreshToken());
        assertEquals(JwtTokenService.TOKEN_TYPE_ACCESS, accessClaims.get("typ", String.class));
        assertEquals(JwtTokenService.TOKEN_TYPE_REFRESH, refreshClaims.get("typ", String.class));
        assertFalse(accessClaims.get("pwd_reset", Boolean.class));
        assertFalse(refreshClaims.get("pwd_reset", Boolean.class));
        assertFalse(response.passwordResetRequired());
        verify(userAccountService).storeRefreshToken(eq(42L), eq(response.refreshToken()), any(Instant.class));
    }
}
