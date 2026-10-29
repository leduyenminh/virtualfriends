package com.vchat.service;

import com.vchat.aop.RateLimited;
import com.vchat.domain.*;
import com.vchat.dto.Dtos.*;
import com.vchat.repo.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatService {
    /** Like Zalo/Messenger: recall ("unsend") and edit are only allowed for a limited window. */
    private static final Duration EDIT_WINDOW = Duration.ofHours(24);

    private final ConversationRepo conversations;
    private final MemberRepo members;
    private final MessageRepo messages;
    private final ReactionRepo reactions;
    private final UserRepo users;
    private final BotRepo bots;
    private final StickerRepo stickers;
    private final EventPublisher events;
    private final ApplicationEventPublisher appEvents;

    public ChatService(ConversationRepo conversations, MemberRepo members, MessageRepo messages, ReactionRepo reactions,
                       UserRepo users, BotRepo bots, StickerRepo stickers, EventPublisher events,
                       ApplicationEventPublisher appEvents) {
        this.conversations = conversations; this.members = members; this.messages = messages; this.reactions = reactions;
        this.users = users; this.bots = bots; this.stickers = stickers; this.events = events; this.appEvents = appEvents;
    }

    /** Published after a user writes in a BOT conversation; BotService replies asynchronously. */
    public record BotTurnRequested(UUID conversationId) {}

    // ---------------- conversations ----------------

    /**
     * Chat list in exactly 4 queries however many chats the user has (previously 3 + members per chat: N+1).
     * Lazy stickers on the preview messages load in one batch via hibernate.default_batch_fetch_size.
     */
    @Transactional(readOnly = true)
    public List<ConversationDto> list(UUID me) {
        List<ConversationMember> mine = members.findMineWithConversation(me);
        if (mine.isEmpty()) return List.of();
        List<UUID> ids = mine.stream().map(m -> m.getConversation().getId()).toList();

        Map<UUID, List<ConversationMember>> membersByConv = members.findAllWithUser(ids).stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId()));
        Map<UUID, Message> latest = messages.findLatestPerConversation(ids).stream()
                .collect(Collectors.toMap(m -> m.getConversation().getId(), m -> m, (a, b) -> a));
        Map<UUID, Long> unread = new HashMap<>();
        for (Object[] row : messages.countUnreadByConversation(me)) {
            unread.put(UUID.fromString(row[0].toString()), ((Number) row[1]).longValue());
        }

        return mine.stream().map(m -> {
            UUID id = m.getConversation().getId();
            return assemble(m, membersByConv.getOrDefault(id, List.of()), latest.get(id), unread.getOrDefault(id, 0L), me);
        }).toList();
    }

    @Transactional
    public ConversationDto create(UUID me, CreateConversationRequest r) {
        AppUser self = user(me);
        Conversation c;
        switch (r.type()) {
            case DIRECT -> {
                if (r.userIds() == null || r.userIds().size() != 1) throw new IllegalArgumentException("DIRECT needs exactly one other user");
                UUID other = r.userIds().get(0);
                Optional<Conversation> existing = conversations.findDirect(me, other);
                if (existing.isPresent()) return toDto(existing.get(), me);
                c = conversations.save(new Conversation(Conversation.Type.DIRECT, null, null));
                members.save(new ConversationMember(c, self, ConversationMember.Role.OWNER));
                members.save(new ConversationMember(c, user(other), ConversationMember.Role.MEMBER));
            }
            case GROUP -> {
                c = conversations.save(new Conversation(Conversation.Type.GROUP,
                        Optional.ofNullable(r.title()).orElse("New group"), null));
                members.save(new ConversationMember(c, self, ConversationMember.Role.OWNER));
                for (UUID id : Optional.ofNullable(r.userIds()).orElse(List.of())) {
                    if (!id.equals(me)) members.save(new ConversationMember(c, user(id), ConversationMember.Role.MEMBER));
                }
            }
            case BOT -> {
                BotCharacter bot = bots.findById(r.botId()).orElseThrow(() -> notFound("bot"));
                if (bot.getOwner() != null && !bot.getOwner().getId().equals(me)) throw forbidden();
                c = conversations.save(new Conversation(Conversation.Type.BOT, bot.getName(), bot));
                members.save(new ConversationMember(c, self, ConversationMember.Role.OWNER));
            }
            default -> throw new IllegalArgumentException("type required");
        }
        ConversationDto dto = toDto(c, me);
        events.toConversation(c.getId(), "conversation.new", dto);
        return dto;
    }

    /** One conversation the caller belongs to (403 otherwise). */
    @Transactional(readOnly = true)
    public ConversationDto get(UUID me, UUID conversationId) {
        return toDto(membership(me, conversationId).getConversation(), me);
    }

    @Transactional
    public void updateMemberSettings(UUID me, UUID conversationId, MemberSettingsRequest r) {
        ConversationMember m = membership(me, conversationId);
        if (r.pinned() != null) m.setPinned(r.pinned());
        if (r.muted() != null) m.setMuted(r.muted());
    }

    @Transactional
    public void markRead(UUID me, UUID conversationId) {
        Instant now = Instant.now();
        membership(me, conversationId).setLastReadAt(now);
        events.toConversation(conversationId, "read", Map.of("userId", me, "at", now));
    }

    // ---------------- messages ----------------

    @Transactional(readOnly = true)
    public List<MessageDto> history(UUID me, UUID conversationId, Instant before, int limit) {
        membership(me, conversationId);
        List<Message> page = new ArrayList<>(messages.page(conversationId,
                before == null ? Instant.now().plusSeconds(1) : before, PageRequest.of(0, Math.min(limit, 100))));
        Collections.reverse(page); // oldest first for rendering
        return toDtos(page);
    }

    @Transactional
    @RateLimited(name = "send", limit = 30, windowSeconds = 10, message = "You're sending messages too fast")
    public MessageDto send(UUID me, UUID conversationId, SendMessageRequest r) {
        ConversationMember mem = membership(me, conversationId);
        Conversation c = mem.getConversation();
        Message.Type type = r.type() == null ? Message.Type.TEXT : r.type();
        Message m = new Message(c, mem.getUser(), false, type);
        switch (type) {
            case TEXT -> {
                if (r.content() == null || r.content().isBlank()) throw new IllegalArgumentException("Empty message");
                m.setContent(r.content());
            }
            case STICKER -> m.setSticker(stickers.findById(r.stickerId()).orElseThrow(() -> notFound("sticker")));
            case IMAGE, FILE, AUDIO -> {
                if (r.attachmentUrl() == null) throw new IllegalArgumentException("attachmentUrl required");
                m.setAttachmentUrl(r.attachmentUrl());
                m.setContent(r.content());
            }
            case SYSTEM -> throw new IllegalArgumentException("Clients cannot send SYSTEM messages");
        }
        if (r.replyToId() != null) {
            Message parent = messages.findById(r.replyToId()).orElseThrow(() -> notFound("message"));
            if (!parent.getConversation().getId().equals(conversationId)) throw new IllegalArgumentException("Reply to another conversation");
            m.setReplyTo(parent);
        }
        m = messages.save(m);
        c.touch();
        mem.setLastReadAt(m.getCreatedAt());

        MessageDto dto = MessageDto.of(m, Map.of());
        events.toConversation(conversationId, "message.new", dto);
        if (c.getType() == Conversation.Type.BOT && type != Message.Type.SYSTEM) {
            appEvents.publishEvent(new BotTurnRequested(conversationId));
        }
        return dto;
    }

    /** Used by BotService to persist the bot's reply. */
    @Transactional
    public MessageDto saveBotMessage(UUID conversationId, String text) {
        Conversation c = conversations.findById(conversationId).orElseThrow(() -> notFound("conversation"));
        Message m = new Message(c, null, true, Message.Type.TEXT);
        m.setContent(text);
        m = messages.save(m);
        c.touch();
        MessageDto dto = MessageDto.of(m, Map.of());
        events.toConversation(conversationId, "message.new", dto);
        return dto;
    }

    @Transactional
    public MessageDto edit(UUID me, UUID messageId, EditMessageRequest r) {
        Message m = ownMessage(me, messageId);
        if (m.getType() != Message.Type.TEXT) throw new IllegalArgumentException("Only text messages can be edited");
        m.setContent(r.content());
        m.setEditedAt(Instant.now());
        MessageDto dto = toDtos(List.of(m)).get(0);
        events.toConversation(m.getConversation().getId(), "message.updated", dto);
        return dto;
    }

    @Transactional
    public MessageDto recall(UUID me, UUID messageId) {
        Message m = ownMessage(me, messageId);
        m.recall();
        MessageDto dto = toDtos(List.of(m)).get(0);
        events.toConversation(m.getConversation().getId(), "message.updated", dto);
        return dto;
    }

    /** Toggles a reaction (adding the same emoji twice removes it). */
    @Transactional
    public MessageDto react(UUID me, UUID messageId, String emoji) {
        Message m = messages.findById(messageId).orElseThrow(() -> notFound("message"));
        ConversationMember mem = membership(me, m.getConversation().getId());
        reactions.findByMessageIdAndUserIdAndEmoji(messageId, me, emoji).ifPresentOrElse(
                reactions::delete,
                () -> reactions.save(new MessageReaction(m, mem.getUser(), emoji)));
        reactions.flush();
        MessageDto dto = toDtos(List.of(m)).get(0);
        events.toConversation(m.getConversation().getId(), "message.updated", dto);
        return dto;
    }

    public void typing(UUID me, UUID conversationId, boolean typing) {
        if (!members.existsByConversationIdAndUserId(conversationId, me)) return;
        events.toConversation(conversationId, "typing", new TypingEvent(me, typing));
    }

    // ---------------- helpers ----------------

    ConversationMember membership(UUID me, UUID conversationId) {
        return members.findByConversationIdAndUserId(conversationId, me).orElseThrow(ChatService::forbidden);
    }

    private Message ownMessage(UUID me, UUID messageId) {
        Message m = messages.findById(messageId).orElseThrow(() -> notFound("message"));
        if (m.getSender() == null || !m.getSender().getId().equals(me)) throw forbidden();
        if (m.getCreatedAt().plus(EDIT_WINDOW).isBefore(Instant.now())) throw new IllegalArgumentException("Too late to change this message");
        return m;
    }

    private List<MessageDto> toDtos(List<Message> list) {
        if (list.isEmpty()) return List.of();
        Map<UUID, Map<String, List<UUID>>> byMessage = new HashMap<>();
        for (MessageReaction r : reactions.findByMessageIdIn(list.stream().map(Message::getId).toList())) {
            byMessage.computeIfAbsent(r.getMessage().getId(), k -> new TreeMap<>())
                    .computeIfAbsent(r.getEmoji(), k -> new ArrayList<>()).add(r.getUser().getId());
        }
        return list.stream().map(m -> MessageDto.of(m, byMessage.getOrDefault(m.getId(), Map.of()))).toList();
    }

    /** Single conversation (after create). */
    private ConversationDto toDto(Conversation c, UUID me) {
        List<ConversationMember> ms = members.findAllWithUser(List.of(c.getId()));
        ConversationMember mine = ms.stream().filter(m -> m.getUser().getId().equals(me)).findFirst().orElseThrow();
        Message last = messages.findFirstByConversationIdOrderByCreatedAtDesc(c.getId()).orElse(null);
        long unread = messages.countUnread(c.getId(),
                mine.getLastReadAt() == null ? Instant.EPOCH : mine.getLastReadAt(), me);
        return assemble(mine, ms, last, unread, me);
    }

    private ConversationDto assemble(ConversationMember mine, List<ConversationMember> ms, Message last, long unread, UUID me) {
        Conversation c = mine.getConversation();
        List<UserDto> memberDtos = ms.stream().map(m -> UserDto.of(m.getUser())).collect(Collectors.toList());
        Map<UUID, Instant> readBy = new HashMap<>();
        for (ConversationMember m : ms) {
            if (m.getLastReadAt() != null) readBy.put(m.getUser().getId(), m.getLastReadAt());
        }
        String title = c.getTitle();
        if (c.getType() == Conversation.Type.DIRECT) {
            title = memberDtos.stream().filter(u -> !u.id().equals(me)).map(UserDto::displayName).findFirst().orElse("Chat");
        }
        return new ConversationDto(c.getId(), c.getType(), title, c.getBot() == null ? null : BotDto.of(c.getBot()),
                memberDtos, last == null ? null : MessageDto.of(last, Map.of()), unread, mine.isPinned(), mine.isMuted(),
                c.getUpdatedAt(), readBy);
    }

    private AppUser user(UUID id) { return users.findById(id).orElseThrow(() -> notFound("user")); }

    static ResponseStatusException notFound(String what) { return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found"); }
    static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed"); }
}
