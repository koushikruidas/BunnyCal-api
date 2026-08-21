package io.bunnycal.conferencing.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.domain.ConferencingEventMapping;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the conferencing lifecycle for a <em>group session</em>, the way
 * {@link ConferencingCoordinator} owns it for a 1:1 booking.
 *
 * <p>Group sessions are not {@code Booking}s, so they cannot go through
 * {@link ConferencingCoordinator} — it loads a {@code Booking} by {@code BookingId} in order to read
 * the host, event type and time window, none of which a session carries in that shape. This class is
 * the session-shaped counterpart: same provider registry, same {@code conferencing_event_mappings}
 * bookkeeping, same {@link ConferencingInstruction} contract handed to the calendar layer.
 *
 * <p><b>Why the mapping table needs no change:</b> {@code conferencing_event_mappings.booking_id}
 * carries no foreign key (see {@code V50_0__conferencing_zoom_foundation.sql}) — it is a correlation
 * id, not a reference. A session id lives there unambiguously because session ids and booking ids are
 * both UUIDs drawn from disjoint sets. {@link ConferencingProvider} likewise takes the id only to
 * correlate log lines; {@link ZoomConferencingProvider} never dereferences it.
 *
 * <p>Historically the session path resolved the provider itself and silently mapped anything that was
 * not Meet/Teams/custom-URL onto "no conferencing". A Zoom group event therefore produced a calendar
 * event with no join link, and the confirmation email was then correctly withheld by the outbox
 * readiness guard — leaving the booking stuck with no signal pointing at Zoom. Every branch here is
 * explicit and logged for that reason.
 */
@Service
public class SessionConferencingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SessionConferencingCoordinator.class);

    private final ConferencingProviderRegistry providerRegistry;
    private final ConferencingEventMappingRepository mappingRepository;
    private final EventConferencingResolver conferencingResolver;
    private final TransactionTemplate requiresNew;

    public SessionConferencingCoordinator(ConferencingProviderRegistry providerRegistry,
                                          ConferencingEventMappingRepository mappingRepository,
                                          EventConferencingResolver conferencingResolver,
                                          PlatformTransactionManager transactionManager) {
        this.providerRegistry = providerRegistry;
        this.mappingRepository = mappingRepository;
        this.conferencingResolver = conferencingResolver;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Resolve conferencing for a session that is being created or updated on the host's calendar.
     *
     * @param sessionId the group session; used as the correlation key in {@code conferencing_event_mappings}
     * @param hostId    the writer — whoever receives the calendar event and therefore mints the link
     */
    public ConferencingInstruction prepare(UUID sessionId,
                                           UUID hostId,
                                           EventType eventType,
                                           Instant start,
                                           Instant end) {
        ConferencingProviderType providerType = conferencingResolver.resolve(hostId, eventType);
        UUID eventTypeId = eventType == null ? null : eventType.getId();
        log.info("session_conferencing_provider_resolved sessionId={} eventTypeId={} provider={}",
                sessionId, eventTypeId, providerType);

        // Exhaustive on purpose: no `default` branch. Adding a provider to
        // ConferencingProviderType must not compile until this switch says what a session should do
        // with it — that omission is exactly how ZOOM previously degraded to "no link" in silence.
        return switch (providerType) {
            case NONE -> {
                log.info("session_conferencing_prepare_none sessionId={} hostId={}", sessionId, hostId);
                yield ConferencingInstruction.none();
            }
            case CUSTOM_URL -> instructionFromCustomUrl(sessionId, eventType);
            case GOOGLE_MEET, MICROSOFT_TEAMS -> {
                log.info("session_conferencing_prepare_native_meet sessionId={} hostId={} provider={}",
                        sessionId, hostId, providerType);
                yield ConferencingInstruction.requestNativeMeet(providerType);
            }
            case ZOOM -> externalMeeting(sessionId, hostId, eventType, providerType, start, end);
            case DEFAULT -> throw new IllegalStateException(
                    "unresolved DEFAULT conferencing pointer reached provider dispatch for session " + sessionId);
        };
    }

    /**
     * Release a session's external meeting. Mirrors {@link ConferencingCoordinator#cancelForBooking}:
     * only the providers that hold state on a third party need releasing — Meet/Teams die with the
     * calendar event and a custom URL was never ours to cancel.
     */
    public void cancelForSession(UUID sessionId, UUID hostId) {
        for (ConferencingProviderType providerType : ConferencingProviderType.values()) {
            if (providerType != ConferencingProviderType.ZOOM) {
                continue;
            }
            mappingRepository.findByBookingIdAndProvider(sessionId, providerType)
                    .ifPresent(mapping -> cancelMapping(sessionId, hostId, providerType, mapping));
        }
    }

    private ConferencingInstruction externalMeeting(UUID sessionId,
                                                    UUID hostId,
                                                    EventType eventType,
                                                    ConferencingProviderType providerType,
                                                    Instant start,
                                                    Instant end) {
        String topic = eventType == null || eventType.getName() == null || eventType.getName().isBlank()
                ? "Group Session"
                : eventType.getName();
        UUID eventTypeId = eventType == null ? null : eventType.getId();

        // REQUIRES_NEW so the meeting we just created at Zoom is committed even if the surrounding
        // calendar-sync transaction later rolls back and retries; otherwise every retry would mint
        // another orphaned Zoom meeting.
        ConferencingInstruction instruction = requiresNew.execute(status -> {
            ConferencingEventMapping mapping = mappingRepository
                    .findByBookingIdAndProvider(sessionId, providerType)
                    .orElseGet(() -> newMapping(sessionId, providerType));

            boolean reusable = "ACTIVE".equals(mapping.getStatus())
                    && mapping.getJoinUrl() != null
                    && !mapping.getJoinUrl().isBlank();
            boolean hasMeeting = mapping.getMeetingId() != null && !mapping.getMeetingId().isBlank();

            try {
                ConferencingProvider provider = providerRegistry.resolve(providerType);
                ConferencingProvider.MeetingDetails details;

                if (reusable && hasMeeting) {
                    // The session moved or was edited: keep the join URL guests already hold and
                    // push the new time to Zoom instead of minting a second meeting.
                    details = provider.updateMeeting(sessionId, hostId, mapping.getMeetingId(), topic, start, end);
                    persistMapping(mapping, providerType, details, "ACTIVE", null);
                    log.info("session_conferencing_prepare_updated sessionId={} provider={} meetingId={}",
                            sessionId, providerType, details.meetingId());
                } else {
                    details = provider.createMeeting(sessionId, hostId, topic, start, end);
                    persistMapping(mapping, providerType, details, "ACTIVE", null);
                    log.info("session_conferencing_prepare_created sessionId={} provider={} meetingId={} hostUrlPresent={}",
                            sessionId, providerType, details.meetingId(),
                            details.hostUrl() != null && !details.hostUrl().isBlank());
                    OpsLoggers.CONFERENCE.info(
                            "conference_create_success bookingId={} provider={} hostId={} eventTypeId={} source={} hasJoinUrl={}",
                            sessionId, providerType, hostId, eventTypeId, "session_conferencing_provider",
                            details.joinUrl() != null && !details.joinUrl().isBlank());
                }
                return ConferencingInstruction.urlEmbedded(providerType, details.joinUrl(),
                        details.hostUrl(), details.meetingId());
            } catch (RuntimeException ex) {
                // Record why, then rethrow. The sync job retries with backoff and the outbox readiness
                // guard keeps the confirmation email withheld meanwhile — a guest must never be told a
                // meeting exists before its join link does.
                mapping.setStatus(hasMeeting ? mapping.getStatus() : "FAILED");
                mapping.setLastError(truncateError(ex.getMessage()));
                mappingRepository.save(mapping);
                log.warn("session_conferencing_prepare_failed sessionId={} provider={} hostId={} message={}",
                        sessionId, providerType, hostId, OpsLogSupport.truncate(ex.getMessage(), 160));
                OpsLoggers.CONFERENCE.warn(
                        "conference_create_failed bookingId={} provider={} hostId={} eventTypeId={} eventType={} reasonCode={} message={}",
                        sessionId, providerType, hostId, eventTypeId, "GROUP_SESSION",
                        "CONFERENCE_CREATE_FAILED", OpsLogSupport.truncate(ex.getMessage(), 160));
                throw ex;
            }
        });
        return instruction == null ? ConferencingInstruction.none() : instruction;
    }

    private void cancelMapping(UUID sessionId,
                               UUID hostId,
                               ConferencingProviderType providerType,
                               ConferencingEventMapping mapping) {
        if (mapping.getMeetingId() != null && !mapping.getMeetingId().isBlank()) {
            try {
                providerRegistry.resolve(providerType).cancelMeeting(sessionId, hostId, mapping.getMeetingId());
            } catch (RuntimeException ex) {
                mapping.setLastError(truncateError(ex.getMessage()));
                mappingRepository.save(mapping);
                log.warn("session_conferencing_cancel_failed sessionId={} provider={} meetingId={} message={}",
                        sessionId, providerType, mapping.getMeetingId(),
                        OpsLogSupport.truncate(ex.getMessage(), 160));
                throw ex;
            }
        }
        mapping.setStatus("CANCELLED");
        mapping.setLastError(null);
        mappingRepository.save(mapping);
        log.info("session_conferencing_cancelled sessionId={} provider={} meetingId={}",
                sessionId, providerType, mapping.getMeetingId());
    }

    private ConferencingInstruction instructionFromCustomUrl(UUID sessionId, EventType eventType) {
        String url = eventType == null ? null : eventType.getCustomConferenceUrl();
        if (url == null || url.isBlank()) {
            // Configured for a custom link but none stored. Say so: this is the state that used to
            // vanish, and it produces a session with no way to join.
            log.warn("session_conferencing_custom_url_missing sessionId={} eventTypeId={}",
                    sessionId, eventType == null ? null : eventType.getId());
            return ConferencingInstruction.none();
        }
        return ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.CUSTOM_URL, url.trim(), null, null);
    }

    private ConferencingEventMapping newMapping(UUID sessionId, ConferencingProviderType providerType) {
        ConferencingEventMapping mapping = new ConferencingEventMapping();
        mapping.setBookingId(sessionId);
        mapping.setProvider(providerType);
        mapping.setStatus("PENDING");
        return mapping;
    }

    private void persistMapping(ConferencingEventMapping mapping,
                                ConferencingProviderType providerType,
                                ConferencingProvider.MeetingDetails details,
                                String status,
                                String lastError) {
        mapping.setProvider(providerType);
        if (details.meetingId() != null) {
            mapping.setMeetingId(details.meetingId());
        }
        if (details.joinUrl() != null) {
            mapping.setJoinUrl(details.joinUrl());
        }
        if (details.hostUrl() != null) {
            mapping.setHostUrl(details.hostUrl());
        }
        mapping.setStatus(status);
        mapping.setLastError(lastError);
        mappingRepository.save(mapping);
    }

    private static String truncateError(String message) {
        if (message == null) return null;
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
