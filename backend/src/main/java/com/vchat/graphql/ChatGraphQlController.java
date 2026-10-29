package com.vchat.graphql;

import com.vchat.dto.Dtos.*;
import com.vchat.graphql.GraphQlTypes.Presence;
import com.vchat.graphql.GraphQlTypes.Reaction;
import com.vchat.graphql.GraphQlTypes.ReadReceipt;
import com.vchat.repo.AvatarModelRepo;
import com.vchat.repo.BotRepo;
import com.vchat.repo.MessageRepo;
import com.vchat.repo.StickerPackRepo;
import com.vchat.repo.UserRepo;
import com.vchat.service.AdminService;
import com.vchat.service.ChatService;
import com.vchat.service.Current;
import com.vchat.service.PresenceService;
import com.vchat.web.BotController;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolvers for schema.graphqls. They are thin: every rule (membership checks, 24h edit window, rate limits,
 * admin role) lives in the same services the REST controllers use, so both APIs behave identically.
 *
 * N+1 protection: Message.sender and Message.replyTo use @BatchMapping, so a page of 50 messages loads its senders
 * with one query and its quoted messages with one more, instead of up to 100 single-row queries.
 */
@Controller
public class ChatGraphQlController {
    private static final int DEFAULT_PAGE = 50;

    private final ChatService chat;
    private final AdminService admin;
    private final PresenceService presence;
    private final BotController botController;
    private final UserRepo users;
    private final MessageRepo messages;
    private final BotRepo bots;
    private final AvatarModelRepo avatars;
    private final StickerPackRepo packs;

    public ChatGraphQlController(ChatService chat, AdminService admin, PresenceService presence, BotController botController,
                                 UserRepo users, MessageRepo messages, BotRepo bots, AvatarModelRepo avatars, StickerPackRepo packs) {
        this.chat = chat; this.admin = admin; this.presence = presence; this.botController = botController;
        this.users = users; this.messages = messages; this.bots = bots; this.avatars = avatars; this.packs = packs;
    }

    // ------------------------------------------------------------------ queries

    @QueryMapping
    public MeDto me() {
        return users.findById(Current.userId()).map(MeDto::of).orElseThrow();
    }

    @QueryMapping
    public List<ConversationDto> conversations() {
        return chat.list(Current.userId());
    }

    @QueryMapping
    public ConversationDto conversation(@Argument UUID id) {
        return chat.get(Current.userId(), id);
    }

    @QueryMapping
    public List<MessageDto> messages(@Argument UUID conversationId, @Argument Instant before, @Argument Integer limit) {
        return chat.history(Current.userId(), conversationId, before, limit == null ? DEFAULT_PAGE : limit);
    }

    @QueryMapping
    public List<UserDto> searchUsers(@Argument String query) {
        Current.userId();
        if (query == null || query.strip().length() < 2) return List.of();
        return users.search(query.strip(), PageRequest.of(0, 20)).stream().map(UserDto::of).toList();
    }

    @QueryMapping
    public List<Presence> presence(@Argument List<UUID> userIds) {
        Current.userId();
        if (userIds.size() > 100) throw new IllegalArgumentException("At most 100 ids");
        return presence.lookup(userIds).entrySet().stream()
                .map(e -> new Presence(e.getKey(), e.getValue().online(), e.getValue().lastSeenAt()))
                .toList();
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public List<BotDto> bots() {
        return bots.findByOwnerId(Current.userId()).stream().map(BotDto::of).toList();
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public List<AvatarDto> avatars() {
        return avatars.visibleTo(Current.userId()).stream().map(AvatarDto::of).toList();
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public List<StickerPackDto> stickerPacks() {
        return packs.findByOwnerIdOrderByCreatedAt(Current.userId()).stream().map(StickerPackDto::of).toList();
    }

    @QueryMapping
    public List<ModelOption> botModels() {
        Current.userId();
        return botController.models();
    }

    /** AdminService is @PreAuthorize("hasRole('ADMIN')"), so non-admins get a FORBIDDEN error here. */
    @QueryMapping
    public List<AdminUserDto> adminUsers(@Argument String query) {
        return admin.users(query);
    }

    // ------------------------------------------------------------------ nested fields

    @SchemaMapping(typeName = "Conversation", field = "readBy")
    public List<ReadReceipt> readBy(ConversationDto c) {
        return c.readBy() == null ? List.of() : c.readBy().entrySet().stream()
                .map(e -> new ReadReceipt(e.getKey(), e.getValue()))
                .toList();
    }

    @SchemaMapping(typeName = "Conversation", field = "messages")
    public List<MessageDto> conversationMessages(ConversationDto c, @Argument Instant before, @Argument Integer limit) {
        return chat.history(Current.userId(), c.id(), before, limit == null ? 20 : limit);
    }

    @SchemaMapping(typeName = "Message", field = "reactions")
    public List<Reaction> reactions(MessageDto m) {
        if (m.reactions() == null) return List.of();
        return m.reactions().entrySet().stream()
                .map(e -> new Reaction(e.getKey(), e.getValue().size(), e.getValue()))
                .toList();
    }

    /** All senders of all messages in the response: one SELECT ... WHERE id IN (...). */
    @BatchMapping(typeName = "Message", field = "sender")
    public Map<MessageDto, UserDto> sender(List<MessageDto> list) {
        Set<UUID> ids = list.stream().map(MessageDto::senderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserDto> byId = users.findAllById(ids).stream().map(UserDto::of)
                .collect(Collectors.toMap(UserDto::id, Function.identity()));
        Map<MessageDto, UserDto> out = new HashMap<>();
        for (MessageDto m : list) if (m.senderId() != null && byId.containsKey(m.senderId())) out.put(m, byId.get(m.senderId()));
        return out;
    }

    /** All quoted messages in the response: one query. Replies are always in the same chat (enforced on send). */
    @BatchMapping(typeName = "Message", field = "replyTo")
    @Transactional(readOnly = true)
    public Map<MessageDto, MessageDto> replyTo(List<MessageDto> list) {
        Set<UUID> ids = list.stream().map(MessageDto::replyToId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<UUID, MessageDto> byId = messages.findAllById(ids).stream().map(m -> MessageDto.of(m, Map.of()))
                .collect(Collectors.toMap(MessageDto::id, Function.identity()));
        Map<MessageDto, MessageDto> out = new HashMap<>();
        for (MessageDto m : list) if (m.replyToId() != null && byId.containsKey(m.replyToId())) out.put(m, byId.get(m.replyToId()));
        return out;
    }

    // ------------------------------------------------------------------ mutations

    @MutationMapping
    public MessageDto sendMessage(@Argument UUID conversationId, @Argument @Valid SendMessageRequest input) {
        return chat.send(Current.userId(), conversationId, input);
    }

    @MutationMapping
    public MessageDto editMessage(@Argument UUID id, @Argument String content) {
        return chat.edit(Current.userId(), id, new EditMessageRequest(content));
    }

    @MutationMapping
    public MessageDto recallMessage(@Argument UUID id) {
        return chat.recall(Current.userId(), id);
    }

    @MutationMapping
    public MessageDto toggleReaction(@Argument UUID messageId, @Argument String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.length() > 16) throw new IllegalArgumentException("Invalid emoji");
        return chat.react(Current.userId(), messageId, emoji);
    }

    @MutationMapping
    public boolean markRead(@Argument UUID conversationId) {
        chat.markRead(Current.userId(), conversationId);
        return true;
    }

    @MutationMapping
    public ConversationDto createConversation(@Argument CreateConversationRequest input) {
        return chat.create(Current.userId(), input);
    }

    @MutationMapping
    public boolean updateConversationSettings(@Argument UUID conversationId, @Argument Boolean pinned, @Argument Boolean muted) {
        chat.updateMemberSettings(Current.userId(), conversationId, new MemberSettingsRequest(pinned, muted));
        return true;
    }
}
