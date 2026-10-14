package com.vchat.repo;

import com.vchat.domain.AppUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepo extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);

    /** Served by the pg_trgm GIN indexes on lower(username) / lower(display_name) (see V2 migration). */
    @Query("select u from AppUser u where lower(u.username) like lower(concat('%', ?1, '%')) or lower(u.displayName) like lower(concat('%', ?1, '%')) order by u.username")
    List<AppUser> search(String q, Pageable page);

    /** Single UPDATE, no SELECT + dirty-check round trip. */
    @Modifying
    @Transactional
    @Query("update AppUser u set u.lastSeenAt = ?2 where u.id = ?1")
    int touchLastSeen(UUID id, Instant at);
}
