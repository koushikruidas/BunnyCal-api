package io.bunnycal.conferencing.repository;

import io.bunnycal.conferencing.domain.ZoomConferencingConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoomConferencingConnectionRepository extends JpaRepository<ZoomConferencingConnection, UUID> {
    Optional<ZoomConferencingConnection> findByUserId(UUID userId);
}
