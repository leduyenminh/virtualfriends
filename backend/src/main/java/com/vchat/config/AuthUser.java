package com.vchat.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated principal, rebuilt from the access token on every request (no DB lookup).
 * getName() is the user id so STOMP's /user/{name}/queue routing works by id.
 * tokenId (the JWT "jti") and the issue/expiry times let a single token be revoked before it expires.
 */
public record AuthUser(UUID id, String username, Set<String> roles, String tokenId, Instant issuedAt, Instant expiresAt)
        implements Principal {

    @Override
    public String getName() { return id.toString(); }

    public boolean isAdmin() { return roles.contains("ADMIN"); }

    public List<GrantedAuthority> authorities() {
        return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
