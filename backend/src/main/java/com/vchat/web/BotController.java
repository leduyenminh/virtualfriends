package com.vchat.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.vchat.config.VchatProperties;
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
import java.util.Map;
import java.util.UUID;

@Tag(name = "Bots & avatars")
@RestController
@RequestMapping("/api")
public class BotController {
    private static final Map<String, String> MODEL_LABELS = Map.of(
            "claude-sonnet-5-5", "Claude Sonnet 5.5 · balanced",
            "claude-haiku-5-5", "Claude Haiku 5.5 · fastest, lowest cost",
            "claude-opus-5-5", "Claude Opus 5.5 · most capable, higher cost");

    private final BotRepo bots;
    private final AvatarModelRepo avatars;
    private final UserRepo users;
    private final VchatProperties.Ai ai;

    public BotController(BotRepo bots, AvatarModelRepo avatars, UserRepo users, VchatProperties props) {
        this.bots = bots; this.avatars = avatars; this.users = users; this.ai = props.ai();
    }

    // ---- Claude models users may pick per bot (vchat.ai.models) ----

    @Operation(summary = "Claude models I can pick for an AI friend")
    @GetMapping("/bots/models")
    public List<ModelOption> models() {
        return ai.choices().stream()
                .map(id -> new ModelOption(id, MODEL_LABELS.getOrDefault(id, id), id.equals(ai.model())))
                .toList();
    }

    // ---- avatar models (built-in stick figures + user imports) ----

    @Operation(summary = "Built-in and my imported avatar models")
    @GetMapping("/avatars")
    @Transactional(readOnly = true)
    public List<AvatarDto> avatars() {
        return avatars.visibleTo(Current.userId()).stream().map(AvatarDto::of).toList();
    }

    @Operation(summary = "Register an uploaded VRM / glTF / Live2D / Unity model")
    @PostMapping("/avatars")
    @Transactional
    public AvatarDto importAvatar(@Valid @RequestBody AvatarRequest r) {
        if (r.format() == null || r.format() == AvatarModel.Format.PLACEHOLDER) throw new IllegalArgumentException("format required");
        AppUser me = users.findById(Current.userId()).orElseThrow();
        return AvatarDto.of(avatars.save(new AvatarModel(me, r.name(), r.format(), r.url())));
    }

    @Operation(summary = "Delete one of my avatar models")
    @DeleteMapping("/avatars/{id}")
    @Transactional
    public void deleteAvatar(@PathVariable UUID id) {
        AvatarModel a = avatars.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (a.getOwner() == null || !a.getOwner().getId().equals(Current.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        avatars.delete(a);
    }

    // ---- bot characters ----

    @Operation(summary = "My AI friends")
    @GetMapping("/bots")
    @Transactional(readOnly = true)
    public List<BotDto> list() { return bots.findByOwnerId(Current.userId()).stream().map(BotDto::of).toList(); }

    @Operation(summary = "Create an AI friend")
    @PostMapping("/bots")
    @Transactional
    public BotDto create(@Valid @RequestBody BotRequest r) {
        AppUser me = users.findById(Current.userId()).orElseThrow();
        BotCharacter b = new BotCharacter(me, r.name(), r.persona());
        apply(b, r);
        return BotDto.of(bots.save(b));
    }

    @Operation(summary = "Update an AI friend")
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
        if (r.model() == null || r.model().isBlank()) b.setModel(null);
        else if (ai.allows(r.model())) b.setModel(r.model());
        else throw new IllegalArgumentException("That AI model isn't available. Pick one from the list.");
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
