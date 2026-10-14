package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Links an external login (Google "sub", Facebook "id") to a local account. */
@Entity
@Table(name = "user_identity")
public class UserIdentity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private AppUser user;
    @Column(nullable = false) private String provider;
    @Column(nullable = false) private String subject;
    private String email;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected UserIdentity() {}
    public UserIdentity(AppUser user, String provider, String subject, String email) {
        this.user = user; this.provider = provider; this.subject = subject; this.email = email;
    }

    public AppUser getUser() { return user; }
    public String getProvider() { return provider; }
    public String getSubject() { return subject; }
}
