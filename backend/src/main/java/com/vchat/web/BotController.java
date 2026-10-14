package com.vchat.web;

import com.vchat.domain.AppUser;
import com.vchat.domain.AvatarModel;
import com.vchat.domain.BotCharacter;
import com.vchat.dto.Dtos.*;
import com.vchat.repo.AvatarModelRepo;
import com.vchat.repo.BotRepo;
import com.vchat.repo.UserRepo;
import com.vchat.service.Current;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BotController {
    private final BotRepo bots;
    private final AvatarModelRepo avatars;
    private final UserRepo users;

    public BotController(BotRepo bots, AvatarModelRepo avatars, UserRepo users) {
        this.bots = bots; this.avatars = avatars; this.users = users;
    }

    // ---- avatar models (built-in stick figures + user imports) ----

    @GetMapping("/avatars")
    @Transactional(readOnly = true)
    public List<AvatarDto> avatars() {
        return avatars.visibleTo(Current.userId()).stream().map(AvatarDto::of).toList();
    }

    @PostMapping("/avatars")
    @Transactional
    public AvatarDto importAvatar(@Valid @RequestBody AvatarRequest r) {
        if (r.format() == null || r.format() == AvatarModel.Format.PLACEHOLDER) throw new IllegalArgumentException("format required");
        AppUser me = users.findById(Current.userId()).orElseThrow();
        return AvatarDto.of(avatars.save(new AvatarModel(me, r.name(), r.format(), r.url())));
    }

    @DeleteMapping("/avatars/{id}")
    @Transactional
    public void deleteAvatar(@PathVariable UUID id) {
        AvatarModel a = avatars.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (a.getOwner() == null || !a.getOwner().getId().equals(Current.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        avatars.delete(a);
    }

    // ---- bot characters ----

    @GetMapping("/bots")
    @Transactional(readOnly = true)
    public List<BotDto> list() { return bots.findByOwnerId(Current.userId()).stream().map(BotDto::of).toList(); }

    @PostMapping("/bots")
    @Transactional
    public BotDto create(@Valid @RequestBody BotRequest r) {
        AppUser me = users.findById(Current.userId()).orElseThrow();
        BotCharacter b = new BotCharacter(me, r.name(), r.persona());
        apply(b, r);
        return BotDto.of(bots.save(b));
    }

    @PutMapping("/bots/{id}")
    @Transactional
    public BotDto update(@PathVariable UUID id, @Valid @RequestBody BotRequest r) {
        BotCharacter b = own(id);
        b.setName(r.name());
        b.setPersona(r.persona());
        apply(b, r);
        return BotDto.of(b);
    }

    private void apply(BotCharacter b, BotRequest r) {
        if (r.language() != null) b.setLanguage(r.language());
        if (r.ttsProvider() != null) b.setTtsProvider(r.ttsProvider());
        b.setVoiceId(r.voiceId());
        if (r.avatarModelId() != null) {
            AvatarModel a = avatars.findById(r.avatarModelId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (a.getOwner() != null && !a.getOwner().getId().equals(Current.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            b.setAvatarModel(a);
        }
    }

    private BotCharacter own(UUID id) {
        BotCharacter b = bots.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (b.getOwner() == null || !b.getOwner().getId().equals(Current.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return b;
    }
}
