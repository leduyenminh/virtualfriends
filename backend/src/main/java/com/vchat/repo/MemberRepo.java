package com.vchat.repo;

import com.vchat.domain.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberRepo extends JpaRepository<ConversationMember, UUID> {
    List<ConversationMember> findByConversationId(UUID conversationId);
    Optional<ConversationMember> findByConversationIdAndUserId(UUID conversationId, UUID userId);
    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

    /** Chat list, query 1/4: my memberships with conversation + bot + avatar in one round trip. */
    @Query("""
        select m from ConversationMember m
          join fetch m.conversation c
          left join fetch c.bot b
          left join fetch b.avatarModel
        where m.user.id = ?1
        order by m.pinned desc, c.updatedAt desc
        """)
    List<ConversationMember> findMineWithConversation(UUID userId);

    /** Chat list, query 2/4: every member (with user) of those conversations, one query instead of N. */
    @Query("select m from ConversationMember m join fetch m.user where m.conversation.id in ?1")
    List<ConversationMember> findAllWithUser(Collection<UUID> conversationIds);

    /** Recipients for realtime fan-out; selects ids only, no entity hydration. */
    @Query("select m.user.id from ConversationMember m where m.conversation.id = ?1")
    List<UUID> findUserIds(UUID conversationId);
}
