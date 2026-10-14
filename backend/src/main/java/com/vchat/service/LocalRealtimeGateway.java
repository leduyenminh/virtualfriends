package com.vchat.service;

import com.vchat.dto.Dtos.Event;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Single-instance mode: push straight to the in-memory STOMP broker. */
@Component
@ConditionalOnProperty(name = "vchat.events.mode", havingValue = "local", matchIfMissing = true)
public class LocalRealtimeGateway implements RealtimeGateway {
    private final SimpMessagingTemplate broker;

    public LocalRealtimeGateway(SimpMessagingTemplate broker) { this.broker = broker; }

    @Override
    public void deliver(UUID conversationId, List<UUID> recipients, Event event) {
        recipients.forEach(uid -> broker.convertAndSendToUser(uid.toString(), "/queue/events", event));
    }
}
