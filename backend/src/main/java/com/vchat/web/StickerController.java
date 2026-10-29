package com.vchat.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.vchat.domain.AppUser;
import com.vchat.domain.Sticker;
import com.vchat.domain.StickerPack;
import com.vchat.dto.Dtos.*;
import com.vchat.repo.StickerPackRepo;
import com.vchat.repo.StickerRepo;
import com.vchat.repo.UserRepo;
import com.vchat.service.Current;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

/** Sticker packs. Files are uploaded first via /api/uploads?kind=STICKER, then registered here by URL. */
@Tag(name = "Stickers")
@RestController
@RequestMapping("/api/sticker-packs")
public class StickerController {
    private final StickerPackRepo packs;
    private final StickerRepo stickers;
    private final UserRepo users;

    public StickerController(StickerPackRepo packs, StickerRepo stickers, UserRepo users) {
        this.packs = packs; this.stickers = stickers; this.users = users;
    }

    @Operation(summary = "My sticker packs")
    @GetMapping
    @Transactional(readOnly = true)
    public List<StickerPackDto> mine() {
        return packs.findByOwnerIdOrderByCreatedAt(Current.userId()).stream().map(StickerPackDto::of).toList();
    }

    @Operation(summary = "Create a sticker pack")
    @PostMapping
    @Transactional
    public StickerPackDto create(@Valid @RequestBody StickerPackRequest r) {
        AppUser me = users.findById(Current.userId()).orElseThrow();
        return StickerPackDto.of(packs.save(new StickerPack(me, r.name())));
    }

    @Operation(summary = "Add an uploaded image to a pack")
    @PostMapping("/{packId}/stickers")
    @Transactional
    public StickerDto add(@PathVariable UUID packId, @Valid @RequestBody StickerRequest r) {
        StickerPack p = own(packId);
        Sticker s = stickers.save(new Sticker(p, r.name(), r.url(), r.mimeType()));
        return StickerDto.of(s);
    }

    @Operation(summary = "Delete a pack")
    @DeleteMapping("/{packId}")
    @Transactional
    public void deletePack(@PathVariable UUID packId) { packs.delete(own(packId)); }

    @Operation(summary = "Remove a sticker from a pack")
    @DeleteMapping("/{packId}/stickers/{stickerId}")
    @Transactional
    public void deleteSticker(@PathVariable UUID packId, @PathVariable UUID stickerId) {
        StickerPack p = own(packId);
        p.getStickers().removeIf(s -> s.getId().equals(stickerId));
    }

    private StickerPack own(UUID packId) {
        StickerPack p = packs.findById(packId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!p.getOwner().getId().equals(Current.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return p;
    }
}
