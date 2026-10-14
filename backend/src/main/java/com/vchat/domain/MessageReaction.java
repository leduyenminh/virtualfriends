package com.vchat.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "message_reaction")
public class MessageReaction extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "message_id") private Message message;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private AppUser user;
    @Column(nullable = false) private String emoji;

    protected MessageReaction() {}
    public MessageReaction(Message message, AppUser user, String emoji) {
        this.message = message; this.user = user; this.emoji = emoji;
    }

    public Message getMessage() { return message; }
    public AppUser getUser() { return user; }
    public String getEmoji() { return emoji; }
}
