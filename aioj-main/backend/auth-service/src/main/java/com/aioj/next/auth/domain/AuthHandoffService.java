package com.aioj.next.auth.domain;

import com.aioj.next.auth.config.AuthHandoffProperties;
import com.aioj.next.auth.entity.AuthHandoffTicketEntity;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.mapper.AuthHandoffTicketMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.auth.HandoffExchangeResponse;
import com.aioj.next.contract.auth.HandoffTicketIssueRequest;
import com.aioj.next.contract.auth.HandoffTicketIssueResponse;
import com.aioj.next.contract.auth.TokenResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class AuthHandoffService {
    private static final String AUDIENCE = "web-user";
    private static final Pattern NEXT_PATH = Pattern.compile("^/problems/[1-9][0-9]*$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String INVALID_TICKET_MESSAGE = "Invalid or expired handoff ticket";

    private final AuthHandoffTicketMapper ticketMapper;
    private final UserMapper userMapper;
    private final UserAccountService userAccountService;
    private final AuthTokenIssuer authTokenIssuer;
    private final AuthHandoffProperties properties;

    public AuthHandoffService(AuthHandoffTicketMapper ticketMapper, UserMapper userMapper,
                              UserAccountService userAccountService, AuthTokenIssuer authTokenIssuer,
                              AuthHandoffProperties properties) {
        this.ticketMapper = ticketMapper;
        this.userMapper = userMapper;
        this.userAccountService = userAccountService;
        this.authTokenIssuer = authTokenIssuer;
        this.properties = properties;
    }

    @Transactional
    public HandoffTicketIssueResponse issue(Long userId, HandoffTicketIssueRequest request) {
        ensureEnabled();
        UserEntity user = eligibleUser(userId);
        String audience = request == null ? null : request.audience();
        String nextPath = request == null ? null : request.nextPath();
        if (!AUDIENCE.equals(audience)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported handoff audience");
        }
        if (nextPath == null || !NEXT_PATH.matcher(nextPath).matches()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid handoff nextPath");
        }

        Instant now = Instant.now();
        int limit = properties.getIssueLimitPerMinute();
        if (limit > 0) {
            long recent = ticketMapper.selectCount(new LambdaQueryWrapper<AuthHandoffTicketEntity>()
                    .eq(AuthHandoffTicketEntity::getUserId, userId)
                    .ge(AuthHandoffTicketEntity::getCreatedAt, now.minus(Duration.ofMinutes(1))));
            if (recent >= limit) {
                throw new DomainException(ErrorCode.TOO_MANY_REQUESTS, "Too many handoff tickets requested");
            }
        }

        Duration ttl = properties.getTtl();
        Duration maxTtl = properties.getMaxTtl();
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Invalid handoff TTL configuration");
        }
        if (maxTtl != null && !maxTtl.isNegative() && !maxTtl.isZero() && ttl.compareTo(maxTtl) > 0) {
            ttl = maxTtl;
        }
        String ticket = newTicket();
        AuthHandoffTicketEntity entity = new AuthHandoffTicketEntity();
        entity.setTicketHash(sha256(ticket));
        entity.setUserId(user.getId());
        entity.setAudience(audience);
        entity.setNextPath(nextPath);
        entity.setExpiresAt(now.plus(ttl));
        entity.setCreatedAt(now);
        ticketMapper.insert(entity);
        return new HandoffTicketIssueResponse(ticket, entity.getExpiresAt());
    }

    @Transactional
    public HandoffExchangeResponse exchange(String ticket) {
        ensureEnabled();
        if (ticket == null || ticket.isBlank()) {
            throw invalidTicket();
        }
        String ticketHash = sha256(ticket);
        AuthHandoffTicketEntity entity = ticketMapper.selectOne(new LambdaQueryWrapper<AuthHandoffTicketEntity>()
                .eq(AuthHandoffTicketEntity::getTicketHash, ticketHash));
        Instant now = Instant.now();
        if (entity == null || entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(now)
                || entity.getConsumedAt() != null || !AUDIENCE.equals(entity.getAudience())) {
            throw invalidTicket();
        }
        UserEntity user = eligibleUser(entity.getUserId());
        UserAccount account = userAccountService.getById(user.getId());
        if (account.passwordResetRequired()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Password reset is required");
        }
        if (ticketMapper.consume(ticketHash, now) != 1) {
            throw invalidTicket();
        }
        TokenResponse tokens = authTokenIssuer.issue(account);
        return new HandoffExchangeResponse(tokens, entity.getNextPath());
    }

    private UserEntity eligibleUser(Long userId) {
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null || user.getArchivedAt() != null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Account is not available for handoff");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Account is disabled. Please contact an administrator.");
        }
        if (Boolean.TRUE.equals(user.getPasswordResetRequired())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Password reset is required");
        }
        return user;
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Auth handoff is disabled");
        }
    }

    private DomainException invalidTicket() {
        return new DomainException(ErrorCode.UNAUTHORIZED, INVALID_TICKET_MESSAGE);
    }

    private String newTicket() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash handoff ticket", ex);
        }
    }
}
