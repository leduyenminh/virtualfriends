package com.vchat.service.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vchat.dto.Dtos.Event;
import com.vchat.service.RealtimeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "vchat.events.mode", havingValue = "kafka")
public class KafkaRealtimeGateway implements RealtimeGateway {
    private static final Logger log = LoggerFactory.getLogger(KafkaRealtimeGateway.class);

    private final KafkaTemplate<String, String> kafka;
    private final SimpMessagingTemplate broker;
    private final ObjectMapper json;

    public KafkaRealtimeGateway(KafkaTemplate<String, String> kafka, SimpMessagingTemplate broker, ObjectMapper json) {
        this.kafka = kafka; this.broker = broker; this.json = json;
    }

    @Override
    public void deliver(UUID conversationId, List<UUID> recipients, Event event) {
        try {
            String value = json.writeValueAsString(Map.of("recipients", recipients, "event", event));
            kafka.send(KafkaEventsConfig.CHAT_EVENTS, conversationId.toString(), value)
                    .whenComplete((r, ex) -> { if (ex != null) log.error("Could not publish {} event: {}", event.type(), ex.getMessage()); });
        } catch (Exception e) {
            log.error("Could not publish {} event: {}", event.type(), e.getMessage());
        }
    }

    /**
     * A unique group id per instance means every instance receives every event (broadcast). Each one pushes to
     * its own STOMP broker; users not connected to this instance are simply not found and skipped.
     */
    @KafkaListener(topics = KafkaEventsConfig.CHAT_EVENTS,
            groupId = "#{'vchat-realtime-' + T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    void onEvent(String value) throws Exception {
        JsonNode env = json.readTree(value);
        JsonNode event = env.get("event");
        for (JsonNode uid : env.get("recipients")) {
            broker.convertAndSendToUser(uid.asText(), "/queue/events", event);
        }
    }
}
