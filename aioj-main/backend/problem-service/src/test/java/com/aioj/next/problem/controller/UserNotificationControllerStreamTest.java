package com.aioj.next.problem.controller;

import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecurityPrincipal;
import com.aioj.next.problem.domain.notification.UserNotificationService;
import com.aioj.next.problem.domain.notification.UserNotificationStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserNotificationControllerStreamTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamDisablesProxyBufferingAndCaching() {
        authenticate(101L);
        UserNotificationStreamService streamService = mock(UserNotificationStreamService.class);
        SseEmitter emitter = new SseEmitter();
        when(streamService.connect(101L)).thenReturn(emitter);
        UserNotificationController controller = new UserNotificationController(
                mock(UserNotificationService.class), streamService);

        ResponseEntity<SseEmitter> response = controller.stream();

        assertThat(response.getBody()).isSameAs(emitter);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void rawIoExceptionFromClosedStreamIsHandledWithoutJsonFallback() throws Exception {
        authenticate(101L);
        UserNotificationStreamService disconnectedStream = new UserNotificationStreamService() {
            @Override
            public SseEmitter connect(Long recipientUserId) {
                return sneakyThrow(new IOException("client disconnected"));
            }
        };
        UserNotificationController controller = new UserNotificationController(
                mock(UserNotificationService.class), disconnectedStream);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    private void authenticate(Long userId) {
        SecurityPrincipal principal = new SecurityPrincipal(userId, "student-" + userId, Set.of(Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a"));
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable error) throws E {
        throw (E) error;
    }
}
