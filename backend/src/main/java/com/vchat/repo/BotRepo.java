package com.vchat.repo;

import com.vchat.domain.BotCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BotRepo extends JpaRepository<BotCharacter, UUID> {
    List<BotCharacter> findByOwnerId(UUID ownerId);
}
