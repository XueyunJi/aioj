package com.aioj.next.auth.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.entity.UserRoleEntity;
import com.aioj.next.auth.mapper.RefreshTokenMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.auth.mapper.UserRoleMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.AdminUserBatchAction;
import com.aioj.next.contract.auth.AdminUserBatchActionRequest;
import com.aioj.next.contract.auth.AdminUserBatchCandidateRequest;
import com.aioj.next.contract.auth.AdminUserBatchCreateRequest;
import com.aioj.next.contract.auth.AdminUserBatchPasswordRequest;
import com.aioj.next.contract.auth.AdminUserBatchPreviewRequest;
import com.aioj.next.contract.auth.PasswordUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceLoginTest {
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RefreshTokenMapper refreshTokenMapper;

    private UserAccountService service;
    private InMemoryLoginAttemptGuard loginAttemptGuard;

    @BeforeEach
    void setUp() {
        loginAttemptGuard = new InMemoryLoginAttemptGuard(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        service = new UserAccountService(passwordEncoder, userMapper, userRoleMapper, refreshTokenMapper, loginAttemptGuard);
    }

    @Test
    void repeatedLoginFailuresLockTheAccountEvenForCorrectPassword() {
        UserEntity user = user(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        InMemoryLoginAttemptGuard strictGuard = new InMemoryLoginAttemptGuard(2, Duration.ofMinutes(15), Duration.ofMinutes(15));
        UserAccountService guarded = new UserAccountService(passwordEncoder, userMapper, userRoleMapper, refreshTokenMapper, strictGuard);

        assertThrows(DomainException.class, () -> guarded.login("student", "wrong-1"));
        assertThrows(DomainException.class, () -> guarded.login("student", "wrong-2"));

        // The lock triggers before any credential check, so even a correct password is refused.
        DomainException locked = assertThrows(DomainException.class, () -> guarded.login("student", "secret"));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, locked.errorCode());
    }

    @Test
    void loginLockExpiresAfterLockDuration() {
        UserEntity user = user(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-04T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        InMemoryLoginAttemptGuard timedGuard = new InMemoryLoginAttemptGuard(1, Duration.ofMinutes(15), Duration.ofMinutes(15), clock);
        UserAccountService guarded = new UserAccountService(passwordEncoder, userMapper, userRoleMapper, refreshTokenMapper, timedGuard);

        assertThrows(DomainException.class, () -> guarded.login("student", "wrong"));
        assertThrows(DomainException.class, () -> guarded.login("student", "secret"));

        now.set(Instant.parse("2026-08-04T00:16:00Z"));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRoleEntity(1L, Role.STUDENT)));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        UserAccount account = guarded.login("student", "secret");
        assertEquals("student", account.account());
    }

    @Test
    void successfulLoginClearsEarlierFailures() {
        UserEntity user = user(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRoleEntity(1L, Role.STUDENT)));
        InMemoryLoginAttemptGuard strictGuard = new InMemoryLoginAttemptGuard(2, Duration.ofMinutes(15), Duration.ofMinutes(15));
        UserAccountService guarded = new UserAccountService(passwordEncoder, userMapper, userRoleMapper, refreshTokenMapper, strictGuard);

        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        assertThrows(DomainException.class, () -> guarded.login("student", "wrong"));

        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        guarded.login("student", "secret");

        when(passwordEncoder.matches("wrong2", "hash")).thenReturn(false);
        assertThrows(DomainException.class, () -> guarded.login("student", "wrong2"));
        // Still below the threshold after the reset, so a correct password works again.
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        guarded.login("student", "secret");
    }

    @Test
    void loginWithDisabledAccountAndCorrectPasswordReturnsDisabledAccountMessage() {
        UserEntity user = user(false);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        DomainException error = assertThrows(DomainException.class, () -> service.login("student", "secret"));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
        assertEquals("Account is disabled. Please contact an administrator.", error.getMessage());
    }

    @Test
    void loginWithDisabledAccountAndWrongPasswordStillReturnsGenericCredentialFailure() {
        UserEntity user = user(false);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        DomainException error = assertThrows(DomainException.class, () -> service.login("student", "wrong"));

        assertEquals(ErrorCode.UNAUTHORIZED, error.errorCode());
        assertEquals("Invalid account or password", error.getMessage());
    }

    @Test
    void updatePasswordClearsForcedResetFlag() {
        UserEntity user = user(true);
        user.setPasswordResetRequired(true);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRoleEntity(1L, Role.STUDENT)));
        when(passwordEncoder.matches("old-password", "hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");
        UserAccountService serviceWithoutRealRefreshTokenUpdate = spy(service);
        doNothing().when(serviceWithoutRealRefreshTokenUpdate).revokeUserRefreshTokens(1L);

        UserAccount updated = serviceWithoutRealRefreshTokenUpdate.updatePassword(1L, new PasswordUpdateRequest("old-password", "new-password"));

        assertEquals("new-hash", user.getPasswordHash());
        assertFalse(Boolean.TRUE.equals(user.getPasswordResetRequired()));
        assertFalse(updated.passwordResetRequired());
        verify(userMapper).updateById(user);
        verify(serviceWithoutRealRefreshTokenUpdate).revokeUserRefreshTokens(1L);
    }

    @Test
    void updatePasswordRejectsNewPasswordSameAsCurrentPassword() {
        UserEntity user = user(true);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("old-password", "hash")).thenReturn(true);
        when(passwordEncoder.matches("same-password", "hash")).thenReturn(true);
        UserAccountService serviceWithoutRealRefreshTokenUpdate = spy(service);

        DomainException error = assertThrows(DomainException.class,
                () -> serviceWithoutRealRefreshTokenUpdate.updatePassword(1L,
                        new PasswordUpdateRequest("old-password", "same-password")));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("New password cannot be the same as the current password", error.getMessage());
        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).updateById(any(UserEntity.class));
        verify(serviceWithoutRealRefreshTokenUpdate, never()).revokeUserRefreshTokens(1L);
    }

    @Test
    void batchPreviewReportsDuplicatesAndInvalidRowsBeforeCreatingUsers() {
        var response = service.previewBatchUsers(new AdminUserBatchPreviewRequest(
                "TEXT",
                null,
                null,
                null,
                null,
                """
                        k6001,Alice,alice@example.com
                        k6001,Bob,bob@example.com
                        ,Missing Account
                        k6002,Carol,not-an-email
                        """,
                Set.of(Role.STUDENT),
                true,
                true,
                null
        ));

        assertEquals(4, response.total());
        assertEquals(1, response.valid());
        assertEquals(3, response.invalid());
        assertEquals("k6001", response.items().get(0).account());
        assertEquals(true, response.items().get(0).passwordResetRequired());
        assertEquals(true, response.items().get(1).duplicateInBatch());
        assertEquals(false, response.items().get(2).valid());
        assertEquals(false, response.items().get(3).valid());
        assertEquals("邮箱格式不正确。请填写类似 name@example.com 的邮箱，或清空该字段。", response.items().get(3).errors().get(0));
    }

    @Test
    void batchPreviewKeepsChineseNamesAndReportsCandidateEmailErrorsByRow() {
        var response = service.previewBatchUsers(new AdminUserBatchPreviewRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of(Role.STUDENT),
                true,
                true,
                List.of(
                        new AdminUserBatchCandidateRequest("2405024101", "艾振鹏", "ai＠example。com", Set.of(Role.STUDENT), true, true),
                        new AdminUserBatchCandidateRequest("2405024102", "曹思毅", "bad-email", Set.of(Role.STUDENT), true, true)
                )
        ));

        assertEquals(2, response.total());
        assertEquals(1, response.valid());
        assertEquals(1, response.invalid());
        assertEquals("艾振鹏", response.items().get(0).displayName());
        assertEquals("ai@example.com", response.items().get(0).email());
        assertEquals("曹思毅", response.items().get(1).displayName());
        assertEquals("bad-email", response.items().get(1).email());
        assertEquals("邮箱格式不正确。请填写类似 name@example.com 的邮箱，或清空该字段。", response.items().get(1).errors().get(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void batchPreviewChecksExistingAccountsOnlyAmongNonDeletedUsers() {
        service.previewBatchUsers(new AdminUserBatchPreviewRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of(Role.STUDENT),
                true,
                true,
                List.of(new AdminUserBatchCandidateRequest("reused001", "Reused Student", null, Set.of(Role.STUDENT), true, true))
        ));

        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectOne(queryCaptor.capture());
        initUserTableInfo();
        String sqlSegment = queryCaptor.getValue().getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sqlSegment.contains("deleted_at"));
        assertTrue(sqlSegment.contains("is null"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void seedUsersUsesAnyAccountLookupToAvoidRecreatingDeletedDefaultsWithDefaultPassword() {
        when(userMapper.selectOne(any())).thenReturn(user(true));

        service.seedUsers();

        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper, times(3)).selectOne(queryCaptor.capture());
        initUserTableInfo();
        for (LambdaQueryWrapper wrapper : queryCaptor.getAllValues()) {
            String sqlSegment = wrapper.getSqlSegment().toLowerCase(Locale.ROOT);
            assertFalse(sqlSegment.contains("deleted_at"));
        }
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void seedUsersCreatesNewSeedsWithForcedPasswordReset() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        service.seedUsers();

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper, times(3)).insert(captor.capture());
        for (UserEntity seeded : captor.getAllValues()) {
            assertEquals(true, seeded.getPasswordResetRequired());
        }
    }

    @Test
    void seedUsersFlagsExistingAccountStillUsingDefaultPassword() {
        UserEntity existing = user(true);
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(passwordEncoder.matches(any(), org.mockito.ArgumentMatchers.eq("hash"))).thenReturn(true);

        service.seedUsers();

        assertEquals(true, existing.getPasswordResetRequired());
        // First matching seed flips the flag; the remaining two see the flag already set
        // and skip thanks to the idempotency guard.
        verify(userMapper, times(1)).updateById(existing);
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    void seedUsersLeavesExistingAccountAloneAfterPasswordRotation() {
        UserEntity existing = user(true);
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(passwordEncoder.matches(any(), org.mockito.ArgumentMatchers.eq("hash"))).thenReturn(false);

        service.seedUsers();

        assertFalse(Boolean.TRUE.equals(existing.getPasswordResetRequired()));
        verify(userMapper, never()).updateById(any(UserEntity.class));
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    private void initUserTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserEntity.class);
    }

    @Test
    void batchCreateCreatesValidStudentsWithForcedResetAndDoesNotReturnPasswords() {
        AtomicLong ids = new AtomicLong(100);
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        doAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(ids.incrementAndGet());
            return 1;
        }).when(userMapper).insert(any(UserEntity.class));

        var response = service.createBatchUsers(new AdminUserBatchCreateRequest(
                List.of(
                        new AdminUserBatchCandidateRequest("k6001", "Alice", "alice@example.com", Set.of(Role.STUDENT), true, true),
                        new AdminUserBatchCandidateRequest("k6002", "Bob", null, Set.of(Role.STUDENT), true, true),
                        new AdminUserBatchCandidateRequest("", "Broken", null, Set.of(Role.STUDENT), true, true)
                ),
                new AdminUserBatchPasswordRequest("ACCOUNT_SUFFIX", null, "#Init123")
        ));

        assertEquals(3, response.requested());
        assertEquals(2, response.created());
        assertEquals(1, response.skipped());
        assertEquals("CREATED", response.results().get(0).status());
        assertEquals("Alice", response.results().get(0).user().displayName());
        assertEquals("SKIPPED", response.results().get(2).status());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper, org.mockito.Mockito.times(2)).insert(userCaptor.capture());
        assertEquals(List.of("k6001", "k6002"), userCaptor.getAllValues().stream().map(UserEntity::getAccount).toList());
        assertEquals(true, userCaptor.getAllValues().get(0).getPasswordResetRequired());
        verify(userRoleMapper, org.mockito.Mockito.times(2)).insert(any(UserRoleEntity.class));
    }

    @Test
    void batchPreviewRejectsInvalidSequencePrefixWithReadableMessage() {
        DomainException error = assertThrows(DomainException.class, () -> service.previewBatchUsers(new AdminUserBatchPreviewRequest(
                "SEQUENCE",
                "stu#",
                1,
                3,
                3,
                null,
                Set.of(Role.STUDENT),
                true,
                true,
                null
        )));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("账号前缀只能包含英文字母、数字、点、下划线、短横线或 @。", error.getMessage());
    }

    @Test
    void batchCreateReportsShortAccountSuffixPasswordClearly() {
        DomainException error = assertThrows(DomainException.class, () -> service.createBatchUsers(new AdminUserBatchCreateRequest(
                List.of(new AdminUserBatchCandidateRequest("stu001", "Student 1", null, Set.of(Role.STUDENT), true, true)),
                new AdminUserBatchPasswordRequest("ACCOUNT_SUFFIX", null, "@")
        )));

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode());
        assertEquals("初始密码必须为 8-128 位；如果使用“账号 + 后缀”，请加长密码后缀。", error.getMessage());
    }

    @Test
    void batchActionDoesNotResetCurrentAdminPassword() {
        UserEntity user = user(true);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRoleEntity(1L, Role.ADMIN)));

        var response = service.applyBatchAction(1L, new AdminUserBatchActionRequest(
                Set.of(1L),
                AdminUserBatchAction.RESET_PASSWORD,
                new AdminUserBatchPasswordRequest("FIXED", "TempPass123", null),
                true
        ));

        assertEquals(1, response.requested());
        assertEquals(0, response.succeeded());
        assertEquals(1, response.failed());
        assertEquals("FAILED", response.results().get(0).status());
        assertEquals("不能对当前登录管理员自己执行该操作。", response.results().get(0).message());
    }

    @Test
    void batchActionRestoreArchivedUserClearsArchivedAtWithExplicitUpdate() {
        UserEntity user = user(false);
        user.setArchivedAt(Instant.now());
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(new UserRoleEntity(1L, Role.STUDENT)));

        var response = service.applyBatchAction(99L, new AdminUserBatchActionRequest(
                Set.of(1L),
                AdminUserBatchAction.RESTORE,
                null,
                null
        ));

        assertEquals(1, response.succeeded());
        assertNull(user.getArchivedAt());
        assertTrue(Boolean.TRUE.equals(user.getEnabled()));
        assertNull(response.results().get(0).user().archivedAt());
        assertTrue(response.results().get(0).user().enabled());
        verify(userMapper).update(isNull(), any());
        verify(userMapper, never()).updateById(user);
    }

    private UserEntity user(boolean enabled) {
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setAccount("student");
        user.setPasswordHash("hash");
        user.setDisplayName("Demo Student");
        user.setEnabled(enabled);
        user.setPasswordResetRequired(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }
}
