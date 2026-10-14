package com.vchat.service;

import com.vchat.aop.RateLimited;
import com.vchat.config.AuthUser;
import com.vchat.config.JwtService;
import com.vchat.config.TokenRevocationService;
import com.vchat.config.VchatProperties;
import com.vchat.domain.AppUser;
import com.vchat.domain.UserIdentity;
import com.vchat.dto.Dtos.*;
import com.vchat.repo.UserIdentityRepo;
import com.vchat.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** What a successful sign-in produces: the JSON body (access token) and the refresh token for the cookie. */
    public record Session(AuthResponse body, RefreshTokenService.Issued refresh) {}

    private final UserRepo users;
    private final UserIdentityRepo identities;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final TokenRevocationService revocation;
    private final ObjectProvider<RealtimeGateway> realtime; // lazy: AuthService is built early, with the security chain
    private final VchatProperties.Auth cfg;

    public AuthService(UserRepo users, UserIdentityRepo identities, PasswordEncoder encoder, JwtService jwt,
                       RefreshTokenService refreshTokens, TokenRevocationService revocation, ObjectProvider<RealtimeGateway> realtime,
                       VchatProperties props) {
        this.users = users; this.identities = identities; this.encoder = encoder; this.jwt = jwt;
        this.refreshTokens = refreshTokens; this.revocation = revocation; this.realtime = realtime; this.cfg = props.auth();
    }

    @Transactional
    @RateLimited(name = "register", key = "ip", limit = 10, windowSeconds = 3600, message = "Too many sign-ups from this network, try again later")
    public Session register(RegisterRequest r) {
        String username = r.username().toLowerCase(Locale.ROOT);
        if (users.existsByUsername(username)) throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken");
        AppUser u = users.save(new AppUser(username, r.displayName(), encoder.encode(r.password())));
        return startSession(u);
    }

    /** Brute-force guard: 10 attempts per username per 5 minutes, across all backend instances. */
    @Transactional
    @RateLimited(name = "login", key = "#r.username()", limit = 10, windowSeconds = 300, message = "Too many attempts. Wait a few minutes and try again")
    public Session login(LoginRequest r) {
        AppUser u = users.findByUsername(r.username().toLowerCase(Locale.ROOT))
                .filter(x -> x.getPasswordHash() != null && encoder.matches(r.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password"));
        return startSession(u);
    }

    /**
     * Google/Facebook login. Accounts are matched by the provider's stable subject id only, never by e-mail:
     * auto-linking on e-mail would let anyone who controls that address at another provider take over an account.
     */
    @Transactional
    public Session loginWithIdentity(String provider, String subject, String email, String name, String picture) {
        AppUser u = identities.findWithUser(provider, subject).map(UserIdentity::getUser).orElseGet(() -> {
            String display = name != null && !name.isBlank() ? name : "New friend";
            AppUser created = new AppUser(uniqueUsername(email != null ? email.split("@")[0] : display), display, null);
            created.setEmail(email);
            created.setAvatarUrl(picture);
            created = users.save(created);
            identities.save(new UserIdentity(created, provider, subject, email));
            return created;
        });
        return startSession(u);
    }

    /** Exchange the refresh cookie for a fresh access token (and a rotated refresh token). */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    @RateLimited(name = "refresh", key = "ip", limit = 60, windowSeconds = 60)
    public Session refresh(String rawRefresh) {
        if (rawRefresh == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        RefreshTokenService.Rotation rot = refreshTokens.rotate(rawRefresh);
        AppUser u = rot.user();
        if (u.isDisabled()) {
            refreshTokens.revokeAll(u.getId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "This account has been disabled");
        }
        promoteIfConfigured(u);
        return new Session(accessFor(u), rot.next());
    }

    /** This device: kill the refresh-token family and the access token that is still in memory. */
    @Transactional
    public void logout(String rawRefresh, AuthUser current) {
        if (rawRefresh != null) refreshTokens.revokeFamilyOf(rawRefresh);
        if (current != null) revocation.deny(current);
    }

    /** Every device: all refresh tokens, every access token issued until now, and open WebSockets are told to sign out. */
    @Transactional
    public void logoutEverywhere(UUID userId, String reason) {
        int n = refreshTokens.revokeAll(userId);
        revocation.revokeAllBefore(userId, Instant.now());
        realtime.getObject().deliver(userId, List.of(userId), new Event("session.revoked", null, Map.of("reason", reason)));
        log.info("Revoked all sessions of {} ({} refresh tokens): {}", userId, n, reason);
    }

    private Session startSession(AppUser u) {
        if (u.isDisabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been disabled");
        promoteIfConfigured(u);
        return new Session(accessFor(u), refreshTokens.issue(u, UUID.randomUUID()));
    }

    private AuthResponse accessFor(AppUser u) {
        JwtService.Issued at = jwt.issue(u.getId(), u.getUsername(), u.roles());
        long expiresIn = Duration.between(Instant.now(), at.expiresAt()).toSeconds();
        return new AuthResponse(at.token(), expiresIn, MeDto.of(u));
    }

    /** Bootstrap admins from configuration (ADMIN_USERNAMES), so the first admin never needs manual SQL. */
    private void promoteIfConfigured(AppUser u) {
        if (u.getRole() != AppUser.Role.ADMIN && cfg.isAdminUsername(u.getUsername())) {
            u.setRole(AppUser.Role.ADMIN);
            users.save(u);
            log.info("Promoted {} to ADMIN (listed in ADMIN_USERNAMES)", u.getUsername());
        }
    }

    /** "Nguyễn Đức" / "duc.nguyen+x@..." -> "nguyenduc", "duc.nguyenx", plus digits if taken. */
    private String uniqueUsername(String seed) {
        String base = Normalizer.normalize(seed.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.]", "");
        if (base.length() < 3) base = "user" + base;
        if (base.length() > 30) base = base.substring(0, 30);
        String candidate = base;
        while (users.existsByUsername(candidate)) {
            candidate = base + ThreadLocalRandom.current().nextInt(1000, 9999);
        }
        return candidate;
    }
}
