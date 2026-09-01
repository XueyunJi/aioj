package com.aioj.next.auth.controller;

import com.aioj.next.contract.auth.HandoffExchangeRequest;
import com.aioj.next.contract.auth.HandoffTicketIssueRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthHandoffControllerContractTest {
    @Test
    void public_paths_and_payload_shape_are_frozen() throws Exception {
        assertArrayEquals(new String[]{"/auth"}, AuthHandoffController.class
                .getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[]{"/handoff-tickets"}, AuthHandoffController.class
                .getMethod("issue", HandoffTicketIssueRequest.class)
                .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/handoff/exchange"}, AuthHandoffController.class
                .getMethod("exchange", HandoffExchangeRequest.class)
                .getAnnotation(PostMapping.class).value());

        assertNotNull(HandoffTicketIssueRequest.class.getRecordComponents());
        assertNotNull(HandoffExchangeRequest.class.getRecordComponents());
        assertFalse(hasComponent(HandoffTicketIssueRequest.class, "userId"));
        assertFalse(hasComponent(HandoffExchangeRequest.class, "userId"));
    }

    private boolean hasComponent(Class<?> type, String name) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(name::equals);
    }
}
