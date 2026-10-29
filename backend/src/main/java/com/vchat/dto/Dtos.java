package com.vchat.dto;

import com.vchat.domain.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    // ---- auth ----
    public record RegisterRequest(
            @NotBlank(message = "Choose a username")
            @Size(min = 3, max = 40, message = "Username must be 3-40 characters")
            @Pattern(regexp = "[a-zA-Z0-9_.]*", message = "Username may only use letters, numbers, dot and underscore") String username,
            @NotBlank(message = "Enter your display name") @Size(max = 80, message = "Display name is too long") String displayName,
            @NotBlank(message = "Choose a password") @Size(min = 8, max = 100, message = "Password must be at least 8 characters") String password) {}
    public record LoginRequest(@NotBlank(message = "Enter your username") String username,
                               @NotBlank(message = "Enter your password") String password) {}
    /** expiresIn = seconds until the access token expires; the refresh token travels only in the httpOnly cookie. */
    public record AuthResponse(String token, long expiresIn, MeDto user) {}
    /** The signed-in user (includes role; never returned for other people). */
    public record MeDto(UUID id, String username, String displayName, String avatarUrl, String theme, String role) {
        public static MeDto of(AppUser u) {
            return new MeDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getAvatarUrl(), u.getTheme(), u.getRole().name());
        }
    }
    public record AdminUserDto(UUID id, String username, String displayName, String role, boolean disabled,
                               Instant createdAt, Instant lastSeenAt) {
        public static AdminUserDto of(AppUser u) {
            return new AdminUserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().name(), u.isDisabled(),
                    u.getCreatedAt(), u.getLastSeenAt());
        }
    }

    // ---- users ----
    public record UserDto(UUID id, String username, String displayName, String avatarUrl, String theme, Instant lastSeenAt) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getAvatarUrl(), u.getTheme(), u.getLastSeenAt());
        }
    }
    public record UpdateProfileRequest(String displayName, String avatarUrl,
                                       @Pattern(regexp = "light|dark|win95|aero") String theme) {}

    // ---- avatars & bots ----
    public record AvatarDto(UUID id, String name, AvatarModel.Format format, String url, boolean builtIn) {
        public static AvatarDto of(AvatarModel a) {
            return new AvatarDto(a.getId(), a.getName(), a.getFormat(), a.getUrl(), a.getOwner() == null);
        }
    }
    public record AvatarRequest(@NotBlank String name, AvatarModel.Format format, @NotBlank String url) {}

    /** model: the bot's own Claude model, or null for the server default. */
    public record BotDto(UUID id, String name, String persona, String language,
                         BotCharacter.TtsProvider ttsProvider, String voiceId, AvatarDto avatar, String model) {
        public static BotDto of(BotCharacter b) {
            return new BotDto(b.getId(), b.getName(), b.getPersona(), b.getLanguage(), b.getTtsProvider(), b.getVoiceId(),
                    b.getAvatarModel() == null ? null : AvatarDto.of(b.getAvatarModel()), b.getModel());
        }
    }
    /** model: one of GET /api/bots/models, or null/blank for the server default. */
    public record BotRequest(@NotBlank String name, @NotBlank String persona,
                             @Pattern(regexp = "vi|en|zh|ja|ko|es|ru") String language,
                             BotCharacter.TtsProvider ttsProvider, String voiceId, UUID avatarModelId,
                             @Size(max = 64) String model) {}
    /** A Claude model users can pick for their AI friends. */
    public record ModelOption(String id, String label, boolean isDefault) {}

    // ---- stickers ----
    public record StickerDto(UUID id, UUID packId, String name, String url, String mimeType) {
        public static StickerDto of(Sticker s) {
            return new StickerDto(s.getId(), s.getPack().getId(), s.getName(), s.getUrl(), s.getMimeType());
        }
    }
    public record StickerPackDto(UUID id, String name, List<StickerDto> stickers) {
        public static StickerPackDto of(StickerPack p) {
            return new StickerPackDto(p.getId(), p.getName(), p.getStickers().stream().map(StickerDto::of).toList());
        }
    }
    public record StickerPackRequest(@NotBlank @Size(max = 80) String name) {}
    public record StickerRequest(@NotBlank String name, @NotBlank String url, @NotBlank String mimeType) {}

    // ---- conversations & messages ----
    public record ConversationDto(UUID id, Conversation.Type type, String title, BotDto bot, List<UserDto> members,
                                  MessageDto lastMessage, long unread, boolean pinned, boolean muted, Instant updatedAt,
                                  /** member id -> last time they read this chat; drives the ✓✓ read ticks */
                                  Map<UUID, Instant> readBy) {}
    public record CreateConversationRequest(Conversation.Type type, List<UUID> userIds, String title, UUID botId) {}
    public record MemberSettingsRequest(Boolean pinned, Boolean muted) {}

    public record MessageDto(UUID id, UUID conversationId, UUID senderId, boolean fromBot, Message.Type type,
                             String content, StickerDto sticker, String attachmentUrl, UUID replyToId,
                             Instant createdAt, Instant editedAt, boolean recalled,
                             Map<String, List<UUID>> reactions) {
        public static MessageDto of(Message m, Map<String, List<UUID>> reactions) {
            return new MessageDto(m.getId(), m.getConversation().getId(),
                    m.getSender() == null ? null : m.getSender().getId(), m.isFromBot(), m.getType(),
                    m.getContent(), m.getSticker() == null ? null : StickerDto.of(m.getSticker()),
                    m.getAttachmentUrl(), m.getReplyTo() == null ? null : m.getReplyTo().getId(),
                    m.getCreatedAt(), m.getEditedAt(), m.isRecalled(), reactions);
        }
    }
    public record SendMessageRequest(Message.Type type, @Size(max = 8000) String content, UUID stickerId,
                                     String attachmentUrl, UUID replyToId) {}
    public record EditMessageRequest(@NotBlank @Size(max = 8000) String content) {}
    public record ReactionRequest(@NotBlank @Size(max = 16) String emoji) {}

    // ---- realtime events pushed to /user/queue/events ----
    public record Event(String type, UUID conversationId, Object payload) {}
    public record PresenceDto(boolean online, Instant lastSeenAt) {}
    public record TypingEvent(UUID userId, boolean typing) {}
    public record BotSpeech(UUID messageId, String text, String emotion, String language,
                            BotCharacter.TtsProvider ttsProvider, String voiceId) {}

    // ---- uploads & tts ----
    public record UploadResponse(String url, String mimeType, long size) {}
    public record TtsRequest(@NotBlank @Size(max = 2000) String text, String language,
                             BotCharacter.TtsProvider provider, String voiceId) {}
}
