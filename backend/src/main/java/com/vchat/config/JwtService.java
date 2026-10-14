package com.vchat.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Issues and verifies short-lived access tokens (JWT, HMAC-SHA signed). */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    /** Secrets that have appeared in this repo's docs/config; never acceptable outside local dev. */
    private static final Set<String> KNOWN_DEV_SECRETS = Set.of(
            "change-me-change-me-change-me-change-me-0123456789",
            "local-dev-secret-local-dev-secret-0123456789");

    public record Issued(String token, Instant expiresAt) {}

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(VchatProperties props, Environment env) {
        boolean production = env.acceptsProfiles(Profiles.of("aws", "prod"));
        String secret = props.jwtSecret();
        if (secret == null || secret.isBlank()) {
            if (production) throw new IllegalStateException("JWT_SECRET must be set (32+ random characters) in production");
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            log.warn("JWT_SECRET is not set: using a random signing key for this run. Access tokens stop working on "
                    + "restart; browsers renew them silently with their refresh cookie.");
        } else {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
            if (production && KNOWN_DEV_SECRETS.contains(secret)) {
                throw new IllegalStateException("JWT_SECRET is a published development value; generate a new one");
            }
            this.key = Keys.hmacShaKeyFor(bytes);
        }
        this.ttl = Duration.ofMinutes(props.auth().accessTtlMinutes());
    }

    public Issued issue(UUID userId, String username, Set<String> roles) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("username", username)
                .claim("roles", List.copyOf(roles))
                // "iat" has 1-second resolution; revocation needs to order a token against an event in the same second
                .claim("iat_ms", now.toEpochMilli())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, exp);
    }

    /** @throws io.jsonwebtoken.JwtException if the signature is wrong, the token is malformed or expired */
    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Set<String> roles = new HashSet<>();
        Object raw = c.get("roles");
        if (raw instanceof Collection<?> list) list.forEach(r -> roles.add(String.valueOf(r)));
        Number iatMs = c.get("iat_ms", Number.class);
        Instant issued = iatMs != null ? Instant.ofEpochMilli(iatMs.longValue()) : c.getIssuedAt().toInstant();
        return new AuthUser(UUID.fromString(c.getSubject()), c.get("username", String.class), Set.copyOf(roles),
                c.getId(), issued, c.getExpiration().toInstant());
    }

    public Duration ttl() { return ttl; }
}
