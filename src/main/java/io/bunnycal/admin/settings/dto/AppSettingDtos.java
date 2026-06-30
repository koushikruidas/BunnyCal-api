package io.bunnycal.admin.settings.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.bunnycal.admin.settings.SettingCategory;
import java.time.Instant;
import java.util.UUID;

public final class AppSettingDtos {

    private AppSettingDtos() {
    }

    public record AppSettingDto(
            String key,
            JsonNode value,
            SettingCategory category,
            String description,
            boolean secret,
            boolean editable,
            boolean persisted,
            String source,
            UUID updatedBy,
            Instant updatedAt) {
    }

    public record UpdateSettingRequest(JsonNode value, String reason) {
    }
}
