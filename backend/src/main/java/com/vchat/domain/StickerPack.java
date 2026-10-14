package com.vchat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "sticker_pack")
public class StickerPack extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id") private AppUser owner;
    @Column(nullable = false) private String name;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt") private List<Sticker> stickers = new ArrayList<>();

    protected StickerPack() {}
    public StickerPack(AppUser owner, String name) { this.owner = owner; this.name = name; }

    public AppUser getOwner() { return owner; }
    public String getName() { return name; }
    public List<Sticker> getStickers() { return stickers; }
}
