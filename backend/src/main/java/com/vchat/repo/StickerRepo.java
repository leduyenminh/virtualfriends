package com.vchat.repo;

import com.vchat.domain.Sticker;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StickerRepo extends JpaRepository<Sticker, UUID> {}
