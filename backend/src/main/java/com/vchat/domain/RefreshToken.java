package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Server-side session. The raw token only ever exists in the user's cookie; we keep its SHA-256. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private AppUser user;
    @Column(name = "token_hash", nullable = false, unique = true) private String tokenHash;
    @Column(name = "family_id", nullable = false) private UUID familyId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "rotated_at") private Instant rotatedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "user_agent") private String userAgent;
    private String ip;

    protected RefreshToken() {}
    public RefreshToken(AppUser user, String tokenHash, UUID familyId, Instant expiresAt, String userAgent, String ip) {
        this.user = user; this.tokenHash = tokenHash; this.familyId = familyId; this.expiresAt = expiresAt;
        this.userAgent = userAgent; this.ip = ip;
    }

    public AppUser getUser() { return user; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void markRotated(Instant at) { rotatedAt = at; }
}
