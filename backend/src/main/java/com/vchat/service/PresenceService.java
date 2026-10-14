package com.vchat.service;

import com.vchat.config.AuthUser;
import com.vchat.dto.Dtos.PresenceDto;
import com.vchat.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Online / "last seen" presence. Each user's open WebSocket sessions are a Redis SET (presence:{userId}), so it
 * works across instances and multiple tabs, and SADD/SREM are idempotent (Spring may publish a disconnect twice).
 * The DB is only written once, when the last session closes.
 */
@Service
public class PresenceService {
    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final Duration TTL = Duration.ofHours(12); // self-heals if an instance crashes

    private final StringRedisTemplate redis;
    private final UserRepo users;

    public PresenceService(StringRedisTemplate redis, UserRepo users) { this.redis = redis; this.users = users; }

    @EventListener
    public void connected(SessionConnectedEvent e) {
        userId(e.getUser()).ifPresent(id -> {
            String sessionId = (String) e.getMessage().getHeaders().get("simpSessionId");
            try {
                redis.opsForSet().add(key(id), sessionId);
                redis.expire(key(id), TTL);
            } catch (DataAccessException ex) {
                log.warn("Presence unavailable: {}", ex.getMessage());
            }
        });
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent e) {
        userId(e.getUser()).ifPresent(id -> {
            try {
                redis.opsForSet().remove(key(id), e.getSessionId());
                Long left = redis.opsForSet().size(key(id));
                if (left == null || left == 0) users.touchLastSeen(id, Instant.now());
            } catch (DataAccessException ex) {
                log.warn("Presence unavailable: {}", ex.getMessage());
            }
        });
    }

    public Map<UUID, PresenceDto> lookup(Collection<UUID> ids) {
        Map<UUID, PresenceDto> out = new LinkedHashMap<>();
        Map<UUID, Instant> lastSeen = new HashMap<>();
        users.findAllById(ids).forEach(u -> lastSeen.put(u.getId(), u.getLastSeenAt()));
        for (UUID id : ids) {
            boolean online = false;
            try {
                Long n = redis.opsForSet().size(key(id));
                online = n != null && n > 0;
            } catch (DataAccessException ex) {
                log.warn("Presence unavailable: {}", ex.getMessage());
            }
            out.put(id, new PresenceDto(online, lastSeen.get(id)));
        }
        return out;
    }

    private static String key(UUID id) { return "presence:" + id; }

    private static Optional<UUID> userId(Principal p) {
        return p instanceof AuthUser u ? Optional.of(u.id()) : Optional.empty();
    }
}
