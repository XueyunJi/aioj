package com.aioj.next.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminControllerAuthorizationTest {

    @Test
    void admin_user_and_role_management_remains_admin_only() {
        PreAuthorize annotation = AdminController.class.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", annotation.value());
    }
}
