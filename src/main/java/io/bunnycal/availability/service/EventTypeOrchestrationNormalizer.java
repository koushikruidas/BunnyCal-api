package io.bunnycal.availability.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalises the conferencing choice on an event type.
 *
 * <p>Calendars are no longer part of this: availability and write-back are properties of the user,
 * set once on the settings page, so an event type carries neither an availability selection nor a
 * projection destination.
 *
 * <p>Conferencing validation went with them. An event type may only hold {@code DEFAULT} — a pointer
 * to the user's global default, which by construction agrees with their write-back calendar because
 * the settings page will not let it diverge — or a provider-independent override
 * ({@code ZOOM}, {@code CUSTOM_URL}, {@code NONE}) that needs no particular calendar at all. There is
 * no combination left to reject: {@code GOOGLE_MEET} and {@code MICROSOFT_TEAMS} cannot be named
 * here, so they cannot be named wrongly.
 */
@Component
public class EventTypeOrchestrationNormalizer {

    public ConferencingConfig normalize(CreateEventTypeRequest request) {
        return normalizeConference(request.conference());
    }

    /**
     * For a partial update: a null {@code conference} means "leave it as it is", so the event type's
     * current choice is round-tripped rather than reset to the default.
     */
    public ConferencingConfig normalizeForDraftMutation(EventType existingEventType,
                                                        CreateEventTypeRequest.ConferenceRequest conference) {
        CreateEventTypeRequest.ConferenceRequest effective = conference;
        if (effective == null && existingEventType != null) {
            ConferencingProviderType current = existingEventType.getConferencingProvider();
            effective = new CreateEventTypeRequest.ConferenceRequest(
                    current != ConferencingProviderType.NONE,
                    current.name(),
                    existingEventType.getCustomConferenceUrl());
        }
        return normalizeConference(effective);
    }

    private ConferencingConfig normalizeConference(CreateEventTypeRequest.ConferenceRequest conference) {
        boolean enabled = conference != null && Boolean.TRUE.equals(conference.enabled());
        String providerRaw = conference != null ? conference.provider() : null;
        String customUrl = trimToNull(conference != null ? conference.customUrl() : null);

        ConferencingProviderType providerType;
        if (!enabled) {
            providerType = ConferencingProviderType.NONE;
            customUrl = null;
        } else if (providerRaw == null || providerRaw.isBlank()) {
            // "A meeting link, but you didn't say which" — the user's own default is the answer.
            providerType = ConferencingProviderType.DEFAULT;
        } else {
            providerType = parseConferencingProvider(providerRaw);
        }

        if (providerType == ConferencingProviderType.CUSTOM_URL) {
            if (customUrl == null || !isValidHttpsUrl(customUrl)) {
                throw new CustomException(ErrorCode.VALIDATION_ERROR,
                        "custom conference URL must be a valid https URL.");
            }
        } else {
            customUrl = null;
        }
        return new ConferencingConfig(enabled, providerType, customUrl);
    }

    private static ConferencingProviderType parseConferencingProvider(String raw) {
        ConferencingProviderType type = ConferencingProviderType.fromExternal(raw)
                .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR, "invalid conference.provider"));

        if (type == ConferencingProviderType.NONE) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "conference.provider cannot be NONE when conference.enabled=true.");
        }
        // Meet and Teams can only be minted on a matching calendar, so pinning one to an event type
        // would break the day its owner moved their write-back calendar to the other provider. They
        // are reachable only through DEFAULT, which re-resolves against whatever calendar the user
        // currently writes to.
        if (type.requiresCalendarProvider()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    type.externalId() + " cannot be chosen per event. It follows your default meeting "
                            + "link, which you set once in calendar settings.");
        }
        return type;
    }

    private static boolean isValidHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ConferencingConfig(boolean enabled,
                                     ConferencingProviderType providerType,
                                     String customUrl) {
    }
}
