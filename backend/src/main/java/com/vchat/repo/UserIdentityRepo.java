package com.vchat.repo;

import com.vchat.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepo extends JpaRepository<UserIdentity, UUID> {
    @Query("select i from UserIdentity i join fetch i.user where i.provider = ?1 and i.subject = ?2")
    Optional<UserIdentity> findWithUser(String provider, String subject);
}
