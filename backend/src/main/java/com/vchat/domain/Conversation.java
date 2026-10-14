package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "conversation")
public class Conversation extends BaseEntity {
    public enum Type { DIRECT, GROUP, BOT }

    @Enumerated(EnumType.STRING) @Column(nullable = false) private Type type;
    private String title;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "bot_id") private BotCharacter bot;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected Conversation() {}
    public Conversation(Type type, String title, BotCharacter bot) {
        this.type = type; this.title = title; this.bot = bot;
    }

    public Type getType() { return type; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public BotCharacter getBot() { return bot; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = Instant.now(); }
}
