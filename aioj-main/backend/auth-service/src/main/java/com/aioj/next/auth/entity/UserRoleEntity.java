package com.aioj.next.auth.entity;

import com.aioj.next.common.security.Role;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("user_roles")
public class UserRoleEntity {
    @TableId
    private Long userId;
    private Role role;

    public UserRoleEntity() {
    }

    public UserRoleEntity(Long userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}
