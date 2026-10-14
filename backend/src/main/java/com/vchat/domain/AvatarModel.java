package com.vchat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import java.time.Instant;

@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "avatar_model")
public class AvatarModel extends BaseEntity {
    public enum Format { PLACEHOLDER, GLTF, VRM, LIVE2D, UNITY }

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id") private AppUser owner;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Format format;
    private String url;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AvatarModel() {}
    public AvatarModel(AppUser owner, String name, Format format, String url) {
        this.owner = owner; this.name = name; this.format = format; this.url = url;
    }

    public AppUser getOwner() { return owner; }
    public String getName() { return name; }
    public Format getFormat() { return format; }
    public String getUrl() { return url; }
}
