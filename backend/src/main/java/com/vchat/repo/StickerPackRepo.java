package com.vchat.repo;

import com.vchat.domain.StickerPack;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface StickerPackRepo extends JpaRepository<StickerPack, UUID> {
    List<StickerPack> findByOwnerIdOrderByCreatedAt(UUID ownerId);
}
