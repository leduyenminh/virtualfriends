package com.vchat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import java.time.Instant;

@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "sticker")
public class Sticker extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pack_id") private StickerPack pack;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String url;
    @Column(name = "mime_type", nullable = false) private String mimeType;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected Sticker() {}
    public Sticker(StickerPack pack, String name, String url, String mimeType) {
        this.pack = pack; this.name = name; this.url = url; this.mimeType = mimeType;
    }

    public StickerPack getPack() { return pack; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getMimeType() { return mimeType; }
}
