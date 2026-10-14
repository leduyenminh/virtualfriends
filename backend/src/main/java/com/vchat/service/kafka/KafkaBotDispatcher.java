package com.vchat.service.kafka;

import com.vchat.service.BotService;
import com.vchat.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.UUID;

/**
 * Kafka mode: bot replies become durable jobs. If an instance dies mid-reply, the uncommitted offset is redelivered
 * to another worker; slow Claude calls no longer compete with request threads; bot workers could even run as a
 * separate deployment that only consumes this topic.
 */
@Component
@ConditionalOnProperty(name = "vchat.events.mode", havingValue = "kafka")
public class KafkaBotDispatcher {
    private static final Logger log = LoggerFactory.getLogger(KafkaBotDispatcher.class);

    private final KafkaTemplate<String, String> kafka;
    private final BotService bots;

    public KafkaBotDispatcher(KafkaTemplate<String, String> kafka, BotService bots) {
        this.kafka = kafka; this.bots = bots;
    }

    @TransactionalEventListener
    public void on(ChatService.BotTurnRequested e) {
        String id = e.conversationId().toString();
        kafka.send(KafkaEventsConfig.BOT_REQUESTS, id, id)
                .whenComplete((r, ex) -> { if (ex != null) log.error("Could not enqueue bot turn for {}: {}", id, ex.getMessage()); });
    }

    @KafkaListener(topics = KafkaEventsConfig.BOT_REQUESTS, groupId = "vchat-bot-workers", concurrency = "3")
    void work(String conversationId) {
        bots.reply(UUID.fromString(conversationId));
    }
}
