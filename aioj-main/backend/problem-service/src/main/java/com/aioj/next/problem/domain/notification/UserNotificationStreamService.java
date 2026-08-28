package com.aioj.next.problem.domain.notification;

import com.aioj.next.contract.notification.UserNotificationStreamEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains only live wake-up connections; notifications themselves live in MySQL. */
@Service
public class UserNotificationStreamService {
    private static final long EMITTER_TIMEOUT_MILLIS = 55_000L;
    private final ConcurrentHashMap<Long, Set<SseEmitter>> emittersByRecipient = new ConcurrentHashMap<>();

    public SseEmitter connect(Long recipientUserId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Set<SseEmitter> emitters = emittersByRecipient.computeIfAbsent(recipientUserId, ignored -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(recipientUserId, emitter));
        emitter.onTimeout(() -> {
            remove(recipientUserId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(recipientUserId, emitter));
        send(recipientUserId, emitter, "ready", null);
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationCreated(UserNotificationCreatedEvent event) {
        Set<SseEmitter> emitters = emittersByRecipient.get(event.recipientUserId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(event.recipientUserId(), emitter, "notification", event.notification());
        }
    }

    @Scheduled(fixedDelayString = "${aioj.notifications.sse-heartbeat-millis:20000}")
    public void heartbeat() {
        emittersByRecipient.forEach((recipientUserId, emitters) ->
                emitters.forEach(emitter -> send(recipientUserId, emitter, "heartbeat", null)));
    }

    private void send(Long recipientUserId, SseEmitter emitter, String eventName,
                      UserNotificationStreamEvent payload) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName);
            if (payload == null) {
                event.data("{}");
            } else {
                event.data(payload);
            }
            emitter.send(event);
        } catch (IOException | IllegalStateException ignored) {
            remove(recipientUserId, emitter);
            // A failed write means the servlet response is already unusable.
            // Calling complete() would flush it again and trigger an async error dispatch.
        }
    }

    private void remove(Long recipientUserId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByRecipient.get(recipientUserId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByRecipient.remove(recipientUserId, emitters);
        }
    }
}
