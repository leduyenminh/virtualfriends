package com.vchat.service;

import com.vchat.domain.AppUser;
import com.vchat.domain.Message;
import com.vchat.dto.Dtos.AdminUserDto;
import com.vchat.dto.Dtos.MessageDto;
import com.vchat.repo.MessageRepo;
import com.vchat.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Moderation. @PreAuthorize repeats the URL rule so these can't be reached from a non-admin code path either. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminService {
    private static final Logger audit = LoggerFactory.getLogger("vchat.audit");

    private final UserRepo users;
    private final MessageRepo messages;
    private final AuthService auth;
    private final EventPublisher events;

    public AdminService(UserRepo users, MessageRepo messages, AuthService auth, EventPublisher events) {
        this.users = users; this.messages = messages; this.auth = auth; this.events = events;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> users(String q) {
        var page = PageRequest.of(0, 50, Sort.by("createdAt").descending());
        List<AppUser> list = q == null || q.isBlank() ? users.findAll(page).getContent() : users.search(q.strip(), PageRequest.of(0, 50));
        return list.stream().map(AdminUserDto::of).toList();
    }

    @Transactional
    public AdminUserDto setDisabled(UUID adminId, UUID userId, boolean disabled) {
        if (adminId.equals(userId)) throw new IllegalArgumentException("You can't disable your own account");
        AppUser u = user(userId);
        u.setDisabled(disabled);
        if (disabled) auth.logoutEverywhere(userId, "Your account has been disabled by a moderator");
        audit.info("admin={} {} user={} ({})", adminId, disabled ? "DISABLED" : "ENABLED", userId, u.getUsername());
        return AdminUserDto.of(u);
    }

    @Transactional
    public AdminUserDto setRole(UUID adminId, UUID userId, AppUser.Role role) {
        if (adminId.equals(userId) && role != AppUser.Role.ADMIN) throw new IllegalArgumentException("You can't remove your own admin role");
        AppUser u = user(userId);
        u.setRole(role);
        // Role is baked into access tokens: force a refresh so the change applies within seconds, not 15 minutes.
        auth.logoutEverywhere(userId, "Your permissions changed. Please sign in again.");
        audit.info("admin={} set role {} on user={} ({})", adminId, role, userId, u.getUsername());
        return AdminUserDto.of(u);
    }

    @Transactional
    public MessageDto removeMessage(UUID adminId, UUID messageId) {
        Message m = messages.findById(messageId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found"));
        m.recall();
        MessageDto dto = MessageDto.of(m, Map.of());
        events.toConversation(m.getConversation().getId(), "message.updated", dto);
        audit.info("admin={} removed message={} in conversation={}", adminId, messageId, m.getConversation().getId());
        return dto;
    }

    private AppUser user(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }
}
