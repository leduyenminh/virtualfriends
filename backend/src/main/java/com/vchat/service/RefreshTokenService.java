package com.vchat.service;

import com.vchat.config.VchatProperties;
import com.vchat.domain.AppUser;
import com.vchat.domain.RefreshToken;
import com.vchat.repo.RefreshTokenRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens with rotation and reuse detection (the OAuth 2.0 Security BCP pattern):
 *  - each refresh returns a NEW refresh token and retires the old one;
 *  - if a retired token is presented again after the grace window, someone has a stolen copy, so the whole
 *    family (every token descending from that login) is revoked and both the thief and the user must sign in again.
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String raw, Instant expiresAt) {}
    /** next == null: concurrent refresh from another tab inside the grace window; the browser already has the newer cookie. */
    public record Rotation(AppUser user, Issued next) {}

    private final RefreshTokenRepo repo;
    private final Duration ttl;
    private final Duration grace;

    public RefreshTokenService(RefreshTokenRepo repo, VchatProperties props) {
        this.repo = repo;
        this.ttl = Duration.ofDays(props.auth().refreshTtlDays());
        this.grace = Duration.ofSeconds(props.auth().reuseGraceSeconds());
    }

    @Transactional
    public Issued issue(AppUser user, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(ttl);
        HttpServletRequest req = currentRequest();
        String ua = req == null ? null : truncate(req.getHeader("User-Agent"), 255);
        String ip = req == null ? null : truncate(req.getRemoteAddr(), 64);
        repo.save(new RefreshToken(user, hash(raw), familyId, expires, ua, ip));
        return new Issued(raw, expires);
    }

    /** noRollbackFor: a detected reuse must stay revoked even though we answer 401. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Rotation rotate(String raw) {
        RefreshToken t = repo.findByHash(hash(raw)).orElseThrow(() -> expired());
        Instant now = Instant.now();
        if (t.getRevokedAt() != null || t.getExpiresAt().isBefore(now)) throw expired();
        if (t.getRotatedAt() != null) {
            if (t.getRotatedAt().isAfter(now.minus(grace))) return new Rotation(t.getUser(), null);
            int n = repo.revokeFamily(t.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}: revoked {} session token(s)", t.getUser().getId(), n);
            throw expired();
        }
        t.markRotated(now);
        return new Rotation(t.getUser(), issue(t.getUser(), t.getFamilyId()));
    }

    /** Logout on this device: kills this token and everything rotated from the same login. */
    @Transactional
    public void revokeFamilyOf(String raw) {
        repo.findByHash(hash(raw)).ifPresent(t -> repo.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public int revokeAll(UUID userId) {
        return repo.revokeAllForUser(userId, Instant.now());
    }

    /** Housekeeping, 03:30 every night. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        int n = repo.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
        if (n > 0) log.info("Purged {} expired refresh tokens", n);
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Your session has expired. Please sign in again.");
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
