package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {
    public enum Role { USER, ADMIN }

    @Column(nullable = false, unique = true) private String username;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "password_hash") private String passwordHash;   // null for Google/Facebook-only accounts
    private String email;
    @Column(name = "avatar_url") private String avatarUrl;
    @Column(nullable = false) private String theme = "light";
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role = Role.USER;
    @Column(nullable = false) private boolean disabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "last_seen_at") private Instant lastSeenAt;

    protected AppUser() {}
    public AppUser(String username, String displayName, String passwordHash) {
        this.username = username; this.displayName = displayName; this.passwordHash = passwordHash;
    }

    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getPasswordHash() { return passwordHash; }
    public String getEmail() { return email; }
    public void setEmail(String v) { email = v; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String v) { avatarUrl = v; }
    public String getTheme() { return theme; }
    public void setTheme(String v) { theme = v; }
    public Role getRole() { return role; }
    public void setRole(Role v) { role = v; }
    public Set<String> roles() { return role == Role.ADMIN ? Set.of("USER", "ADMIN") : Set.of("USER"); }
    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean v) { disabled = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant v) { lastSeenAt = v; }
}
