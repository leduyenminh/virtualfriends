package com.vchat.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSocketConfigTest {
    private static final AuthUser USER = new AuthUser(UUID.randomUUID(), "alice", Set.of("USER"), "jti",
            Instant.now(), Instant.now().plusSeconds(900));

    private static StompHeaderAccessor frame(StompCommand command, String destination, AuthUser user) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(command);
        acc.setDestination(destination);
        acc.setUser(user);
        return acc;
    }

    @Test
    void allowsOwnQueueAndAppDestinations() {
        assertDoesNotThrow(() -> WebSocketConfig.requireDestination(frame(StompCommand.SUBSCRIBE, "/user/queue/events", USER), "/user/queue/"));
        assertDoesNotThrow(() -> WebSocketConfig.requireDestination(frame(StompCommand.SEND, "/app/typing", USER), "/app/"));
    }

    @Test
    void rejectsSendingIntoAnotherUsersQueue() {
        String victim = "/user/" + UUID.randomUUID() + "/queue/events";
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SEND, victim, USER), "/app/"));
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SEND, "/queue/events-user123", USER), "/app/"));
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SEND, "/topic/x", USER), "/app/"));
    }

    @Test
    void rejectsSubscribingOutsideOwnQueue() {
        String other = "/user/" + UUID.randomUUID() + "/queue/events";
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SUBSCRIBE, other, USER), "/user/queue/"));
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SUBSCRIBE, "/queue/events-user123", USER), "/user/queue/"));
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SUBSCRIBE, "/user/queue/../x", USER), "/user/queue/"));
    }

    @Test
    void rejectsFramesBeforeSignIn() {
        assertThrows(AccessDeniedException.class, () -> WebSocketConfig.requireDestination(frame(StompCommand.SEND, "/app/typing", null), "/app/"));
    }
}
