package com.vchat.repo;

import com.vchat.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepo extends JpaRepository<Conversation, UUID> {
    @Query("""
        select c from Conversation c where c.type = com.vchat.domain.Conversation.Type.DIRECT
          and exists (select 1 from ConversationMember a where a.conversation = c and a.user.id = ?1)
          and exists (select 1 from ConversationMember b where b.conversation = c and b.user.id = ?2)
        """)
    Optional<Conversation> findDirect(UUID a, UUID b);
}
