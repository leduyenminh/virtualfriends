package com.vchat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Early revocation of access tokens, without a DB lookup per request. Two Redis keys, checked with one MGET:
 *   jwt:deny:{jti}               this one token is dead (logout), kept until the token would have expired anyway
*   jwt:revoked-before:{userId}  every token issued up to this millisecond is dead (log out everywhere / account disabled)
 * Fails open with a warning if Redis is down: tokens then simply live until their (15-minute) expiry.
 */
@Service
public class TokenRevocationService {
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;
    private final JwtService jwt;

    public TokenRevocationService(StringRedisTemplate redis, JwtService jwt) { this.redis = redis; this.jwt = jwt; }

    public void deny(AuthUser token) {
        Duration left = Duration.between(Instant.now(), token.expiresAt());
        if (left.isNegative() || left.isZero() || token.tokenId() == null) return;
        try {
            redis.opsForValue().set("jwt:deny:" + token.tokenId(), "1", left.plusSeconds(5));
        } catch (DataAccessException e) {
            log.warn("Could not deny token (Redis unavailable): {}", e.getMessage());
        }
    }

    public void revokeAllBefore(UUID userId, Instant at) {
        try {
            redis.opsForValue().set("jwt:revoked-before:" + userId, String.valueOf(at.toEpochMilli()),
                    jwt.ttl().plusMinutes(1));
        } catch (DataAccessException e) {
            log.warn("Could not revoke tokens for {} (Redis unavailable): {}", userId, e.getMessage());
        }
    }

    public boolean isRevoked(AuthUser token) {
        try {
            List<String> v = redis.opsForValue().multiGet(List.of(
                    "jwt:deny:" + token.tokenId(), "jwt:revoked-before:" + token.id()));
            if (v == null) return false;
            if (v.get(0) != null) return true;
            return v.get(1) != null && token.issuedAt().toEpochMilli() <= Long.parseLong(v.get(1));
        } catch (DataAccessException e) {
            log.warn("Revocation check skipped (Redis unavailable): {}", e.getMessage());
            return false;
        }
    }
}
