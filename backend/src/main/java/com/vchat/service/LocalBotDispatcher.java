package com.vchat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Single-instance mode: run the bot turn on a (virtual) background thread once the user's message is committed. */
@Component
@ConditionalOnProperty(name = "vchat.events.mode", havingValue = "local", matchIfMissing = true)
public class LocalBotDispatcher {
    private final BotService bots;

    public LocalBotDispatcher(BotService bots) { this.bots = bots; }

    @Async
    @TransactionalEventListener
    public void on(ChatService.BotTurnRequested e) {
        bots.reply(e.conversationId());
    }
}
