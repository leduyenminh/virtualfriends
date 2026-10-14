package com.vchat.service.kafka;

import com.vchat.config.VchatProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka mode (vchat.events.mode=kafka). Two topics, both keyed by conversationId so all events of one conversation
 * stay ordered on one partition while different conversations are processed in parallel:
 *   vchat.chat-events   every realtime event; each backend instance consumes all of it (own consumer group)
 *                       and delivers to the WebSocket sessions connected to that instance -> horizontal scaling.
 *   vchat.bot-requests  "user wrote to a bot" jobs; one shared consumer group of bot workers, durable, retried,
 *                       and parked in vchat.bot-requests.DLT after repeated failures.
 */
@Configuration
@ConditionalOnProperty(name = "vchat.events.mode", havingValue = "kafka")
public class KafkaEventsConfig {
    public static final String CHAT_EVENTS = "vchat.chat-events";
    public static final String BOT_REQUESTS = "vchat.bot-requests";
    private static final Logger log = LoggerFactory.getLogger(KafkaEventsConfig.class);

    @Bean
    NewTopic chatEventsTopic(VchatProperties p) {
        return TopicBuilder.name(CHAT_EVENTS).partitions(6).replicas(p.events().replicas())
                .config("retention.ms", String.valueOf(24 * 3600 * 1000L)).build();
    }

    @Bean
    NewTopic botRequestsTopic(VchatProperties p) {
        return TopicBuilder.name(BOT_REQUESTS).partitions(6).replicas(p.events().replicas()).build();
    }

    @Bean
    NewTopic botRequestsDlt(VchatProperties p) {
        return TopicBuilder.name(BOT_REQUESTS + ".DLT").partitions(1).replicas(p.events().replicas()).build();
    }

    /** Picked up by Spring Boot's listener container factory: 2 retries 1s apart, then dead-letter. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Kafka delivery attempt {} failed for {}: {}", attempt, record.topic(), ex.getMessage()));
        return handler;
    }
}
