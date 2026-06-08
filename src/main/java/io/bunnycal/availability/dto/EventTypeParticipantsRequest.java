package io.bunnycal.availability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Replaces the full participant set for an event type (PUT semantics).
 * Order of {@code userIds} defines display/assignment order.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventTypeParticipantsRequest(List<UUID> userIds) {}
