package com.vchat.service;

import com.vchat.dto.Dtos.Event;
import com.vchat.repo.MemberRepo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;

/** Fans an event out to every member of a conversation, after the surrounding transaction commits. */
@Component
public class EventPublisher {
    private final RealtimeGateway gateway;
    private final MemberRepo members;

    public EventPublisher(RealtimeGateway gateway, MemberRepo members) {
        this.gateway = gateway; this.members = members;
    }

    public void toConversation(UUID conversationId, String type, Object payload) {
        List<UUID> recipients = members.findUserIds(conversationId);
        Event event = new Event(type, conversationId, payload);
        Runnable send = () -> gateway.deliver(conversationId, recipients, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }
}
