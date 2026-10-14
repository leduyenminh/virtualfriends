package com.vchat.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message")
public class Message extends BaseEntity {
    public enum Type { TEXT, STICKER, IMAGE, FILE, AUDIO, SYSTEM }

    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "conversation_id") private Conversation conversation;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sender_id") private AppUser sender;
    @Column(name = "from_bot", nullable = false) private boolean fromBot;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Type type;
    @Column(columnDefinition = "text") private String content;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "sticker_id") private Sticker sticker;
    @Column(name = "attachment_url") private String attachmentUrl;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reply_to_id") private Message replyTo;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "edited_at") private Instant editedAt;
    private boolean recalled;

    protected Message() {}
    public Message(Conversation conversation, AppUser sender, boolean fromBot, Type type) {
        this.conversation = conversation; this.sender = sender; this.fromBot = fromBot; this.type = type;
    }

    public Conversation getConversation() { return conversation; }
    public AppUser getSender() { return sender; }
    public boolean isFromBot() { return fromBot; }
    public Type getType() { return type; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public Sticker getSticker() { return sticker; }
    public void setSticker(Sticker v) { sticker = v; }
    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String v) { attachmentUrl = v; }
    public Message getReplyTo() { return replyTo; }
    public void setReplyTo(Message v) { replyTo = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant v) { editedAt = v; }
    public boolean isRecalled() { return recalled; }
    /** Zalo/Messenger-style "unsend": keep the row so the thread shows "message recalled". */
    public void recall() { recalled = true; content = null; attachmentUrl = null; sticker = null; }
}
