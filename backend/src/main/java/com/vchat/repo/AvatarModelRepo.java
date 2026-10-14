package com.vchat.repo;

import com.vchat.domain.AvatarModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface AvatarModelRepo extends JpaRepository<AvatarModel, UUID> {
    @Query("select a from AvatarModel a where a.owner is null or a.owner.id = ?1 order by a.createdAt")
    List<AvatarModel> visibleTo(UUID userId);
}
