package io.bunnycal.embed.analytics.repository;

import io.bunnycal.embed.analytics.domain.WidgetEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetEventRepository extends JpaRepository<WidgetEvent, UUID> {}
