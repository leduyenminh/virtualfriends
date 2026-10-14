package com.vchat.repo;

import com.vchat.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepo extends JpaRepository<RefreshToken, UUID> {
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = ?1")
    Optional<RefreshToken> findByHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = ?2 where t.familyId = ?1 and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant at);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = ?2 where t.user.id = ?1 and t.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant at);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < ?1")
    int deleteExpiredBefore(Instant cutoff);
}
