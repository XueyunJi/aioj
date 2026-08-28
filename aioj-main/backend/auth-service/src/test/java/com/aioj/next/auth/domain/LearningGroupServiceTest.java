package com.aioj.next.auth.domain;

import com.aioj.next.auth.entity.LearningGroupEntity;
import com.aioj.next.auth.entity.LearningGroupMemberEntity;
import com.aioj.next.auth.entity.UserEntity;
import com.aioj.next.auth.mapper.LearningGroupMapper;
import com.aioj.next.auth.mapper.LearningGroupMemberMapper;
import com.aioj.next.auth.mapper.UserMapper;
import com.aioj.next.auth.mapper.UserRoleMapper;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.contract.learning.LearningGroupCreateRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberBatchAddStatus;
import com.aioj.next.contract.learning.LearningGroupMemberAddRequest;
import com.aioj.next.contract.learning.LearningGroupMemberRole;
import com.aioj.next.contract.learning.LearningGroupStatus;
import com.aioj.next.contract.learning.LearningGroupType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningGroupServiceTest {
    @Mock
    private LearningGroupMapper groupMapper;
    @Mock
    private LearningGroupMemberMapper memberMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private OperationAuditWriter auditWriter;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private LearningGroupService service;

    @BeforeEach
    void setUp() {
        service = new LearningGroupService(groupMapper, memberMapper, userMapper, userRoleMapper, auditWriter, jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createClassInsertsOwnerMember() {
        authenticate(7L, Role.TEACHER);
        doAnswer(invocation -> {
            LearningGroupEntity group = invocation.getArgument(0);
            group.setId(101L);
            return 1;
        }).when(groupMapper).insert(any(LearningGroupEntity.class));
        when(userMapper.selectById(7L)).thenReturn(user(7L, "teacher", "Demo Teacher", true));
        when(memberMapper.selectCount(any())).thenReturn(1L, 0L);

        var response = service.createClass(new LearningGroupCreateRequest(" Algorithm Class ", " Training "));

        assertEquals(101L, response.id());
        assertEquals("Algorithm Class", response.name());
        ArgumentCaptor<LearningGroupMemberEntity> memberCaptor = ArgumentCaptor.forClass(LearningGroupMemberEntity.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertEquals(101L, memberCaptor.getValue().getGroupId());
        assertEquals(7L, memberCaptor.getValue().getUserId());
        assertEquals(LearningGroupMemberRole.OWNER, memberCaptor.getValue().getRole());
    }

    @Test
    void teacherCannotManageClassWithoutMembership() {
        authenticate(8L, Role.TEACHER);
        when(groupMapper.selectById(200L)).thenReturn(group(200L, null, LearningGroupType.CLASS, 7L));
        when(memberMapper.selectOne(any())).thenReturn(null);

        DomainException error = assertThrows(DomainException.class,
                () -> service.addClassMember(200L, new LearningGroupMemberAddRequest(null, "student", LearningGroupMemberRole.STUDENT)));

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test
    void numericAccountCanBeAddedWhenItLooksLikeAUserId() {
        authenticate(7L, Role.TEACHER);
        when(groupMapper.selectById(200L)).thenReturn(group(200L, null, LearningGroupType.CLASS, 7L));
        when(userMapper.selectById(1002L)).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(user(9L, "1002", "Numeric Account", true));

        var response = service.addClassMember(200L,
                new LearningGroupMemberAddRequest(1002L, null, LearningGroupMemberRole.STUDENT));

        assertEquals(9L, response.userId());
        assertEquals("1002", response.account());
    }

    @Test
    void batchAddMembersReturnsPerUserResults() {
        authenticate(7L, Role.TEACHER);
        when(groupMapper.selectById(200L)).thenReturn(group(200L, null, LearningGroupType.CLASS, 7L));
        when(userMapper.selectById(1001L)).thenReturn(user(1001L, "student1", "Student One", true));
        when(userMapper.selectById(1002L)).thenReturn(user(1002L, "disabled", "Disabled User", false));
        when(memberMapper.selectOne(any())).thenReturn(null);

        var response = service.addClassMembers(200L,
                new LearningGroupMemberBatchAddRequest(List.of(1001L, 1002L), LearningGroupMemberRole.STUDENT));

        assertEquals(2, response.requested());
        assertEquals(1, response.succeeded());
        assertEquals(1, response.failed());
        assertEquals(LearningGroupMemberBatchAddStatus.ADDED, response.results().get(0).status());
        assertEquals(LearningGroupMemberBatchAddStatus.FAILED, response.results().get(1).status());
        ArgumentCaptor<LearningGroupMemberEntity> memberCaptor = ArgumentCaptor.forClass(LearningGroupMemberEntity.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertEquals(1001L, memberCaptor.getValue().getUserId());
    }

    private void authenticate(Long userId, Role... roles) {
        Set<Role> roleSet = Set.of(roles);
        var authorities = roleSet.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new SecurityPrincipal(userId, "tester", roleSet), null, authorities));
    }

    private LearningGroupEntity group(Long id, Long parentId, LearningGroupType type, Long ownerId) {
        Instant now = Instant.now();
        LearningGroupEntity group = new LearningGroupEntity();
        group.setId(id);
        group.setParentGroupId(parentId);
        group.setType(type);
        group.setName("Class");
        group.setOwnerUserId(ownerId);
        group.setStatus(LearningGroupStatus.ACTIVE);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return group;
    }

    private UserEntity user(Long id, String account, String displayName, boolean enabled) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setAccount(account);
        user.setDisplayName(displayName);
        user.setEnabled(enabled);
        return user;
    }
}
