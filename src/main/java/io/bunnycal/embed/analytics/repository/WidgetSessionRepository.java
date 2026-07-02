package io.bunnycal.embed.analytics.repository;

import io.bunnycal.embed.analytics.domain.WidgetSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetSessionRepository extends JpaRepository<WidgetSession, UUID> {}
