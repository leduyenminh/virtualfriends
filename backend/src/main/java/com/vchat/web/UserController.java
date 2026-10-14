package com.vchat.web;

import com.vchat.domain.AppUser;
import com.vchat.dto.Dtos.*;
import com.vchat.repo.UserRepo;
import com.vchat.service.Current;
import com.vchat.service.PresenceService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserRepo users;
    private final PresenceService presence;

    public UserController(UserRepo users, PresenceService presence) { this.users = users; this.presence = presence; }

    @GetMapping("/me")
    @Transactional
    public MeDto me() {
        AppUser u = users.findById(Current.userId()).orElseThrow();
        u.setLastSeenAt(Instant.now());
        return MeDto.of(u);
    }

    @PatchMapping("/me")
    @Transactional
    public MeDto update(@Valid @RequestBody UpdateProfileRequest r) {
        AppUser u = users.findById(Current.userId()).orElseThrow();
        if (r.displayName() != null) u.setDisplayName(r.displayName());
        if (r.avatarUrl() != null) u.setAvatarUrl(r.avatarUrl());
        if (r.theme() != null) u.setTheme(r.theme());
        return MeDto.of(u);
    }

    @GetMapping("/search")
    public List<UserDto> search(@RequestParam String q) {
        if (q.isBlank()) return List.of();
        return users.search(q.strip(), org.springframework.data.domain.PageRequest.of(0, 20)).stream().map(UserDto::of).toList();
    }

    /** "online" / "last seen at" for up to 100 users (chat headers, group member lists). */
    @GetMapping("/presence")
    public Map<UUID, PresenceDto> presence(@RequestParam List<UUID> ids) {
        if (ids.size() > 100) throw new IllegalArgumentException("At most 100 ids");
        return presence.lookup(ids);
    }
}
