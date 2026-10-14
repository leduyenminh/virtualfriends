package com.vchat.service;

import com.vchat.dto.Dtos.Event;
import java.util.List;
import java.util.UUID;

/** Delivers an event to the given users' WebSocket sessions, wherever (on whichever instance) they are connected. */
public interface RealtimeGateway {
    void deliver(UUID conversationId, List<UUID> recipients, Event event);
}
