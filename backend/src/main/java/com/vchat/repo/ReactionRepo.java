package com.vchat.repo;

import com.vchat.domain.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReactionRepo extends JpaRepository<MessageReaction, UUID> {
    List<MessageReaction> findByMessageIdIn(Collection<UUID> messageIds);
    Optional<MessageReaction> findByMessageIdAndUserIdAndEmoji(UUID messageId, UUID userId, String emoji);
}
