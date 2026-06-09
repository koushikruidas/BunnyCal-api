package io.bunnycal.booking.notification;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.BookingActionType;
import io.bunnycal.booking.service.GuestCapabilityTokenService;
import io.bunnycal.booking.service.TokenCreatorType;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.MicrosoftAccountClassifier;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import io.bunnycal.conferencing.service.ConferencingCoordinator;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.conferencing.service.ConferenceDetails;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class BookingNotificationService {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationService.class);

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "BOOKING_CONFIRMED",
            "BOOKING_CONFIRMED_READY",
            "BOOKING_UPDATED",
            "BOOKING_CANCELLED",
            "BOOKING_EXTERNAL_TERMINATED"
    );

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final BookingManageLinkService bookingManageLinkService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final NotificationRecipientResolver recipientResolver;
    private final EmailDeliverabilityPolicy deliverabilityPolicy;
    private final NotificationSendDedupService notificationSendDedupService;
    private final ConferencingCoordinator conferencingCoordinator;
    private final ConferencingEventMappingRepository conferencingEventMappingRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final boolean notificationsEnabled;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;
    private final String debugEmlDir;
    private final Duration guestManageTokenTtl;

    public BookingNotificationService(BookingRepository bookingRepository,
                                      UserRepository userRepository,
                                      EventTypeRepository eventTypeRepository,
                                      JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      BookingManageLinkService bookingManageLinkService,
                                      GuestCapabilityTokenService guestCapabilityTokenService,
                                      NotificationRecipientResolver recipientResolver,
                                      EmailDeliverabilityPolicy deliverabilityPolicy,
                                      NotificationSendDedupService notificationSendDedupService,
                                      ConferencingCoordinator conferencingCoordinator,
                                      ConferencingEventMappingRepository conferencingEventMappingRepository,
                                      CalendarConnectionRepository calendarConnectionRepository,
                                      @Value("${booking.notifications.enabled:false}") boolean notificationsEnabled,
                                      @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
                                      @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@BunnyCal.local}}")
                                      String calendarOrganizerEmail,
                                      @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}")
                                      String calendarOrganizerName,
                                      @Value("${booking.notifications.debug-eml-dir:}") String debugEmlDir,
                                      @Value("${booking.public.capability-token-ttl-days:14}") long capabilityTokenTtlDays) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.bookingManageLinkService = bookingManageLinkService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.recipientResolver = recipientResolver;
        this.deliverabilityPolicy = deliverabilityPolicy;
        this.notificationSendDedupService = notificationSendDedupService;
        this.conferencingCoordinator = conferencingCoordinator;
        this.conferencingEventMappingRepository = conferencingEventMappingRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.notificationsEnabled = notificationsEnabled;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
        this.debugEmlDir = debugEmlDir == null ? "" : debugEmlDir.trim();
        this.guestManageTokenTtl = Duration.ofDays(Math.max(1L, capabilityTokenTtlDays));
    }

    public void handleOutboxEvent(OutboxEvent event) {
        if (!notificationsEnabled || event == null) {
            return;
        }
        if (!"Booking".equals(event.getAggregateType()) || event.getAggregateId() == null) {
            return;
        }
        if (!SUPPORTED_EVENTS.contains(event.getEventType())) {
            return;
        }

        Optional<Booking> maybeBooking = event.getPartitionKey() == null
                ? bookingRepository.findAnyById(event.getAggregateId())
                : bookingRepository.findAnyByIdAndHostId(event.getAggregateId(), event.getPartitionKey());
        if (maybeBooking.isEmpty()) {
            log.warn("booking_notification_skip_missing_booking bookingId={} eventType={}",
                    event.getAggregateId(), event.getEventType());
            return;
        }
        Booking booking = maybeBooking.get();
        Optional<User> maybeHost = userRepository.findById(booking.getHostId());
        if (maybeHost.isEmpty()) {
            log.warn("booking_notification_skip_missing_host bookingId={} hostId={} eventType={}",
                    booking.getId(), booking.getHostId(), event.getEventType());
            return;
        }
        User host = maybeHost.get();
        EventType eventType = eventTypeRepository.findById(booking.getEventTypeId()).orElse(null);

        String summary = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = "Booking " + booking.getId();
        Optional<String> attendee = recipientResolver.resolveAttendeeRecipient(booking);
        if (attendee.isEmpty()) {
            log.warn("booking_notification_skip_missing_attendee bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
        }
        Optional<String> hostRecipient = recipientResolver.resolveHostRecipient(host);
        if (hostRecipient.isEmpty()) {
            String normalizedHost = deliverabilityPolicy.normalize(host.getEmail());
            String reason = normalizedHost == null ? "MISSING_OR_INVALID" :
                    (deliverabilityPolicy.isSynthetic(normalizedHost) ? "SYNTHETIC_RECIPIENT_SKIPPED" : "UNDELIVERABLE");
            log.info("booking_notification_host_recipient_skipped bookingId={} hostId={} eventType={} reason={} hostEmail={}",
                    booking.getId(), booking.getHostId(), event.getEventType(), reason, normalizedHost);
        } else if (shouldSuppressHostIcsForOwnMsaProjection(eventType, booking.getHostId())) {
            // Consumer MSA host whose Outlook calendar IS the projection target:
            // Outlook would auto-import this iTIP REQUEST and create a second visible
            // event next to the Graph-projected one. Drop the host from this email's
            // recipient set — the calendar entry already exists in their calendar via
            // the Graph projection. Guest still gets the email.
            // For RR: booking.hostId is the assigned participant, so the check must
            // resolve that participant's connection, not the event type owner's.
            log.info("booking_notification_host_recipient_suppressed bookingId={} hostId={} eventType={} reason=ms_consumer_msa_projection_self_owned hostEmail={}",
                    booking.getId(), booking.getHostId(), event.getEventType(), hostRecipient.get());
            hostRecipient = Optional.empty();
        } else if (shouldSuppressHostIcsForOwnGoogleProjection(eventType, booking.getHostId())) {
            // Google host whose Gmail/Calendar account IS the projection target:
            // Gmail auto-imports the iTIP REQUEST and creates a second event next
            // to the one already written via the Calendar API. Drop the host from
            // the recipient set — guest still gets the email.
            // For RR: booking.hostId is the assigned participant, so checking
            // conn.getUserId().equals(hostId) correctly scopes to the participant.
            log.info("booking_notification_host_recipient_suppressed bookingId={} hostId={} eventType={} reason=google_projection_self_owned hostEmail={}",
                    booking.getId(), booking.getHostId(), event.getEventType(), hostRecipient.get());
            hostRecipient = Optional.empty();
        }

        List<String> candidateRecipients = new ArrayList<>(2);
        hostRecipient.ifPresent(candidateRecipients::add);
        attendee.ifPresent(candidateRecipients::add);
        List<String> recipients = recipientResolver.deduplicate(candidateRecipients);
        if (recipients.isEmpty()) {
            log.info("booking_notification_all_recipients_skipped bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
            return;
        }
        log.info("booking_notification_recipients_resolved bookingId={} eventId={} eventType={} hostPresent={} attendeePresent={} dedupedRecipients={}",
                booking.getId(),
                event.getId(),
                event.getEventType(),
                hostRecipient.isPresent(),
                attendee.isPresent(),
                recipients.size());

        String organizer = calendarOrganizerEmail;
        String organizerName = calendarOrganizerName;
        String attendeeName = booking.getGuestName();
        String attendeeEmail = attendee.orElse(null);
        String hostEmail = hostRecipient.orElse(null);
        String hostName = host.getName();
        String hostUsername = host.getUsername();
        String eventTypeSlug = eventType != null ? eventType.getSlug() : null;
        int sequence = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, booking.getCalendarSequence()));
        String eventMethod = "BOOKING_CANCELLED".equals(event.getEventType()) ? "CANCEL" : "REQUEST";
        boolean includeManageLink = "BOOKING_CONFIRMED".equals(event.getEventType())
                || "BOOKING_CONFIRMED_READY".equals(event.getEventType())
                || "BOOKING_UPDATED".equals(event.getEventType());
        boolean canBuildManageLink = includeManageLink
                && hostUsername != null && !hostUsername.isBlank()
                && eventTypeSlug != null && !eventTypeSlug.isBlank();
        ConferenceDetails conferenceDetails = resolveConferenceDetails(booking, eventType, event.getEventType());
        String standaloneIcs = "CANCEL".equals(eventMethod)
                ? icsInviteGenerator.buildStandaloneCancel(
                booking.getId(),
                summary,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                organizerName,
                organizer,
                hostName,
                hostEmail,
                attendeeName,
                attendeeEmail,
                sequence,
                conferenceDetails)
                : icsInviteGenerator.buildStandaloneRequest(
                booking.getId(),
                summary,
                description,
                booking.getStartTime(),
                booking.getEndTime(),
                organizerName,
                organizer,
                hostName,
                hostEmail,
                attendeeName,
                attendeeEmail,
                sequence,
                conferenceDetails);
        log.info("booking_notification_ics_built bookingId={} eventId={} eventType={} hasAttachment={} method={} attendeesInIcs={} hasConferenceUrl={}",
                booking.getId(),
                event.getId(),
                event.getEventType(),
                true,
                eventMethod,
                countIcsAttendees(standaloneIcs),
                conferenceDetails != null && conferenceDetails.joinUrl() != null && !conferenceDetails.joinUrl().isBlank());
        for (String recipient : recipients) {
            String role = resolveRecipientRole(recipient, hostRecipient, attendee);
            if (event.getId() == null) {
                log.warn("booking_notification_send_skipped_missing_event_id bookingId={} recipient={} role={} eventType={}",
                        booking.getId(), recipient, role, event.getEventType());
                continue;
            }
            boolean claimed = notificationSendDedupService.claim(event.getId(), recipient, event.getEventType());
            if (!claimed) {
                log.info("booking_notification_send_skipped_duplicate eventId={} bookingId={} recipient={} eventType={}",
                        event.getId(), booking.getId(), recipient, event.getEventType());
                continue;
            }
            String manageLink = null;
            if (canBuildManageLink) {
                String manageToken = guestCapabilityTokenService.issueToken(
                        booking.getId(),
                        booking.getHostId(),
                        BookingActionType.MANAGE_BOOKING,
                        guestManageTokenTtl,
                        TokenCreatorType.SYSTEM
                );
                manageLink = bookingManageLinkService.build(
                        booking.getId(),
                        manageToken,
                        hostUsername,
                        eventTypeSlug
                );
            }
            try {
                String clientType = inferClientType(recipient);
                if (calendarOrganizerEmail == null || calendarOrganizerEmail.isBlank()) {
                    log.warn("organizer_authority_mismatch_detected bookingId={} provider={} externalEventId={} organizerIdentity={} clientType={}",
                            booking.getId(),
                            eventType == null ? "unknown" : eventType.getProjectionProvider(),
                            "",
                            "",
                            clientType);
                } else {
                    log.info("organizer_authority_verified bookingId={} provider={} externalEventId={} organizerIdentity={} clientType={}",
                            booking.getId(),
                            eventType == null ? "unknown" : eventType.getProjectionProvider(),
                            "",
                            calendarOrganizerEmail,
                            clientType);
                }
                sendMail(recipient, summary, event.getEventType(), standaloneIcs, eventMethod, manageLink, conferenceDetails,
                        booking.getId());
                log.info("booking_notification_send_success eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), true);
                log.info("lifecycle_client_reconciliation_verified bookingId={} provider={} externalEventId={} organizerIdentity={} clientType={} lifecycleOperation={}",
                        booking.getId(),
                        eventType == null ? "unknown" : eventType.getProjectionProvider(),
                        "",
                        calendarOrganizerEmail,
                        clientType,
                        event.getEventType());
            } catch (Exception ex) {
                notificationSendDedupService.release(event.getId(), recipient, event.getEventType());
                log.warn("booking_notification_send_failed_retryable eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={} message={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), true, ex.getMessage());
                throw new IllegalStateException("notification delivery failed for recipient " + recipient, ex);
            }
        }
    }

    private void sendMail(String to,
                          String summary,
                          String eventType,
                          String ics,
                          String method,
                          String manageLink,
                          ConferenceDetails conferenceDetails,
                          UUID bookingId) throws Exception {
        var message = mailSender.createMimeMessage();
        // We must construct the MIME tree manually because the auto-rendering
        // contract that Outlook and Gmail honour requires a specific shape:
        // top-level multipart/mixed wrapping (a) a multipart/alternative
        // containing text/plain + inline text/calendar; method=REQUEST and
        // (b) an invite.ics attachment as a fallback for clients that only
        // recognise the attachment form. MimeMessageHelper.addAttachment
        // alone collapses the calendar part to attachment-only, which is
        // why Outlook previously demanded a manual "Add to calendar" flow.
        var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        // Align the SMTP From with the iTIP ORGANIZER identity used inside the ICS.
        // Outlook treats a REQUEST whose From differs from the ORGANIZER mailbox as a
        // forwarded/foreign invite and frequently suppresses the meeting banner. Using a
        // single canonical organizer identity (with Reply-To to the same mailbox) keeps the
        // message self-consistent. Falls back to the configured from when no organizer is set.
        String envelopeFrom = (calendarOrganizerEmail != null && !calendarOrganizerEmail.isBlank())
                ? calendarOrganizerEmail
                : fromAddress;
        if (calendarOrganizerName != null && !calendarOrganizerName.isBlank()) {
            helper.setFrom(envelopeFrom, calendarOrganizerName);
        } else {
            helper.setFrom(envelopeFrom);
        }
        helper.setReplyTo(envelopeFrom);
        helper.setTo(to);
        helper.setSubject(subject(summary, eventType));
        if (method != null) {
            message.setHeader("X-MS-OLK-FORCEINSPECTOROPEN", "TRUE");
        }

        String textBody = body(summary, eventType, manageLink, conferenceDetails);

        if (ics != null && method != null) {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(textBody, StandardCharsets.UTF_8.name(), "plain");

            MimeBodyPart calendarInline = new MimeBodyPart();
            calendarInline.setContent(ics, "text/calendar; charset=UTF-8; method=" + method);
            // Outlook auto-render requires the calendar part to appear as an
            // alternative body, not as a file attachment.
            calendarInline.setHeader("Content-Type", "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            calendarInline.setHeader("Content-Transfer-Encoding", "8bit");
            calendarInline.setHeader("Content-Class", "urn:content-classes:calendarmessage");

            MimeMultipart alternative = new MimeMultipart("alternative");
            alternative.addBodyPart(textPart);
            alternative.addBodyPart(calendarInline);

            MimeBodyPart alternativeWrapper = new MimeBodyPart();
            alternativeWrapper.setContent(alternative);

            MimeBodyPart icsAttachment = new MimeBodyPart();
            icsAttachment.setContent(ics, "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            icsAttachment.setFileName("invite.ics");
            icsAttachment.setHeader("Content-Type", "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
            icsAttachment.setHeader("Content-Disposition", "attachment; filename=\"invite.ics\"");
            icsAttachment.setHeader("Content-Transfer-Encoding", "8bit");

            MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(alternativeWrapper);
            mixed.addBodyPart(icsAttachment);

            message.setContent(mixed);
        } else {
            helper.setText(textBody, false);
        }

        // saveChanges() finalises the MIME tree (assigns boundaries, propagates
        // the multipart Content-Type onto the message and any wrapping body
        // parts). Without this, downstream consumers — including SMTP serialisers
        // and our own MIME parsers in tests — see an unset Content-Type on
        // wrapper parts and fail to descend into nested multipart/alternative.
        message.saveChanges();

        maybeDumpEml(message, bookingId, eventType, to, conferenceDetails);

        mailSender.send(message);
    }

    /**
     * Debug-only: persist the fully rendered MIME message to disk before SES send so the
     * raw .eml can be diffed across providers/clients. Off unless
     * {@code booking.notifications.debug-eml-dir} is set. Best-effort — never blocks send.
     */
    private void maybeDumpEml(jakarta.mail.internet.MimeMessage message,
                              UUID bookingId,
                              String eventType,
                              String recipient,
                              ConferenceDetails conferenceDetails) {
        if (debugEmlDir == null || debugEmlDir.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(debugEmlDir);
            java.nio.file.Files.createDirectories(dir);
            String provider = conferenceDetails == null || conferenceDetails.provider() == null
                    ? "none"
                    : conferenceDetails.provider().toLowerCase(Locale.ROOT);
            String safeRecipient = recipient == null ? "unknown" : recipient.replaceAll("[^a-zA-Z0-9._-]", "_");
            String fileName = String.format(Locale.ROOT, "%s_%s_%s_%s_%d.eml",
                    provider,
                    eventType == null ? "EVENT" : eventType,
                    bookingId == null ? "nobooking" : bookingId,
                    safeRecipient,
                    System.currentTimeMillis());
            java.nio.file.Path target = dir.resolve(fileName);
            try (var out = java.nio.file.Files.newOutputStream(target)) {
                message.writeTo(out);
            }
            log.info("booking_notification_eml_dumped bookingId={} eventType={} provider={} recipient={} path={}",
                    bookingId, eventType, provider, recipient, target.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("booking_notification_eml_dump_failed bookingId={} eventType={} message={}",
                    bookingId, eventType, ex.getMessage());
        }
    }

    private ConferenceDetails resolveConferenceDetails(Booking booking, EventType eventType, String outboxEventType) {
        if (eventType == null) {
            return ConferenceDetails.none("event_type_missing", java.time.Instant.now());
        }
        ConferencingProviderType providerType = eventType.getConferencingProvider();
        if (providerType == null || providerType == ConferencingProviderType.NONE) {
            return ConferenceDetails.none("provider_none", java.time.Instant.now());
        }
        // For CANCEL/EXTERNAL_TERMINATED we never want to mint a new meeting,
        // we only want to surface whatever URL exists so recipients can
        // recognise the cancelled session.
        boolean isTerminal = "BOOKING_CANCELLED".equals(outboxEventType)
                || "BOOKING_EXTERNAL_TERMINATED".equals(outboxEventType);
        if (isTerminal) {
            return ConferenceDetails.none("terminal_lifecycle", java.time.Instant.now());
        }

        try {
            ConferencingInstruction instruction = "BOOKING_UPDATED".equals(outboxEventType)
                    ? conferencingCoordinator.prepareForUpdate(booking.getId(), booking.getHostId())
                    : conferencingCoordinator.prepareForCreate(booking.getId(), booking.getHostId());
            if (instruction != null && instruction.joinUrl() != null && !instruction.joinUrl().isBlank()) {
                ConferenceDetails details = ConferenceDetails.fromInstruction(instruction, "conferencing_coordinator", java.time.Instant.now());
                log.info("provider_conference_payload_normalized bookingId={} provider={} source={} hasJoinUrl={}",
                        booking.getId(), providerType, details.sourceOfTruth(), details.joinUrl() != null);
                log.info("canonical_conference_projection_created bookingId={} provider={} sourceOfTruth={} updatedAt={}",
                        booking.getId(), details.provider(), details.sourceOfTruth(), details.updatedAt());
                return details;
            }
        } catch (RuntimeException ex) {
            // Synchronous conferencing prep is best-effort here — the sync worker
            // remains the durable retry path. Fall through to read-only lookup.
            log.warn("booking_notification_conferencing_prepare_failed bookingId={} provider={} eventType={} message={}",
                    booking.getId(), providerType, outboxEventType, ex.getMessage());
        }

        ConferenceDetails details = conferencingEventMappingRepository
                .findByBookingIdAndProvider(booking.getId(), providerType)
                .map(mapping -> new ConferenceDetails(
                        providerType.name(),
                        mapping.getJoinUrl(),
                        null,
                        mapping.getMeetingId(),
                        null,
                        java.util.Map.of("hostUrl", mapping.getHostUrl() == null ? "" : mapping.getHostUrl()),
                        "conferencing_event_mapping",
                        java.time.Instant.now()))
                .orElse(ConferenceDetails.none("conferencing_mapping_missing", java.time.Instant.now()));
        if ((details.joinUrl() == null || details.joinUrl().isBlank()) && eventType.getId() != null) {
            details = bookingRepository.findManageRow(booking.getId(), booking.getHostId(), eventType.getId())
                    .map(row -> row.getConferenceUrl())
                    .filter(url -> url != null && !url.isBlank())
                    .map(url -> new ConferenceDetails(
                            providerType.name(),
                            url,
                            null,
                            null,
                            null,
                            java.util.Map.of(),
                            "projection_sync_metadata",
                            java.time.Instant.now()))
                    .orElse(details);
        }
        log.info("conference_details_projection_verified bookingId={} provider={} source={} hasJoinUrl={}",
                booking.getId(), details.provider(), details.sourceOfTruth(), details.joinUrl() != null);
        return details;
    }

    private static String resolveRecipientRole(String recipient,
                                               Optional<String> hostRecipient,
                                               Optional<String> attendeeRecipient) {
        String normalized = recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT);
        String host = hostRecipient.map(v -> v.trim().toLowerCase(Locale.ROOT)).orElse("");
        String attendee = attendeeRecipient.map(v -> v.trim().toLowerCase(Locale.ROOT)).orElse("");
        if (!host.isBlank() && host.equals(normalized) && !attendee.isBlank() && attendee.equals(normalized)) {
            return "HOST_AND_ATTENDEE";
        }
        if (!host.isBlank() && host.equals(normalized)) {
            return "HOST";
        }
        if (!attendee.isBlank() && attendee.equals(normalized)) {
            return "ATTENDEE";
        }
        return "UNKNOWN";
    }

    private static int countIcsAttendees(String ics) {
        if (ics == null || ics.isBlank()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = ics.indexOf("ATTENDEE;", idx)) >= 0) {
            count++;
            idx += 9;
        }
        return count;
    }

    private static String inferClientType(String recipient) {
        if (recipient == null) return "unknown";
        String lower = recipient.toLowerCase(Locale.ROOT);
        if (lower.endsWith("@outlook.com") || lower.endsWith("@hotmail.com") || lower.endsWith("@live.com")) return "outlook";
        if (lower.endsWith("@gmail.com")) return "gmail";
        if (lower.endsWith("@microsoft.com") || lower.endsWith("@office365.com")) return "microsoft_calendar";
        return "generic";
    }

    private static String subject(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType) || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return "Meeting cancelled: " + summary;
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            return "Meeting updated: " + summary;
        }
        return "Meeting confirmed: " + summary;
    }

    private static String body(String summary, String eventType) {
        if ("BOOKING_CANCELLED".equals(eventType) || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return "Your meeting has been cancelled.\n\nEvent: " + summary;
        }
        String lifecycleText = "BOOKING_UPDATED".equals(eventType)
                ? "Your meeting has been rescheduled.\n\nEvent: " + summary
                : "Your meeting is confirmed.\n\nEvent: " + summary;
        return lifecycleText;
    }

    private static String body(String summary, String eventType, String manageLink, ConferenceDetails conferenceDetails) {
        String base = body(summary, eventType);
        StringBuilder builder = new StringBuilder(base);
        String conferenceJoinUrl = conferenceDetails == null ? null : conferenceDetails.joinUrl();
        if (conferenceJoinUrl != null && !conferenceJoinUrl.isBlank()
                && !"BOOKING_CANCELLED".equals(eventType)
                && !"BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            builder.append("\n\nJoin the meeting:\n").append(conferenceJoinUrl);
        }
        if (manageLink == null || manageLink.isBlank()
                || "BOOKING_CANCELLED".equals(eventType)
                || "BOOKING_EXTERNAL_TERMINATED".equals(eventType)) {
            return builder.toString();
        }
        if ("BOOKING_UPDATED".equals(eventType)) {
            builder.append("\n\nNeed to cancel or reschedule?\nManage your booking:\n").append(manageLink);
        } else {
            builder.append("\n\nManage your booking:\n").append(manageLink);
        }
        return builder.toString();
    }

    /**
     * True iff the booking host's projection calendar is on a consumer Microsoft account
     * owned by the host themselves. In that case Outlook auto-imports the iTIP
     * REQUEST ICS we'd attach to the host's email, producing a duplicate event
     * next to the one already written via Graph. Suppress the host recipient so
     * only the guest receives the invite.
     *
     * <p>For ROUND_ROBIN bookings {@code hostId} is the assigned participant, not the
     * event owner — so we resolve the participant's own ACTIVE Microsoft connection
     * rather than the event type's {@code projectionConnectionId}.
     *
     * <p>Scoped to consumer MSAs only: work/school (Entra) accounts also auto-process
     * meeting mail but Graph dispatches the invite itself, so we never attach an
     * ICS for the host in that path. The check stays narrow to the configuration
     * that produces the visible-duplicate symptom.
     */
    private boolean shouldSuppressHostIcsForOwnMsaProjection(EventType eventType, UUID hostId) {
        if (eventType == null) return false;
        // Resolve the host's (participant's for RR) own ACTIVE Microsoft connection.
        return calendarConnectionRepository
                .findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.MICROSOFT,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE)
                .map(MicrosoftAccountClassifier::isConsumerMsa)
                .orElse(false);
    }

    /**
     * True iff the booking host has an active Google calendar connection that they
     * own themselves. Gmail auto-imports any iTIP REQUEST ICS we'd attach to the
     * host's email, producing a duplicate event next to the one already written via
     * the Calendar API. Suppress the host recipient so only the guest receives the invite.
     *
     * <p>For ROUND_ROBIN bookings {@code hostId} is the assigned participant, not the
     * event owner. RR event types have no owner-level projection (projectionProvider and
     * projectionConnectionId are null), so the old approach of looking up the event
     * type's projection connection would always return false for RR — leaving the
     * participant subscribed to a duplicate ICS email on top of their API-written event.
     *
     * <p>The fix mirrors the MSA path: resolve the host's (participant's for RR) own
     * ACTIVE Google connection directly by userId. If such a connection exists, Gmail
     * will auto-import the invite, so suppress the host recipient.
     */
    private boolean shouldSuppressHostIcsForOwnGoogleProjection(EventType eventType, UUID hostId) {
        if (eventType == null) return false;
        return calendarConnectionRepository
                .findByUserIdAndProviderAndStatus(hostId, CalendarProviderType.GOOGLE,
                        io.bunnycal.calendar.domain.CalendarConnectionStatus.ACTIVE)
                .isPresent();
    }
}
