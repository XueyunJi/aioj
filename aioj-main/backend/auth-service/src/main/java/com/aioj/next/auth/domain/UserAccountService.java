package com.aioj.next.auth.domain;

import com.aioj.next.auth.entity.RefreshTokenEntity;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.entity.UserRoleEntity;
import com.aioj.next.auth.mapper.RefreshTokenMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.auth.mapper.UserRoleMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.contract.auth.AdminUserCreateRequest;
import com.aioj.next.contract.auth.AdminUserBatchAction;
import com.aioj.next.contract.auth.AdminUserBatchActionRequest;
import com.aioj.next.contract.auth.AdminUserBatchActionResponse;
import com.aioj.next.contract.auth.AdminUserBatchActionResultItem;
import com.aioj.next.contract.auth.AdminUserBatchCandidateRequest;
import com.aioj.next.contract.auth.AdminUserBatchCreateRequest;
import com.aioj.next.contract.auth.AdminUserBatchCreateResponse;
import com.aioj.next.contract.auth.AdminUserBatchPreviewItem;
import com.aioj.next.contract.auth.AdminUserBatchPreviewRequest;
import com.aioj.next.contract.auth.AdminUserBatchPreviewResponse;
import com.aioj.next.contract.auth.AdminUserBatchResultItem;
import com.aioj.next.contract.auth.AdminUserBatchPasswordRequest;
import com.aioj.next.contract.auth.AdminUserResponse;
import com.aioj.next.contract.auth.AdminUserUpdateRequest;
import com.aioj.next.contract.auth.DailyUserActivityResponse;
import com.aioj.next.contract.auth.PasswordUpdateRequest;
import com.aioj.next.contract.auth.UserProfileResponse;
import com.aioj.next.contract.auth.UserUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class UserAccountService {
    private static final Set<Role> ALLOWED_ROLES = Set.of(Role.STUDENT, Role.TEACHER, Role.ADMIN);
    static final String DISABLED_ACCOUNT_MESSAGE = "Account is disabled. Please contact an administrator.";
    private static final int MAX_BATCH_USERS = 200;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Za-z0-9._@-]{3,64}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String ACCOUNT_RULE_MESSAGE = "账号必须为 3-64 个字符，只能包含英文字母、数字、点、下划线、短横线或 @。";
    private static final String NEW_PASSWORD_SAME_AS_CURRENT_MESSAGE = "New password cannot be the same as the current password";

    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final LoginAttemptGuard loginAttemptGuard;

    public UserAccountService(PasswordEncoder passwordEncoder, UserMapper userMapper, UserRoleMapper userRoleMapper,
                              RefreshTokenMapper refreshTokenMapper, LoginAttemptGuard loginAttemptGuard) {
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    @PostConstruct
    void seedUsers() {
        seedAccount("admin", "Admin@123456", "Platform Admin", "admin@example.edu",
                Set.of(Role.ADMIN, Role.TEACHER));
        seedAccount("teacher", "Teacher@123456", "Demo Teacher", "teacher@example.edu", Set.of(Role.TEACHER));
        seedAccount("student", "Student@123456", "Demo Student", "student@example.edu", Set.of(Role.STUDENT));
    }

    @Transactional
    public UserAccount register(String account, String rawPassword, String displayName, String email) {
        return register(account, rawPassword, displayName, email, Role.STUDENT);
    }

    @Transactional
    public UserAccount register(String account, String rawPassword, String displayName, String email, Role requestedRole) {
        if (findActiveUserByAccount(account) != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Account already exists");
        }
        // Public registration only ever creates student accounts. Teacher and admin
        // accounts are provisioned through the audited admin user-management flows.
        if (requestedRole != null && requestedRole != Role.STUDENT) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Public registration only supports the student role");
        }
        UserEntity user = newUser(account, rawPassword, displayName, email, true);
        userMapper.insert(user);
        replaceRoles(user.getId(), Set.of(Role.STUDENT));
        return toAccount(user, Set.of(Role.STUDENT));
    }

    public UserAccount login(String account, String rawPassword) {
        loginAttemptGuard.ensureNotLocked(account);
        UserEntity user = findActiveUserByAccount(account);
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginAttemptGuard.recordFailure(account);
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Invalid account or password");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, DISABLED_ACCOUNT_MESSAGE);
        }
        loginAttemptGuard.recordSuccess(account);
        return toAccount(user, rolesForUser(user.getId()));
    }

    public UserAccount getById(Long userId) {
        UserEntity user = requireUser(userId);
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, DISABLED_ACCOUNT_MESSAGE);
        }
        return toAccount(user, rolesForUser(userId));
    }

    public UserProfileResponse getProfile(Long userId) {
        UserAccount user = getById(userId);
        return new UserProfileResponse(user.id(), user.account(), user.displayName(), user.email(), user.roles(),
                user.passwordResetRequired());
    }

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UserUpdateRequest request) {
        UserEntity user = requireUser(userId);
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, DISABLED_ACCOUNT_MESSAGE);
        }
        user.setDisplayName(request.displayName());
        user.setEmail(normalizeBlank(request.email()));
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);
        return getProfile(userId);
    }

    @Transactional
    public UserAccount updatePassword(Long userId, PasswordUpdateRequest request) {
        UserEntity user = requireUser(userId);
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, DISABLED_ACCOUNT_MESSAGE);
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, NEW_PASSWORD_SAME_AS_CURRENT_MESSAGE);
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordResetRequired(false);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);
        revokeUserRefreshTokens(userId);
        return toAccount(user, rolesForUser(userId));
    }

    @Transactional
    public void storeRefreshToken(Long userId, String refreshToken, Instant expiresAt) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(sha256(refreshToken));
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(Instant.now());
        refreshTokenMapper.insert(entity);
    }

    @Transactional
    public UserAccount refresh(String refreshToken, Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        RefreshTokenEntity token = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, sha256(refreshToken)));
        if (token == null || !Objects.equals(token.getUserId(), userId)
                || token.getRevokedAt() != null || !token.getExpiresAt().isAfter(Instant.now())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token is invalid");
        }
        token.setRevokedAt(Instant.now());
        refreshTokenMapper.updateById(token);
        return getById(userId);
    }

    @Transactional
    public boolean revokeRefreshToken(String refreshToken) {
        RefreshTokenEntity token = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, sha256(refreshToken)));
        if (token == null || token.getRevokedAt() != null) {
            return false;
        }
        token.setRevokedAt(Instant.now());
        refreshTokenMapper.updateById(token);
        return true;
    }

    public PageResponse<AdminUserResponse> listUsers(long page, long pageSize, String search, Role role, Boolean enabled,
                                                     String lifecycle) {
        long safePage = Math.max(page, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);
        Set<Long> roleUserIds = null;
        if (role != null) {
            roleUserIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                            .eq(UserRoleEntity::getRole, role))
                    .stream()
                    .map(UserRoleEntity::getUserId)
                    .collect(Collectors.toSet());
            if (roleUserIds.isEmpty()) {
                return new PageResponse<>(List.of(), 0, safePage, safePageSize);
            }
        }

        String normalizedLifecycle = hasText(lifecycle) ? lifecycle.trim().toUpperCase() : "ACTIVE";
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<UserEntity>()
                .isNull(UserEntity::getDeletedAt)
                .isNotNull("ARCHIVED".equals(normalizedLifecycle), UserEntity::getArchivedAt)
                .isNull(!"ARCHIVED".equals(normalizedLifecycle), UserEntity::getArchivedAt)
                .eq(enabled != null, UserEntity::getEnabled, enabled)
                .in(roleUserIds != null, UserEntity::getId, roleUserIds)
                .and(hasText(search), query -> query
                        .like(UserEntity::getAccount, search)
                        .or()
                        .like(UserEntity::getDisplayName, search)
                        .or()
                        .like(UserEntity::getEmail, search))
                .orderByDesc(UserEntity::getCreatedAt);

        Page<UserEntity> result = userMapper.selectPage(new Page<>(safePage, safePageSize), wrapper);
        Map<Long, Set<Role>> roles = rolesForUsers(result.getRecords().stream().map(UserEntity::getId).toList());
        List<AdminUserResponse> records = result.getRecords().stream()
                .map(user -> toAdminResponse(user, roles.getOrDefault(user.getId(), Set.of())))
                .toList();
        return new PageResponse<>(records, result.getTotal(), safePage, safePageSize);
    }

    public AdminUserResponse getAdminUser(Long userId) {
        UserEntity user = requireUser(userId);
        return toAdminResponse(user, rolesForUser(userId));
    }

    @Transactional
    public AdminUserResponse createAdminUser(AdminUserCreateRequest request) {
        if (!validAccount(request.account())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, ACCOUNT_RULE_MESSAGE);
        }
        if (findActiveUserByAccount(request.account()) != null) {
            throw new DomainException(ErrorCode.CONFLICT, "Account already exists");
        }
        Set<Role> roles = requireRoles(request.roles());
        UserEntity user = newUser(request.account(), request.password(), request.displayName(), request.email(),
                request.enabled() == null || request.enabled(), Boolean.TRUE.equals(request.passwordResetRequired()));
        userMapper.insert(user);
        replaceRoles(user.getId(), roles);
        return toAdminResponse(user, roles);
    }

    @Transactional
    public AdminUserResponse updateAdminUser(Long userId, AdminUserUpdateRequest request) {
        return updateAdminUser(userId, request, null);
    }

    @Transactional
    public AdminUserResponse updateAdminUser(Long userId, AdminUserUpdateRequest request, Long actorUserId) {
        UserEntity user = requireUser(userId);
        Set<Role> previousRoles = rolesForUser(userId);
        Set<Role> roles = requireRoles(request.roles());
        boolean enabled = request.enabled() == null ? Boolean.TRUE.equals(user.getEnabled()) : request.enabled();
        if (actorUserId != null && Objects.equals(actorUserId, userId)
                && (!enabled || !roles.contains(Role.ADMIN))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "不能禁用或移除当前登录管理员自己的管理员权限。");
        }
        guardUsableAdminRemoval(user, previousRoles, enabled, roles);
        user.setDisplayName(request.displayName());
        user.setEmail(normalizeBlank(request.email()));
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.passwordResetRequired() != null) {
            user.setPasswordResetRequired(request.passwordResetRequired());
        }
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);
        replaceRoles(userId, roles);
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            revokeUserRefreshTokens(userId);
        }
        return toAdminResponse(user, roles);
    }

    public AdminUserBatchPreviewResponse previewBatchUsers(AdminUserBatchPreviewRequest request) {
        List<AdminUserBatchCandidateRequest> candidates = batchCandidates(request);
        List<AdminUserBatchPreviewItem> items = validateBatchCandidates(candidates);
        int valid = (int) items.stream().filter(AdminUserBatchPreviewItem::valid).count();
        return new AdminUserBatchPreviewResponse(items.size(), valid, items.size() - valid, items);
    }

    @Transactional
    public AdminUserBatchCreateResponse createBatchUsers(AdminUserBatchCreateRequest request) {
        List<AdminUserBatchCandidateRequest> users = request.users() == null ? List.of() : request.users();
        if (users.isEmpty() || users.size() > MAX_BATCH_USERS) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Batch user count must be between 1 and 200");
        }
        Set<Role> defaultRoles = defaultRoles(null);
        List<AdminUserBatchCandidateRequest> normalizedUsers = users.stream()
                .map(user -> normalizeCandidate(user, defaultRoles, true, true))
                .toList();
        List<AdminUserBatchPreviewItem> preview = validateBatchCandidates(normalizedUsers);
        List<AdminUserBatchResultItem> results = new ArrayList<>();
        int created = 0;
        for (AdminUserBatchPreviewItem item : preview) {
            if (!item.valid()) {
                results.add(new AdminUserBatchResultItem(item.rowNumber(), item.account(), "SKIPPED",
                        String.join("; ", item.errors()), null));
                continue;
            }
            String password = batchPassword(request.password(), item.account());
            Set<Role> roles = requireRoles(item.roles());
            UserEntity user = newUser(item.account(), password, item.displayName(), item.email(), item.enabled(),
                    item.passwordResetRequired());
            userMapper.insert(user);
            replaceRoles(user.getId(), roles);
            created++;
            results.add(new AdminUserBatchResultItem(item.rowNumber(), item.account(), "CREATED", "created",
                    toAdminResponse(user, roles)));
        }
        return new AdminUserBatchCreateResponse(normalizedUsers.size(), created, normalizedUsers.size() - created, results);
    }

    @Transactional
    public AdminUserBatchActionResponse applyBatchAction(Long actorUserId, AdminUserBatchActionRequest request) {
        if (request == null || request.userIds() == null || request.userIds().isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "请选择至少一个用户。");
        }
        AdminUserBatchAction action = request.action();
        if (action == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "请选择批量操作。");
        }
        if (action == AdminUserBatchAction.RESET_PASSWORD && request.password() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "请配置重置后的临时密码策略。");
        }
        List<AdminUserBatchActionResultItem> results = new ArrayList<>();
        int succeeded = 0;
        for (Long userId : request.userIds()) {
            UserEntity user = null;
            try {
                user = requireUser(userId);
                applyOneBatchAction(actorUserId, user, action, request);
                Set<Role> roles = rolesForUser(user.getId());
                succeeded++;
                results.add(new AdminUserBatchActionResultItem(user.getId(), user.getAccount(), "OK",
                        "操作已完成", toAdminResponse(user, roles)));
            } catch (DomainException ex) {
                results.add(new AdminUserBatchActionResultItem(userId, user == null ? null : user.getAccount(),
                        "FAILED", ex.getMessage(), user == null ? null : toAdminResponse(user, rolesForUser(user.getId()))));
            }
        }
        return new AdminUserBatchActionResponse(request.userIds().size(), succeeded,
                request.userIds().size() - succeeded, results);
    }

    public List<DailyUserActivityResponse> userActivity(int days) {
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startDate = today.minusDays(safeDays - 1L);
        Instant start = startDate.atStartOfDay(ZONE).toInstant();
        Map<String, Long> active = dailyCount(refreshTokenMapper.selectMaps(new QueryWrapper<RefreshTokenEntity>()
                .select("DATE(created_at) AS day", "COUNT(DISTINCT user_id) AS total")
                .ge("created_at", start)
                .groupBy("DATE(created_at)")));
        Map<String, Long> created = dailyCount(userMapper.selectMaps(new QueryWrapper<UserEntity>()
                .select("DATE(created_at) AS day", "COUNT(*) AS total")
                .ge("created_at", start)
                .groupBy("DATE(created_at)")));
        List<DailyUserActivityResponse> result = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            String day = startDate.plusDays(i).toString();
            result.add(new DailyUserActivityResponse(day, active.getOrDefault(day, 0L), created.getOrDefault(day, 0L)));
        }
        return result;
    }

    @Transactional
    public void disableUser(Long userId) {
        disableUser(userId, null);
    }

    @Transactional
    public void disableUser(Long userId, Long actorUserId) {
        UserEntity user = requireUser(userId);
        if (actorUserId != null && Objects.equals(actorUserId, userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "不能禁用当前登录管理员自己。");
        }
        Set<Role> roles = rolesForUser(userId);
        guardUsableAdminRemoval(user, roles, false, roles);
        if (Boolean.TRUE.equals(user.getEnabled())) {
            user.setEnabled(false);
            user.setUpdatedAt(Instant.now());
            userMapper.updateById(user);
        }
        revokeUserRefreshTokens(userId);
    }

    private void applyOneBatchAction(Long actorUserId, UserEntity user, AdminUserBatchAction action,
                                     AdminUserBatchActionRequest request) {
        if (actorUserId != null && Objects.equals(actorUserId, user.getId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "不能对当前登录管理员自己执行该操作。");
        }
        Set<Role> roles = rolesForUser(user.getId());
        Instant now = Instant.now();
        switch (action) {
            case ENABLE -> {
                if (user.getArchivedAt() != null) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "该用户已归档，请先恢复归档后再启用。");
                }
                user.setEnabled(true);
                user.setUpdatedAt(now);
                userMapper.updateById(user);
            }
            case DISABLE -> {
                guardUsableAdminRemoval(user, roles, false, roles);
                user.setEnabled(false);
                user.setUpdatedAt(now);
                userMapper.updateById(user);
                revokeUserRefreshTokens(user.getId());
            }
            case ARCHIVE -> {
                guardUsableAdminRemoval(user, roles, false, roles);
                user.setEnabled(false);
                user.setArchivedAt(now);
                user.setUpdatedAt(now);
                userMapper.updateById(user);
                revokeUserRefreshTokens(user.getId());
            }
            case RESTORE -> {
                if (user.getArchivedAt() == null) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "该用户未归档，无需恢复。");
                }
                user.setArchivedAt(null);
                user.setEnabled(true);
                user.setUpdatedAt(now);
                userMapper.update(null, new LambdaUpdateWrapper<UserEntity>()
                        .eq(UserEntity::getId, user.getId())
                        .set(UserEntity::getArchivedAt, null)
                        .set(UserEntity::getEnabled, true)
                        .set(UserEntity::getUpdatedAt, now));
            }
            case DELETE_ARCHIVED -> {
                if (user.getArchivedAt() == null) {
                    throw new DomainException(ErrorCode.BAD_REQUEST, "只能删除已归档用户，请先归档。");
                }
                guardUsableAdminRemoval(user, roles, false, roles);
                user.setEnabled(false);
                user.setDeletedAt(now);
                user.setDeletedBy(actorUserId);
                user.setUpdatedAt(now);
                userMapper.updateById(user);
                revokeUserRefreshTokens(user.getId());
            }
            case RESET_PASSWORD -> {
                String password = batchPassword(request.password(), user.getAccount());
                user.setPasswordHash(passwordEncoder.encode(password));
                user.setPasswordResetRequired(request.passwordResetRequired() == null || request.passwordResetRequired());
                user.setUpdatedAt(now);
                userMapper.updateById(user);
                revokeUserRefreshTokens(user.getId());
            }
        }
    }

    private void guardUsableAdminRemoval(UserEntity user, Set<Role> currentRoles, boolean nextEnabled,
                                         Set<Role> nextRoles) {
        boolean currentlyUsableAdmin = usableAdmin(user, currentRoles);
        boolean remainsUsableAdmin = user.getArchivedAt() == null && user.getDeletedAt() == null
                && nextEnabled && nextRoles.contains(Role.ADMIN);
        if (currentlyUsableAdmin && !remainsUsableAdmin && countOtherUsableAdmins(user.getId()) == 0) {
            throw new DomainException(ErrorCode.FORBIDDEN, "至少需要保留一个启用且未归档的管理员。");
        }
    }

    private boolean usableAdmin(UserEntity user, Set<Role> roles) {
        return user.getDeletedAt() == null
                && user.getArchivedAt() == null
                && Boolean.TRUE.equals(user.getEnabled())
                && roles.contains(Role.ADMIN);
    }

    private long countOtherUsableAdmins(Long excludedUserId) {
        List<Long> adminUserIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                        .eq(UserRoleEntity::getRole, Role.ADMIN))
                .stream()
                .map(UserRoleEntity::getUserId)
                .filter(id -> !Objects.equals(id, excludedUserId))
                .toList();
        if (adminUserIds.isEmpty()) {
            return 0;
        }
        return userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getId, adminUserIds)
                .eq(UserEntity::getEnabled, true)
                .isNull(UserEntity::getArchivedAt)
                .isNull(UserEntity::getDeletedAt));
    }

    private void seedAccount(String account, String defaultPassword, String displayName, String email, Set<Role> roles) {
        UserEntity existing = findAnyUserByAccount(account);
        if (existing == null) {
            UserEntity user = newUser(account, defaultPassword, displayName, email, true, true);
            userMapper.insert(user);
            replaceRoles(user.getId(), roles);
            return;
        }
        // Seed accounts still carrying the publicly known default password must rotate it
        // before any other API becomes usable.
        if (!Boolean.TRUE.equals(existing.getPasswordResetRequired())
                && passwordEncoder.matches(defaultPassword, existing.getPasswordHash())) {
            existing.setPasswordResetRequired(true);
            existing.setUpdatedAt(Instant.now());
            userMapper.updateById(existing);
        }
    }

    private UserEntity newUser(String account, String rawPassword, String displayName, String email, boolean enabled) {
        return newUser(account, rawPassword, displayName, email, enabled, false);
    }

    private UserEntity newUser(String account, String rawPassword, String displayName, String email, boolean enabled,
                               boolean passwordResetRequired) {
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setAccount(account);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setEmail(normalizeBlank(email));
        user.setEnabled(enabled);
        user.setPasswordResetRequired(passwordResetRequired);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
        }
        return user;
    }

    private UserEntity findActiveUserByAccount(String account) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getAccount, account)
                .isNull(UserEntity::getDeletedAt));
    }

    private UserEntity findAnyUserByAccount(String account) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getAccount, account));
    }

    private Set<Role> rolesForUser(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                        .eq(UserRoleEntity::getUserId, userId))
                .stream()
                .map(UserRoleEntity::getRole)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }

    private Map<Long, Set<Role>> rolesForUsers(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                        .in(UserRoleEntity::getUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(UserRoleEntity::getUserId,
                        Collectors.mapping(UserRoleEntity::getRole,
                                Collectors.toCollection(() -> EnumSet.noneOf(Role.class)))));
    }

    private void replaceRoles(Long userId, Set<Role> roles) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getUserId, userId));
        roles.forEach(role -> userRoleMapper.insert(new UserRoleEntity(userId, role)));
    }

    private Set<Role> requireRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty() || !ALLOWED_ROLES.containsAll(roles)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid roles");
        }
        Set<Role> normalized = roles.stream().collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        if (normalized.contains(Role.STUDENT) && normalized.contains(Role.TEACHER)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Student and teacher roles are mutually exclusive");
        }
        return normalized;
    }

    private UserAccount toAccount(UserEntity user, Set<Role> roles) {
        return new UserAccount(user.getId(), user.getAccount(), user.getPasswordHash(), user.getDisplayName(),
                user.getEmail(), Boolean.TRUE.equals(user.getEnabled()), Set.copyOf(roles),
                Boolean.TRUE.equals(user.getPasswordResetRequired()));
    }

    private AdminUserResponse toAdminResponse(UserEntity user, Set<Role> roles) {
        return new AdminUserResponse(user.getId(), user.getAccount(), user.getDisplayName(), user.getEmail(),
                Boolean.TRUE.equals(user.getEnabled()), Set.copyOf(roles),
                Boolean.TRUE.equals(user.getPasswordResetRequired()), user.getCreatedAt(), user.getUpdatedAt(),
                user.getArchivedAt(), user.getDeletedAt(), user.getDeletedBy());
    }

    @Transactional
    public void revokeUserRefreshTokens(Long userId) {
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getUserId, userId)
                .isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, Instant.now()));
    }

    private String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash refresh token", ex);
        }
    }

    private String normalizeBlank(String value) {
        return hasText(value) ? value : null;
    }

    private String normalizeEmailCandidate(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim()
                .replace('\uFEFF', ' ')
                .replace('＠', '@')
                .replace('．', '.')
                .replace('。', '.');
        normalized = normalized.replaceAll("\\s+", "");
        return normalized.isBlank() ? null : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<AdminUserBatchCandidateRequest> batchCandidates(AdminUserBatchPreviewRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Batch preview request is required");
        }
        Set<Role> roles = defaultRoles(request.defaultRoles());
        boolean enabled = request.enabled() == null || request.enabled();
        boolean reset = request.passwordResetRequired() == null || request.passwordResetRequired();
        if (request.candidates() != null && !request.candidates().isEmpty()) {
            return request.candidates().stream()
                    .limit(MAX_BATCH_USERS)
                    .map(item -> normalizeCandidate(item, roles, enabled, reset))
                    .toList();
        }
        String mode = normalizeBlank(request.mode()) == null ? "TEXT" : request.mode().trim().toUpperCase();
        if ("SEQUENCE".equals(mode)) {
            return sequenceCandidates(request, roles, enabled, reset);
        }
        return textCandidates(request.importText(), roles, enabled, reset);
    }

    private List<AdminUserBatchCandidateRequest> sequenceCandidates(AdminUserBatchPreviewRequest request, Set<Role> roles,
                                                                    boolean enabled, boolean reset) {
        String prefix = normalizeBlank(request.prefix());
        if (prefix == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "账号前缀不能为空。");
        }
        if (!prefix.matches("^[A-Za-z0-9._@-]+$")) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "账号前缀只能包含英文字母、数字、点、下划线、短横线或 @。");
        }
        int start = request.start() == null ? 1 : Math.max(0, request.start());
        int count = request.count() == null ? 0 : request.count();
        int width = request.width() == null ? 3 : Math.min(Math.max(request.width(), 1), 12);
        if (count < 1 || count > MAX_BATCH_USERS) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "生成数量必须在 1-200 之间。");
        }
        List<AdminUserBatchCandidateRequest> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String account = prefix + String.format("%0" + width + "d", start + i);
            result.add(new AdminUserBatchCandidateRequest(account, account, null, roles, enabled, reset));
        }
        return result;
    }

    private List<AdminUserBatchCandidateRequest> textCandidates(String importText, Set<Role> roles, boolean enabled, boolean reset) {
        if (!hasText(importText)) {
            return List.of();
        }
        List<AdminUserBatchCandidateRequest> result = new ArrayList<>();
        String[] lines = importText.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            if (result.size() >= MAX_BATCH_USERS) {
                break;
            }
            String normalized = line.trim();
            if (normalized.isBlank()) {
                continue;
            }
            String[] parts = normalized.split("[,，\\t;；|]+");
            String account = parts.length > 0 ? parts[0].trim() : "";
            String displayName = parts.length > 1 && hasText(parts[1]) ? parts[1].trim() : account;
            String email = parts.length > 2 ? normalizeEmailCandidate(parts[2]) : null;
            if (email == null) {
                for (String part : parts) {
                    String value = normalizeEmailCandidate(part);
                    if (value != null && value.contains("@")) {
                        email = value;
                        break;
                    }
                }
            }
            result.add(new AdminUserBatchCandidateRequest(account, displayName, email, roles, enabled, reset));
        }
        return result;
    }

    private AdminUserBatchCandidateRequest normalizeCandidate(AdminUserBatchCandidateRequest request, Set<Role> roles,
                                                              boolean enabled, boolean reset) {
        if (request == null) {
            return new AdminUserBatchCandidateRequest(null, null, null, roles, enabled, reset);
        }
        return new AdminUserBatchCandidateRequest(
                normalizeBlank(request.account()),
                normalizeBlank(request.displayName()),
                normalizeEmailCandidate(request.email()),
                request.roles() == null || request.roles().isEmpty() ? roles : request.roles(),
                request.enabled() == null ? enabled : request.enabled(),
                request.passwordResetRequired() == null ? reset : request.passwordResetRequired()
        );
    }

    private List<AdminUserBatchPreviewItem> validateBatchCandidates(List<AdminUserBatchCandidateRequest> candidates) {
        List<AdminUserBatchPreviewItem> items = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            AdminUserBatchCandidateRequest candidate = candidates.get(i);
            String account = normalizeBlank(candidate.account());
            List<String> errors = new ArrayList<>();
            List<String> errorCodes = new ArrayList<>();
            if (account == null) {
                errors.add("账号不能为空");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_ACCOUNT_EMPTY);
            } else if (!validAccount(account)) {
                errors.add(ACCOUNT_RULE_MESSAGE);
                errorCodes.add(AdminUserBatchPreviewItem.CODE_ACCOUNT_INVALID);
            }
            String displayName = normalizeBlank(candidate.displayName());
            if (displayName == null) {
                errors.add("显示名称不能为空");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_DISPLAY_NAME_EMPTY);
            } else if (displayName.length() > 80) {
                errors.add("显示名称不能超过 80 个字符");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_DISPLAY_NAME_TOO_LONG);
            }
            String email = normalizeEmailCandidate(candidate.email());
            if (email != null && (email.length() > 160 || !EMAIL_PATTERN.matcher(email).matches())) {
                errors.add("邮箱格式不正确。请填写类似 name@example.com 的邮箱，或清空该字段。");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_EMAIL_INVALID);
            }
            try {
                requireRoles(candidate.roles());
            } catch (DomainException ex) {
                errors.add(ex.getMessage());
                errorCodes.add(AdminUserBatchPreviewItem.CODE_ROLES_INVALID);
            }
            boolean duplicateInBatch = account != null && seen.putIfAbsent(account, i + 1) != null;
            boolean duplicateExisting = account != null && findActiveUserByAccount(account) != null;
            if (duplicateInBatch) {
                errors.add("账号在本次导入中重复");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_ACCOUNT_DUPLICATE_IN_BATCH);
            }
            if (duplicateExisting) {
                errors.add("账号已存在");
                errorCodes.add(AdminUserBatchPreviewItem.CODE_ACCOUNT_EXISTS);
            }
            Set<Role> roles = candidate.roles() == null ? Set.of(Role.STUDENT) : new HashSet<>(candidate.roles());
            items.add(new AdminUserBatchPreviewItem(i + 1, account, displayName, email, roles,
                    candidate.enabled() == null || candidate.enabled(),
                    candidate.passwordResetRequired() == null || candidate.passwordResetRequired(),
                    errors.isEmpty(), duplicateInBatch, duplicateExisting, errors, errorCodes));
        }
        return items;
    }

    private Set<Role> defaultRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of(Role.STUDENT);
        }
        return requireRoles(roles);
    }

    private String batchPassword(AdminUserBatchPasswordRequest request, String account) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "请配置初始密码策略。");
        }
        String mode = normalizeBlank(request.mode()) == null ? "FIXED" : request.mode().trim().toUpperCase();
        String password;
        if ("ACCOUNT_SUFFIX".equals(mode)) {
            password = account + (normalizeBlank(request.accountSuffix()) == null ? "" : request.accountSuffix().trim());
        } else {
            password = normalizeBlank(request.fixedPassword());
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "初始密码必须为 8-128 位；如果使用“账号 + 后缀”，请加长密码后缀。");
        }
        return password;
    }

    private boolean validAccount(String account) {
        return account != null && ACCOUNT_PATTERN.matcher(account).matches();
    }

    private Map<String, Long> dailyCount(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object dayValue = row.get("day");
            Object totalValue = row.get("total");
            if (dayValue == null || totalValue == null) {
                continue;
            }
            String day = String.valueOf(dayValue);
            if (day.length() > 10) {
                day = day.substring(0, 10);
            }
            result.put(day, ((Number) totalValue).longValue());
        }
        return result;
    }
}
