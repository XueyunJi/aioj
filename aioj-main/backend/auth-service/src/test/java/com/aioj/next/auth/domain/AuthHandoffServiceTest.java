package com.aioj.next.auth.domain;

import com.aioj.next.auth.config.AuthHandoffProperties;
import com.aioj.next.auth.entity.AuthHandoffTicketEntity;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.mapper.AuthHandoffTicketMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.HandoffExchangeResponse;
import com.aioj.next.contract.auth.HandoffTicketIssueRequest;
import com.aioj.next.contract.auth.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthHandoffServiceTest {
    @Mock
    private AuthHandoffTicketMapper ticketMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserAccountService userAccountService;
    @Mock
    private AuthTokenIssuer authTokenIssuer;

    private AuthHandoffProperties properties;
    private AuthHandoffService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        properties = new AuthHandoffProperties();
        properties.setEnabled(true);
        properties.setTtl(Duration.ofSeconds(60));
        properties.setMaxTtl(Duration.ofSeconds(120));
        properties.setIssueLimitPerMinute(20);
        service = new AuthHandoffService(ticketMapper, userMapper, userAccountService, authTokenIssuer, properties);
        user = user(true, false, false, false);
        lenient().when(userMapper.selectById(1L)).thenReturn(user);
        lenient().when(userAccountService.getById(1L)).thenReturn(account());
        lenient().when(authTokenIssuer.issue(any())).thenReturn(tokens());
    }

    @Test
    void featureIsDisabledByDefaultAndCannotIssueOrExchange() {
        AuthHandoffProperties defaults = new AuthHandoffProperties();
        assertEquals(false, defaults.isEnabled());
        AuthHandoffService disabled = new AuthHandoffService(ticketMapper, userMapper, userAccountService,
                authTokenIssuer, defaults);

        DomainException error = assertThrows(DomainException.class,
                () -> disabled.issue(1L, new HandoffTicketIssueRequest("web-user", "/problems/123")));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        verify(ticketMapper, never()).insert(any(AuthHandoffTicketEntity.class));
    }

    @Test
    void issueUsesAuthenticatedUserAndStoresOnlyTicketHash() {
        final AuthHandoffTicketEntity[] stored = new AuthHandoffTicketEntity[1];
        doAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return 1;
        }).when(ticketMapper).insert(any(AuthHandoffTicketEntity.class));

        var response = service.issue(1L, new HandoffTicketIssueRequest("web-user", "/problems/123"));

        assertTrue(response.ticket().length() >= 40);
        assertNotEquals(response.ticket(), stored[0].getTicketHash());
        assertEquals(1L, stored[0].getUserId());
        assertEquals("/problems/123", stored[0].getNextPath());
        assertEquals("web-user", stored[0].getAudience());
    }

    @Test
    void issueRejectsUnsupportedAudienceAndUnsafeNextPaths() {
        assertThrows(DomainException.class,
                () -> service.issue(1L, new HandoffTicketIssueRequest("admin", "/problems/123")));
        for (String path : List.of("https://evil.example/problems/123", "//evil/problems/123",
                "/problems/0", "/problems/123/submit", "/users/1", "/problems/12/../13", "/problems/12\\x")) {
            assertThrows(DomainException.class,
                    () -> service.issue(1L, new HandoffTicketIssueRequest("web-user", path)), path);
        }
        verify(ticketMapper, never()).insert(any(AuthHandoffTicketEntity.class));
    }

    @Test
    void exchangeReturnsNextPathFromStoredTicketAndConsumesOnce() {
        AuthHandoffTicketEntity ticket = ticket("/problems/321", Instant.now().plusSeconds(60));
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        when(ticketMapper.consume(anyString(), any())).thenReturn(1);

        HandoffExchangeResponse response = service.exchange("opaque-ticket");

        assertEquals("/problems/321", response.nextPath());
        assertEquals("access", response.tokens().accessToken());
        assertEquals("refresh", response.tokens().refreshToken());
        verify(authTokenIssuer).issue(any(UserAccount.class));
    }

    @Test
    void expiredUnknownAndReplayedTicketsUseUnauthorizedSemantics() {
        when(ticketMapper.selectOne(any())).thenReturn(null);
        DomainException unknown = assertThrows(DomainException.class, () -> service.exchange("unknown"));

        AuthHandoffTicketEntity expired = ticket("/problems/123", Instant.now().minusSeconds(1));
        when(ticketMapper.selectOne(any())).thenReturn(expired);
        DomainException expiredError = assertThrows(DomainException.class, () -> service.exchange("expired"));

        AuthHandoffTicketEntity replayed = ticket("/problems/123", Instant.now().plusSeconds(60));
        replayed.setConsumedAt(Instant.now().minusSeconds(1));
        when(ticketMapper.selectOne(any())).thenReturn(replayed);
        DomainException replayedError = assertThrows(DomainException.class, () -> service.exchange("replayed"));

        assertEquals(ErrorCode.UNAUTHORIZED, unknown.errorCode());
        assertEquals(unknown.getMessage(), expiredError.getMessage());
        assertEquals(unknown.getMessage(), replayedError.getMessage());
        verify(authTokenIssuer, never()).issue(any());
    }

    @Test
    void concurrentExchangeAllowsExactlyOneAtomicConsumer() throws Exception {
        AuthHandoffTicketEntity ticket = ticket("/problems/123", Instant.now().plusSeconds(60));
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        AtomicBoolean consumed = new AtomicBoolean();
        when(ticketMapper.consume(anyString(), any())).thenAnswer(invocation -> consumed.compareAndSet(false, true) ? 1 : 0);

        int count = 8;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> {
                start.await();
                try {
                    service.exchange("same-ticket");
                    return true;
                } catch (DomainException error) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> results;
        try {
            start.countDown();
            results = executor.invokeAll(tasks);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, results.stream().filter(this::successful).count());
    }

    private boolean successful(Future<Boolean> result) {
        try {
            return result.get();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void unavailableAccountCannotIssueOrExchange() {
        for (UserEntity unavailable : List.of(
                user(false, false, false, false),
                user(true, true, false, false),
                user(true, false, true, false),
                user(true, false, false, true))) {
            when(userMapper.selectById(1L)).thenReturn(unavailable);
            assertThrows(DomainException.class,
                    () -> service.issue(1L, new HandoffTicketIssueRequest("web-user", "/problems/123")));
        }
        verify(ticketMapper, never()).insert(any(AuthHandoffTicketEntity.class));
    }

    @Test
    void unavailableAccountCannotExchangeAnOtherwiseValidTicket() {
        AuthHandoffTicketEntity ticket = ticket("/problems/123", Instant.now().plusSeconds(60));
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        for (UserEntity unavailable : List.of(
                user(false, false, false, false),
                user(true, true, false, false),
                user(true, false, true, false),
                user(true, false, false, true))) {
            when(userMapper.selectById(1L)).thenReturn(unavailable);
            assertThrows(DomainException.class, () -> service.exchange("ticket"));
        }
        verify(ticketMapper, never()).consume(anyString(), any());
        verify(authTokenIssuer, never()).issue(any());
    }

    @Test
    void tokenIssuanceFailureIsPropagatedAfterAtomicConsumeAttempt() {
        AuthHandoffTicketEntity ticket = ticket("/problems/123", Instant.now().plusSeconds(60));
        when(ticketMapper.selectOne(any())).thenReturn(ticket);
        when(ticketMapper.consume(anyString(), any())).thenReturn(1);
        RuntimeException failure = new RuntimeException("token issuer unavailable");
        when(authTokenIssuer.issue(any())).thenThrow(failure);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.exchange("ticket"));

        assertEquals(failure, error);
        verify(ticketMapper).consume(anyString(), any());
    }

    private AuthHandoffTicketEntity ticket(String nextPath, Instant expiresAt) {
        AuthHandoffTicketEntity entity = new AuthHandoffTicketEntity();
        entity.setTicketHash("hash");
        entity.setUserId(1L);
        entity.setAudience("web-user");
        entity.setNextPath(nextPath);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private UserEntity user(boolean enabled, boolean archived, boolean deleted, boolean reset) {
        UserEntity entity = new UserEntity();
        entity.setId(1L);
        entity.setAccount("student");
        entity.setDisplayName("Student");
        entity.setEnabled(enabled);
        entity.setArchivedAt(archived ? Instant.now() : null);
        entity.setDeletedAt(deleted ? Instant.now() : null);
        entity.setPasswordResetRequired(reset);
        return entity;
    }

    private UserAccount account() {
        return new UserAccount(1L, "student", "hash", "Student", null, true, Set.of(Role.STUDENT), false);
    }

    private TokenResponse tokens() {
        return new TokenResponse("access", "refresh", "Bearer", Instant.now().plusSeconds(60),
                1L, "student", "Student", Set.of(Role.STUDENT), false);
    }
}
