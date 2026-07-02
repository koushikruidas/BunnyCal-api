package io.bunnycal.auth.avatar;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {

    Optional<UserAvatar> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
