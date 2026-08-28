package com.aioj.next.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LearningGroupControllerAuthorizationTest {

    @Test
    void class_write_endpoints_allow_teacher_and_admin() throws Exception {
        assertPreAuthorize("createClass", "hasAnyRole('TEACHER','ADMIN')",
                com.aioj.next.contract.learning.LearningGroupCreateRequest.class);
        assertPreAuthorize("addClassMember", "hasAnyRole('TEACHER','ADMIN')",
                Long.class, com.aioj.next.contract.learning.LearningGroupMemberAddRequest.class);
        assertPreAuthorize("addClassMembers", "hasAnyRole('TEACHER','ADMIN')",
                Long.class, com.aioj.next.contract.learning.LearningGroupMemberBatchAddRequest.class);
    }

    private void assertPreAuthorize(String methodName, String expected, Class<?>... parameterTypes) throws Exception {
        Method method = LearningGroupController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertEquals(expected, annotation.value());
    }
}
