package com.vchat.web;

import com.vchat.dto.Dtos.*;
import com.vchat.service.ChatService;
import com.vchat.service.Current;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ConversationController {
    private final ChatService chat;

    public ConversationController(ChatService chat) { this.chat = chat; }

    @GetMapping("/conversations")
    public List<ConversationDto> list() { return chat.list(Current.userId()); }

    @PostMapping("/conversations")
    public ConversationDto create(@RequestBody CreateConversationRequest r) { return chat.create(Current.userId(), r); }

    @PatchMapping("/conversations/{id}/settings")
    public void settings(@PathVariable UUID id, @RequestBody MemberSettingsRequest r) {
        chat.updateMemberSettings(Current.userId(), id, r);
    }

    @PostMapping("/conversations/{id}/read")
    public void read(@PathVariable UUID id) { chat.markRead(Current.userId(), id); }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> history(@PathVariable UUID id,
                                    @RequestParam(required = false) Instant before,
                                    @RequestParam(defaultValue = "50") int limit) {
        return chat.history(Current.userId(), id, before, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    public MessageDto send(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest r) {
        return chat.send(Current.userId(), id, r);
    }

    @PatchMapping("/messages/{id}")
    public MessageDto edit(@PathVariable UUID id, @Valid @RequestBody EditMessageRequest r) {
        return chat.edit(Current.userId(), id, r);
    }

    @PostMapping("/messages/{id}/recall")
    public MessageDto recall(@PathVariable UUID id) { return chat.recall(Current.userId(), id); }

    @PostMapping("/messages/{id}/reactions")
    public MessageDto react(@PathVariable UUID id, @Valid @RequestBody ReactionRequest r) {
        return chat.react(Current.userId(), id, r.emoji());
    }

    /** STOMP: client sends {"conversationId": "...", "typing": true} to /app/typing. */
    @Controller
    public static class TypingSocket {
        private final ChatService chat;

        public TypingSocket(ChatService chat) { this.chat = chat; }

        public record TypingMessage(UUID conversationId, boolean typing) {}

        @MessageMapping("/typing")
        public void typing(@Payload TypingMessage m, Principal principal) {
            chat.typing(UUID.fromString(principal.getName()), m.conversationId(), m.typing());
        }
    }
}
