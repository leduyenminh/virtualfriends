package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "conversation_member")
public class ConversationMember extends BaseEntity {
    public enum Role { OWNER, ADMIN, MEMBER }

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "conversation_id") private Conversation conversation;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role = Role.MEMBER;
    @Column(name = "last_read_at") private Instant lastReadAt;
    private boolean muted;
    private boolean pinned;

    protected ConversationMember() {}
    public ConversationMember(Conversation conversation, AppUser user, Role role) {
        this.conversation = conversation; this.user = user; this.role = role;
    }

    public Conversation getConversation() { return conversation; }
    public AppUser getUser() { return user; }
    public Role getRole() { return role; }
    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant v) { lastReadAt = v; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean v) { muted = v; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean v) { pinned = v; }
}
