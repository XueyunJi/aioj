package com.aioj.next.auth.domain;

import com.aioj.next.auth.entity.LearningGroupEntity;
import com.aioj.next.auth.entity.LearningGroupMemberEntity;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.entity.UserRoleEntity;
import com.aioj.next.auth.mapper.LearningGroupMapper;
import com.aioj.next.auth.mapper.LearningGroupMemberMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.auth.mapper.UserRoleMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.learning.LearningGroupCreateRequest;
import com.aioj.next.contract.learning.LearningGroupMemberAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddResponse;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddResultItem;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddStatus;
import com.aioj.next.contract.learning.LearningGroupMemberResponse;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.learning.LearningGroupResponse;
import com.aioj.next.contract.learning.LearningGroupStatus;
import com.aioj.next.contract.learning.LearningGroupType;
import com.aioj.next.contract.learning.LearningGroupUpdateRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LearningGroupService {
    private static final Set<LearningGroupMemberRole> MANAGER_ROLES =
            EnumSet.of(LearningGroupMemberRole.OWNER, LearningGroupMemberRole.TEACHER);
    private static final Set<LearningGroupMemberRole> PUBLIC_ADD_ROLES =
            EnumSet.of(LearningGroupMemberRole.TEACHER, LearningGroupMemberRole.ASSISTANT,
                    LearningGroupMemberRole.STUDENT);

    private final LearningGroupMapper groupMapper;
    private final LearningGroupMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final OperationAuditWriter auditWriter;
    private final JdbcTemplate jdbcTemplate;

    public LearningGroupService(LearningGroupMapper groupMapper, LearningGroupMemberMapper memberMapper,
                                UserMapper userMapper, UserRoleMapper userRoleMapper,
                                OperationAuditWriter auditWriter, JdbcTemplate jdbcTemplate) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.auditWriter = auditWriter;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LearningGroupResponse> listClasses(String keyword, LearningGroupStatus status) {
        Long currentUserId = SecuritySupport.currentUserId();
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        Set<Long> visibleGroupIds = admin ? Set.of() : visibleGroupIds(currentUserId);
        if (!admin && visibleGroupIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<LearningGroupEntity> wrapper = new LambdaQueryWrapper<LearningGroupEntity>()
                .eq(LearningGroupEntity::getType, LearningGroupType.CLASS)
                .isNull(LearningGroupEntity::getDeletedAt)
                .in(!admin, LearningGroupEntity::getId, visibleGroupIds)
                .eq(status != null, LearningGroupEntity::getStatus, status)
                .like(hasText(keyword), LearningGroupEntity::getName, keyword == null ? null : keyword.trim())
                .orderByDesc(LearningGroupEntity::getCreatedAt);
        return toGroupResponses(groupMapper.selectList(wrapper));
    }

    @Transactional
    public LearningGroupResponse createClass(LearningGroupCreateRequest request) {
        requireTeacherOrAdmin();
        Long currentUserId = SecuritySupport.currentUserId();
        LearningGroupEntity entity = newGroup(null, LearningGroupType.CLASS, request.name(), request.description(),
                currentUserId);
        groupMapper.insert(entity);
        insertOwnerMember(entity.getId(), currentUserId);
        return toGroupResponse(entity);
    }

    @Transactional
    public LearningGroupResponse archiveClass(Long classId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        if (entity.getStatus() != LearningGroupStatus.ARCHIVED) {
            Instant now = Instant.now();
            entity.setStatus(LearningGroupStatus.ARCHIVED);
            entity.setArchivedAt(now);
            entity.setUpdatedAt(now);
            groupMapper.updateById(entity);
        }
        auditWriter.record("ARCHIVE", "LEARNING_GROUP", entity.getId(), "SUCCESS",
                Map.of("name", entity.getName()));
        return toGroupResponse(entity);
    }

    @Transactional
    public LearningGroupResponse restoreClass(Long classId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        if (entity.getStatus() != LearningGroupStatus.ARCHIVED && entity.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived user groups can be restored");
        }
        entity.setStatus(LearningGroupStatus.ACTIVE);
        entity.setArchivedAt(null);
        entity.setUpdatedAt(Instant.now());
        groupMapper.updateById(entity);
        auditWriter.record("RESTORE", "LEARNING_GROUP", entity.getId(), "SUCCESS",
                Map.of("name", entity.getName()));
        return toGroupResponse(entity);
    }

    @Transactional
    public void softDeleteClass(Long classId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        if (entity.getStatus() != LearningGroupStatus.ARCHIVED && entity.getArchivedAt() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Only archived user groups can be deleted");
        }
        assertNotReferencedByActiveRun(entity.getId());
        entity.setDeletedAt(Instant.now());
        entity.setDeletedBy(SecuritySupport.currentUserId());
        entity.setUpdatedAt(Instant.now());
        groupMapper.updateById(entity);
        auditWriter.record("SOFT_DELETE", "LEARNING_GROUP", entity.getId(), "SUCCESS",
                Map.of("name", entity.getName()));
    }

    public LearningGroupResponse getClass(Long classId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanView(entity);
        return toGroupResponse(entity);
    }

    @Transactional
    public LearningGroupResponse updateClass(Long classId, LearningGroupUpdateRequest request) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        updateGroupFields(entity, request);
        groupMapper.updateById(entity);
        return toGroupResponse(entity);
    }

    public List<LearningGroupMemberResponse> listClassMembers(Long classId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanView(entity);
        return listMembers(entity.getId());
    }

    @Transactional
    public LearningGroupMemberResponse addClassMember(Long classId, LearningGroupMemberAddRequest request) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        return upsertMember(entity, request);
    }

    @Transactional
    public LearningGroupMemberBatchAddResponse addClassMembers(Long classId, LearningGroupMemberBatchAddRequest request) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        List<Long> userIds = request == null ? List.of() : request.userIds();
        if (userIds == null || userIds.isEmpty()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "At least one user is required");
        }
        if (userIds.size() > 100) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Cannot add more than 100 users at once");
        }
        LearningGroupMemberRole role = normalizeMemberRole(request == null ? null : request.role());
        List<LearningGroupMemberBatchAddResultItem> results = new ArrayList<>();
        for (Long userId : userIds) {
            results.add(addClassMemberResult(entity, userId, role));
        }
        int failed = (int) results.stream()
                .filter(result -> result.status() == LearningGroupMemberBatchAddStatus.FAILED)
                .count();
        return new LearningGroupMemberBatchAddResponse(results.size(), results.size() - failed, failed, results);
    }

    @Transactional
    public void removeClassMember(Long classId, Long userId) {
        LearningGroupEntity entity = requireClass(classId);
        assertCanManage(entity);
        if (Objects.equals(entity.getOwnerUserId(), userId)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Class owner cannot be removed");
        }
        deleteMember(entity.getId(), userId);
    }

    private LearningGroupEntity newGroup(Long parentGroupId, LearningGroupType type, String name, String description,
                                         Long ownerUserId) {
        Instant now = Instant.now();
        LearningGroupEntity entity = new LearningGroupEntity();
        entity.setParentGroupId(parentGroupId);
        entity.setType(type);
        entity.setName(requireName(name));
        entity.setDescription(normalizeBlank(description));
        entity.setOwnerUserId(ownerUserId);
        entity.setStatus(LearningGroupStatus.ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void updateGroupFields(LearningGroupEntity entity, LearningGroupUpdateRequest request) {
        if (hasText(request.name())) {
            entity.setName(requireName(request.name()));
        }
        if (request.description() != null) {
            entity.setDescription(normalizeBlank(request.description()));
        }
        if (request.status() != null && request.status() != entity.getStatus()) {
            entity.setStatus(request.status());
            entity.setArchivedAt(request.status() == LearningGroupStatus.ARCHIVED ? Instant.now() : null);
        }
        entity.setUpdatedAt(Instant.now());
    }

    private LearningGroupMemberResponse upsertMember(LearningGroupEntity group, LearningGroupMemberAddRequest request) {
        LearningGroupMemberRole role = normalizeMemberRole(request.role());
        UserEntity user = resolveUser(request);
        return upsertMemberForUser(group, user, role).response();
    }

    private LearningGroupMemberBatchAddResultItem addClassMemberResult(LearningGroupEntity group, Long userId,
                                                                       LearningGroupMemberRole role) {
        if (userId == null) {
            return new LearningGroupMemberBatchAddResultItem(null, null, null, role,
                    LearningGroupMemberBatchAddStatus.FAILED, "User id is required", null);
        }
        UserEntity user = null;
        try {
            user = requireUser(userId);
            MemberUpsertResult upsert = upsertMemberForUser(group, user, role);
            return new LearningGroupMemberBatchAddResultItem(user.getId(), user.getAccount(), user.getDisplayName(),
                    role, upsert.status(), upsert.message(), upsert.response());
        } catch (DomainException error) {
            return new LearningGroupMemberBatchAddResultItem(userId,
                    user == null ? null : user.getAccount(),
                    user == null ? null : user.getDisplayName(),
                    role,
                    LearningGroupMemberBatchAddStatus.FAILED,
                    error.getMessage(),
                    null);
        }
    }

    private MemberUpsertResult upsertMemberForUser(LearningGroupEntity group, UserEntity user,
                                                   LearningGroupMemberRole role) {
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "User account is disabled");
        }
        validateMemberRole(user.getId(), role);

        Instant now = Instant.now();
        LearningGroupMemberEntity member = memberFor(group.getId(), user.getId());
        LearningGroupMemberBatchAddStatus status;
        String message;
        if (member == null) {
            member = new LearningGroupMemberEntity();
            member.setGroupId(group.getId());
            member.setUserId(user.getId());
            member.setRole(role);
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
            memberMapper.insert(member);
            status = LearningGroupMemberBatchAddStatus.ADDED;
            message = "Member added";
        } else {
            if (member.getRole() == LearningGroupMemberRole.OWNER) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Owner role cannot be changed through member edit");
            }
            if (member.getRole() == role) {
                status = LearningGroupMemberBatchAddStatus.UNCHANGED;
                message = "Member already exists";
            } else {
                member.setRole(role);
                member.setUpdatedAt(now);
                memberMapper.updateById(member);
                status = LearningGroupMemberBatchAddStatus.UPDATED;
                message = "Member role updated";
            }
        }
        return new MemberUpsertResult(toMemberResponse(member, user), status, message);
    }

    private void validateMemberRole(Long userId, LearningGroupMemberRole role) {
        if (!PUBLIC_ADD_ROLES.contains(role)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Invalid group member role");
        }
        if (role == LearningGroupMemberRole.TEACHER && !hasSystemRole(userId, Role.TEACHER)
                && !hasSystemRole(userId, Role.ADMIN)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Teacher group role requires a teacher account");
        }
    }

    private UserEntity resolveUser(LearningGroupMemberAddRequest request) {
        if (request.userId() != null) {
            UserEntity user = userMapper.selectById(request.userId());
            if (user != null) {
                return user;
            }
            UserEntity accountMatchedUser = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getAccount, String.valueOf(request.userId())));
            if (accountMatchedUser != null) {
                return accountMatchedUser;
            }
            throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
        }
        if (hasText(request.account())) {
            UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getAccount, request.account().trim()));
            if (user != null) {
                return user;
            }
        }
        throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
    }

    private LearningGroupMemberRole normalizeMemberRole(LearningGroupMemberRole role) {
        return role == null ? LearningGroupMemberRole.STUDENT : role;
    }

    private void insertOwnerMember(Long groupId, Long userId) {
        Instant now = Instant.now();
        LearningGroupMemberEntity member = new LearningGroupMemberEntity();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(LearningGroupMemberRole.OWNER);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        memberMapper.insert(member);
    }

    private List<LearningGroupMemberResponse> listMembers(Long groupId) {
        List<LearningGroupMemberEntity> members = memberMapper.selectList(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, groupId)
                .orderByAsc(LearningGroupMemberEntity::getRole)
                .orderByAsc(LearningGroupMemberEntity::getCreatedAt));
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, UserEntity> users = userMapper.selectBatchIds(members.stream()
                        .map(LearningGroupMemberEntity::getUserId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, user -> user));
        return members.stream()
                .map(member -> toMemberResponse(member, users.get(member.getUserId())))
                .filter(Objects::nonNull)
                .toList();
    }

    private void deleteMember(Long groupId, Long userId) {
        memberMapper.delete(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, groupId)
                .eq(LearningGroupMemberEntity::getUserId, userId));
    }

    private LearningGroupEntity requireClass(Long classId) {
        LearningGroupEntity entity = requireGroup(classId);
        if (entity.getType() != LearningGroupType.CLASS) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Class not found");
        }
        return entity;
    }

    private LearningGroupEntity requireGroup(Long groupId) {
        LearningGroupEntity entity = groupMapper.selectById(groupId);
        if (entity == null || entity.getDeletedAt() != null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Learning group not found");
        }
        return entity;
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "User not found");
        }
        return user;
    }

    private void assertCanView(LearningGroupEntity group) {
        Long currentUserId = SecuritySupport.currentUserId();
        if (SecuritySupport.hasRole(Role.ADMIN) || Objects.equals(group.getOwnerUserId(), currentUserId)
                || memberFor(group.getId(), currentUserId) != null) {
            return;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot access this class");
    }

    private void assertCanManage(LearningGroupEntity group) {
        Long currentUserId = SecuritySupport.currentUserId();
        if (SecuritySupport.hasRole(Role.ADMIN) || Objects.equals(group.getOwnerUserId(), currentUserId)) {
            return;
        }
        LearningGroupMemberEntity member = memberFor(group.getId(), currentUserId);
        if (member != null && MANAGER_ROLES.contains(member.getRole())) {
            return;
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "Cannot manage this class");
    }

    private LearningGroupMemberEntity memberFor(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return null;
        }
        return memberMapper.selectOne(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, groupId)
                .eq(LearningGroupMemberEntity::getUserId, userId));
    }

    private Set<Long> visibleGroupIds(Long userId) {
        return memberMapper.selectList(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                        .eq(LearningGroupMemberEntity::getUserId, userId))
                .stream()
                .map(LearningGroupMemberEntity::getGroupId)
                .collect(Collectors.toSet());
    }

    private boolean hasSystemRole(Long userId, Role role) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>()
                        .eq(UserRoleEntity::getUserId, userId)
                        .eq(UserRoleEntity::getRole, role))
                .stream()
                .findAny()
                .isPresent();
    }

    private void requireTeacherOrAdmin() {
        if (!SecuritySupport.hasAnyRole(Role.TEACHER, Role.ADMIN)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Teacher or admin role required");
        }
    }

    private List<LearningGroupResponse> toGroupResponses(List<LearningGroupEntity> groups) {
        List<LearningGroupResponse> responses = new ArrayList<>();
        for (LearningGroupEntity group : groups) {
            responses.add(toGroupResponse(group));
        }
        responses.sort(Comparator.comparing(LearningGroupResponse::createdAt).reversed());
        return responses;
    }

    private LearningGroupResponse toGroupResponse(LearningGroupEntity group) {
        UserEntity owner = userMapper.selectById(group.getOwnerUserId());
        long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, group.getId()));
        long studentCount = memberMapper.selectCount(new LambdaQueryWrapper<LearningGroupMemberEntity>()
                .eq(LearningGroupMemberEntity::getGroupId, group.getId())
                .eq(LearningGroupMemberEntity::getRole, LearningGroupMemberRole.STUDENT));
        return new LearningGroupResponse(
                group.getId(),
                group.getParentGroupId(),
                group.getType(),
                group.getName(),
                group.getDescription(),
                group.getOwnerUserId(),
                owner == null ? null : owner.getDisplayName(),
                group.getStatus(),
                memberCount,
                studentCount,
                group.getCreatedAt(),
                group.getUpdatedAt(),
                group.getArchivedAt(),
                group.getDeletedAt(),
                group.getDeletedBy()
        );
    }

    private void assertNotReferencedByActiveRun(Long groupId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM contest_run_allowed_groups g
                JOIN contest_runs r ON r.id = g.contest_run_id
                WHERE g.group_id = ?
                  AND r.archived_at IS NULL
                  AND r.deleted_at IS NULL
                  AND (r.end_at IS NULL OR r.end_at > CURRENT_TIMESTAMP)
                """, Long.class, groupId);
        if (count != null && count > 0) {
            throw new DomainException(ErrorCode.CONFLICT, "User group is referenced by an active contest run");
        }
    }

    private LearningGroupMemberResponse toMemberResponse(LearningGroupMemberEntity member, UserEntity user) {
        if (member == null || user == null) {
            return null;
        }
        return new LearningGroupMemberResponse(
                user.getId(),
                user.getAccount(),
                user.getDisplayName(),
                user.getEmail(),
                user.getEnabled(),
                member.getRole(),
                member.getCreatedAt()
        );
    }

    private String requireName(String value) {
        if (!hasText(value)) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Group name is required");
        }
        return value.trim();
    }

    private String normalizeBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record MemberUpsertResult(
            LearningGroupMemberResponse response,
            LearningGroupMemberBatchAddStatus status,
            String message
    ) {
    }
}
