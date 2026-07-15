package io.bunnycal.conferencing.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingId;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.domain.ConferencingEventMapping;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the conferencing lifecycle for a booking. Runs BEFORE calendar sync so the
 * calendar payload can carry the resolved conferencing URL (Zoom/custom) and the
 * calendar layer never auto-attaches conferencing on its own.
 *
 * <p>Returns a {@link ConferencingInstruction} the calendar provider consults to
 * decide whether to (a) embed an external URL in description/location,
 * (b) ask the calendar provider to natively create a Meet, or (c) do neither.
 */
@Service
public class ConferencingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ConferencingCoordinator.class);

    private final BookingRepository bookingRepository;
    private final EventTypeRepository eventTypeRepository;
    private final ConferencingProviderRegistry providerRegistry;
    private final ConferencingEventMappingRepository mappingRepository;
    private final EventConferencingResolver conferencingResolver;
    private final TransactionTemplate requiresNew;

    public ConferencingCoordinator(BookingRepository bookingRepository,
                                   EventTypeRepository eventTypeRepository,
                                   ConferencingProviderRegistry providerRegistry,
                                   ConferencingEventMappingRepository mappingRepository,
                                   EventConferencingResolver conferencingResolver,
                                   PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.providerRegistry = providerRegistry;
        this.mappingRepository = mappingRepository;
        this.conferencingResolver = conferencingResolver;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ConferencingInstruction prepareForCreate(UUID bookingId, UUID hostId) {
        Booking booking = loadBooking(bookingId, hostId);
        EventType eventType = loadEventType(booking);
        ConferencingProviderType providerType = resolveProviderType(booking, eventType);
        log.info("conferencing_provider_resolved bookingId={} action=CREATE eventTypeId={} provider={}",
                bookingId, booking.getEventTypeId(), providerType);

        return switch (providerType) {
            case NONE -> {
                log.info("conferencing_prepare_none bookingId={} hostId={}", bookingId, booking.getHostId());
                OpsLoggers.CONFERENCE.info(
                        "conference_create_skipped bookingId={} provider={} hostId={} eventTypeId={} source={} reasonCode={} hasJoinUrl={}",
                        bookingId, ConferencingProviderType.NONE, booking.getHostId(), booking.getEventTypeId(),
                        "conferencing_provider_none", "NO_PROVIDER", false);
                yield ConferencingInstruction.none();
            }
            case CUSTOM_URL -> {
                OpsLoggers.CONFERENCE.info(
                        "conference_create_skipped bookingId={} provider={} hostId={} eventTypeId={} source={} reasonCode={} hasJoinUrl={}",
                        bookingId, ConferencingProviderType.CUSTOM_URL, booking.getHostId(), booking.getEventTypeId(),
                        "custom_conference_url", "CUSTOM_URL", eventType.getCustomConferenceUrl() != null && !eventType.getCustomConferenceUrl().isBlank());
                yield instructionFromCustomUrl(eventType);
            }
            case GOOGLE_MEET, MICROSOFT_TEAMS -> {
                log.info("conferencing_prepare_native_meet bookingId={} hostId={}", bookingId, booking.getHostId());
                OpsLoggers.CONFERENCE.info(
                        "conference_create_skipped bookingId={} provider={} hostId={} eventTypeId={} source={} reasonCode={} hasJoinUrl={}",
                        bookingId, providerType, booking.getHostId(), booking.getEventTypeId(),
                        "native_calendar_provider", "NATIVE_PROVIDER", false);
                yield ConferencingInstruction.requestNativeMeet(providerType);
            }
            case ZOOM -> createExternalMeeting(booking, eventType, providerType);
            case DEFAULT -> throw unresolvedPointer(bookingId);
        };
    }

    @Transactional
    public ConferencingInstruction prepareForUpdate(UUID bookingId, UUID hostId) {
        Booking booking = loadBooking(bookingId, hostId);
        EventType eventType = loadEventType(booking);
        ConferencingProviderType providerType = resolveProviderType(booking, eventType);
        log.info("conferencing_provider_resolved bookingId={} action=UPDATE eventTypeId={} provider={}",
                bookingId, booking.getEventTypeId(), providerType);

        return switch (providerType) {
            case NONE -> ConferencingInstruction.none();
            case CUSTOM_URL -> instructionFromCustomUrl(eventType);
            case GOOGLE_MEET, MICROSOFT_TEAMS -> ConferencingInstruction.requestNativeMeet(providerType);
            case ZOOM -> updateExternalMeeting(booking, eventType, providerType);
            case DEFAULT -> throw unresolvedPointer(bookingId);
        };
    }

    /**
     * {@link ConferencingProviderType#DEFAULT} is a pointer; {@link #resolveProviderType} has already
     * turned it into a real provider (or {@code NONE}). Reaching here means the resolver was bypassed
     * — fail loudly rather than pick an arbitrary branch and hand a guest a booking with no join link.
     */
    private static IllegalStateException unresolvedPointer(UUID bookingId) {
        return new IllegalStateException(
                "unresolved DEFAULT conferencing pointer reached provider dispatch for booking " + bookingId);
    }

    @Transactional
    public void cancelForBooking(UUID bookingId, UUID hostId) {
        Booking booking = loadBookingOrNull(bookingId, hostId);
        if (booking == null) {
            return;
        }
        for (ConferencingProviderType providerType : ConferencingProviderType.values()) {
            if (providerType == ConferencingProviderType.NONE
                    || providerType == ConferencingProviderType.CUSTOM_URL
                    || providerType == ConferencingProviderType.GOOGLE_MEET
                    || providerType == ConferencingProviderType.MICROSOFT_TEAMS) {
                continue;
            }
            mappingRepository.findByBookingIdAndProvider(booking.getId(), providerType)
                    .ifPresent(mapping -> cancelMapping(booking, providerType, mapping));
        }
    }

    private ConferencingInstruction createExternalMeeting(Booking booking,
                                                          EventType eventType,
                                                          ConferencingProviderType providerType) {
        ConferencingInstruction instruction = requiresNew.execute(status -> {
            ConferencingEventMapping mapping = mappingRepository
                    .findByBookingIdAndProvider(booking.getId(), providerType)
                    .orElseGet(() -> newMapping(booking, providerType));

            if ("ACTIVE".equals(mapping.getStatus()) && mapping.getJoinUrl() != null) {
                log.info("conferencing_prepare_existing bookingId={} provider={} meetingId={}",
                        booking.getId(), providerType, mapping.getMeetingId());
                return ConferencingInstruction.urlEmbedded(providerType, mapping.getJoinUrl(),
                        mapping.getHostUrl(), mapping.getMeetingId());
            }

            ConferencingProvider provider = providerRegistry.resolve(providerType);
            String topic = eventType.getName();
            log.info("conferencing_create_meeting_invoking bookingId={} provider={} hostId={} topic={} start={} end={}",
                    booking.getId(), providerType, booking.getHostId(), topic, booking.getStartTime(), booking.getEndTime());
            ConferencingProvider.MeetingDetails details = provider.createMeeting(
                    booking.getId(),
                    booking.getHostId(),
                    topic,
                    booking.getStartTime(),
                    booking.getEndTime());

            persistMapping(mapping, providerType, details, "ACTIVE", null);
            log.info("conferencing_prepare_created bookingId={} provider={} meetingId={} joinUrl={} hostUrlPresent={}",
                    booking.getId(), providerType, details.meetingId(), details.joinUrl(),
                    details.hostUrl() != null && !details.hostUrl().isBlank());
            OpsLoggers.CONFERENCE.info(
                    "conference_create_success bookingId={} provider={} hostId={} eventTypeId={} source={} hasJoinUrl={}",
                    booking.getId(), providerType, booking.getHostId(), booking.getEventTypeId(), "conferencing_provider", true);
            return ConferencingInstruction.urlEmbedded(providerType, details.joinUrl(),
                    details.hostUrl(), details.meetingId());
        });
        return instruction == null ? ConferencingInstruction.none() : instruction;
    }

    private ConferencingInstruction updateExternalMeeting(Booking booking,
                                                          EventType eventType,
                                                          ConferencingProviderType providerType) {
        ConferencingEventMapping mapping = mappingRepository
                .findByBookingIdAndProvider(booking.getId(), providerType)
                .orElse(null);

        if (mapping == null || mapping.getMeetingId() == null || mapping.getMeetingId().isBlank()) {
            return createExternalMeeting(booking, eventType, providerType);
        }

        ConferencingProvider provider = providerRegistry.resolve(providerType);
        ConferencingProvider.MeetingDetails details = provider.updateMeeting(
                booking.getId(),
                booking.getHostId(),
                mapping.getMeetingId(),
                eventType.getName(),
                booking.getStartTime(),
                booking.getEndTime());

        persistMapping(mapping, providerType, details, "ACTIVE", null);
        log.info("conferencing_prepare_updated bookingId={} provider={} meetingId={}",
                booking.getId(), providerType, details.meetingId());
        return ConferencingInstruction.urlEmbedded(providerType, details.joinUrl(),
                details.hostUrl(), details.meetingId());
    }

    private void cancelMapping(Booking booking, ConferencingProviderType providerType, ConferencingEventMapping mapping) {
        if (mapping.getMeetingId() != null && !mapping.getMeetingId().isBlank()) {
            try {
                providerRegistry.resolve(providerType)
                        .cancelMeeting(booking.getId(), booking.getHostId(), mapping.getMeetingId());
            } catch (RuntimeException ex) {
                mapping.setLastError(truncateError(ex.getMessage()));
                mappingRepository.save(mapping);
                OpsLoggers.CONFERENCE.warn(
                        "conference_create_failed bookingId={} provider={} hostId={} eventTypeId={} eventType={} reasonCode={} message={}",
                        booking.getId(), providerType, booking.getHostId(), booking.getEventTypeId(), "BOOKING_CANCELLED",
                        "CONFERENCE_CREATE_FAILED", OpsLogSupport.truncate(ex.getMessage(), 160));
                throw ex;
            }
        }
        mapping.setStatus("CANCELLED");
        mapping.setLastError(null);
        mappingRepository.save(mapping);
        log.info("conferencing_cancelled bookingId={} provider={} meetingId={}",
                booking.getId(), providerType, mapping.getMeetingId());
    }

    private static ConferencingInstruction instructionFromCustomUrl(EventType eventType) {
        String url = eventType.getCustomConferenceUrl();
        if (url == null || url.isBlank()) {
            return ConferencingInstruction.none();
        }
        return ConferencingInstruction.urlEmbedded(
                ConferencingProviderType.CUSTOM_URL, url.trim(), null, null);
    }

    private ConferencingEventMapping newMapping(Booking booking, ConferencingProviderType providerType) {
        ConferencingEventMapping mapping = new ConferencingEventMapping();
        mapping.setBookingId(booking.getId());
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

    private Booking loadBooking(UUID bookingId, UUID hostId) {
        return bookingRepository.findById(new BookingId(bookingId, hostId))
                .orElseThrow(() -> new IllegalStateException("booking not found for conferencing prepare: " + bookingId));
    }

    private Booking loadBookingOrNull(UUID bookingId, UUID hostId) {
        if (hostId != null) {
            return bookingRepository.findById(new BookingId(bookingId, hostId)).orElse(null);
        }
        return bookingRepository.findAnyById(bookingId).orElse(null);
    }

    private EventType loadEventType(Booking booking) {
        return eventTypeRepository.findById(booking.getEventTypeId())
                .orElseThrow(() -> new IllegalStateException("event type not found for conferencing prepare: " + booking.getEventTypeId()));
    }

    /**
     * Resolve the event type's stored choice against the <b>writer</b> — whoever receives the
     * calendar event and therefore mints the link. That is {@code booking.getHostId()}: the owner
     * for 1:1/group/collective, and the <em>assigned member</em> for round-robin.
     *
     * <p>This is the choke point: {@link ConferencingProviderType#DEFAULT} is a pointer and must
     * never reach the switches below, which branch on real providers.
     */
    private ConferencingProviderType resolveProviderType(Booking booking, EventType eventType) {
        return conferencingResolver.resolve(booking.getHostId(), eventType);
    }

    private static String truncateError(String message) {
        if (message == null) return null;
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
