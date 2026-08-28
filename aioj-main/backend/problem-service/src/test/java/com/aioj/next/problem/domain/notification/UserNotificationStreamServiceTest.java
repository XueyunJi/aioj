package com.aioj.next.problem.domain.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserNotificationStreamServiceTest {

    @Test
    void heartbeatRemovesFailedEmitterWithoutCompletingAnUnusableResponse() throws Exception {
        UserNotificationStreamService service = new UserNotificationStreamService();
        SseEmitter emitter = mock(SseEmitter.class);
        emitterMap(service).put(101L, ConcurrentHashMap.newKeySet());
        emitterMap(service).get(101L).add(emitter);
        doThrow(new IOException("client disconnected"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.heartbeat();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
        assertThat(emitterMap(service)).doesNotContainKey(101L);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, Set<SseEmitter>> emitterMap(UserNotificationStreamService service) throws Exception {
        Field field = UserNotificationStreamService.class.getDeclaredField("emittersByRecipient");
        field.setAccessible(true);
        return (ConcurrentHashMap<Long, Set<SseEmitter>>) field.get(service);
    }
}
