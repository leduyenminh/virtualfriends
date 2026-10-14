package com.vchat.repo;

import com.vchat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepo extends JpaRepository<Message, UUID> {
    /**
     * Keyset ("seek") pagination: WHERE created_at < :before ORDER BY created_at DESC LIMIT n walks the
     * (conversation_id, created_at DESC) index and stays O(n) at any depth, unlike OFFSET paging.
     * Stickers are join-fetched so rendering a page never triggers one query per sticker message.
     */
    @Query("""
        select m from Message m left join fetch m.sticker
        where m.conversation.id = ?1 and m.createdAt < ?2
        order by m.createdAt desc
        """)
    List<Message> page(UUID conversationId, Instant before, Pageable pageable);

    Optional<Message> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    /**
     * Chat list, query 3/4: the newest message of each conversation. The correlated LIMIT 1 subquery does one
     * index probe per conversation, instead of DISTINCT ON over every message in those conversations.
     */
    @Query(value = """
        select m.* from message m
        where m.id in (
          select (select x.id from message x where x.conversation_id = c.id order by x.created_at desc limit 1)
          from conversation c where c.id in (:ids))
        """, nativeQuery = true)
    List<Message> findLatestPerConversation(Collection<UUID> ids);

    /** Chat list, query 4/4: unread counts for all my conversations in a single GROUP BY. */
    @Query(value = """
        select cm.conversation_id, count(m.id)
        from conversation_member cm
        join message m on m.conversation_id = cm.conversation_id
                      and m.created_at > coalesce(cm.last_read_at, cast('-infinity' as timestamptz))
                      and (m.sender_id is null or m.sender_id <> cm.user_id)
        where cm.user_id = :userId
        group by cm.conversation_id
        """, nativeQuery = true)
    List<Object[]> countUnreadByConversation(UUID userId);

    @Query("select count(m) from Message m where m.conversation.id = ?1 and m.createdAt > ?2 and (m.sender is null or m.sender.id <> ?3)")
    long countUnread(UUID conversationId, Instant since, UUID userId);
}
