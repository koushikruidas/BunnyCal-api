package io.bunnycal.booking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingAssignment;
import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.common.email.BrandedMailSender;
import io.bunnycal.common.email.CalendarMimeAssembler;
import io.bunnycal.common.email.EmailTemplate;
import io.bunnycal.booking.repository.BookingAssignmentRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.ownership.BookingOwnership;
import io.bunnycal.booking.ownership.BookingOwnershipRepository;
import io.bunnycal.booking.service.BookingActionType;
import io.bunnycal.booking.service.BookingSubmissionFormatter;
import io.bunnycal.booking.service.GuestCapabilityTokenService;
import io.bunnycal.booking.service.TokenCreatorType;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.logging.OpsLogSupport;
import io.bunnycal.common.logging.OpsLoggers;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import io.bunnycal.conferencing.service.ConferencingCoordinator;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import io.bunnycal.conferencing.service.ConferencingInstruction;
import io.bunnycal.conferencing.service.ConferenceDetails;
import io.bunnycal.embed.public_.BookingQuestionAnswerRepository;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "booking.notifications.enabled", havingValue = "true")
public class BookingNotificationService {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationService.class);

    /** "Sunday, 12 July 2026" */
    private static final DateTimeFormatter WHEN_DATE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    /** "13:30" */
    private static final DateTimeFormatter WHEN_TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

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
    private final BookingAssignmentRepository bookingAssignmentRepository;
    private final JavaMailSender mailSender;
    private final IcsInviteGenerator icsInviteGenerator;
    private final BookingManageLinkService bookingManageLinkService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final NotificationRecipientResolver recipientResolver;
    private final EmailDeliverabilityPolicy deliverabilityPolicy;
    private final NotificationSendDedupService notificationSendDedupService;
    private final ConferencingCoordinator conferencingCoordinator;
    private final EventConferencingResolver conferencingResolver;
    private final ConferencingEventMappingRepository conferencingEventMappingRepository;
    private final CalendarConnectionRepository calendarConnectionRepository;
    private final BookingOwnershipRepository bookingOwnershipRepository;
    private final BookingQuestionAnswerRepository bookingQuestionAnswerRepository;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final boolean notificationsEnabled;
    private final String fromAddress;
    private final String calendarOrganizerEmail;
    private final String calendarOrganizerName;
    private final String debugEmlDir;
    private final BrandedMailSender brandedMailSender;
    private final boolean brandedCalendarHtml;
    private final Duration guestManageTokenTtl;

    @Autowired
    public BookingNotificationService(BookingRepository bookingRepository,
                                      UserRepository userRepository,
                                      EventTypeRepository eventTypeRepository,
                                      BookingAssignmentRepository bookingAssignmentRepository,
                                      JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      BookingManageLinkService bookingManageLinkService,
                                      GuestCapabilityTokenService guestCapabilityTokenService,
                                      NotificationRecipientResolver recipientResolver,
                                      EmailDeliverabilityPolicy deliverabilityPolicy,
                                      NotificationSendDedupService notificationSendDedupService,
                                      ConferencingCoordinator conferencingCoordinator,
                                      EventConferencingResolver conferencingResolver,
                                      ConferencingEventMappingRepository conferencingEventMappingRepository,
                                      CalendarConnectionRepository calendarConnectionRepository,
                                      BookingOwnershipRepository bookingOwnershipRepository,
                                      BookingQuestionAnswerRepository bookingQuestionAnswerRepository,
                                      BookingSubmissionFormatter bookingSubmissionFormatter,
                                      @Value("${booking.notifications.enabled:false}") boolean notificationsEnabled,
                                      @Value("${booking.notifications.from:no-reply@BunnyCal.local}") String fromAddress,
                                      @Value("${booking.notifications.calendar-organizer-email:${booking.notifications.from:no-reply@BunnyCal.local}}")
                                      String calendarOrganizerEmail,
                                      @Value("${booking.notifications.calendar-organizer-name:BunnyCal Calendar}")
                                      String calendarOrganizerName,
                                      @Value("${booking.notifications.debug-eml-dir:}") String debugEmlDir,
                                      BrandedMailSender brandedMailSender,
                                      @Value("${booking.notifications.email-template.calendar-html-enabled:false}")
                                      boolean brandedCalendarHtml,
                                      @Value("${booking.public.capability-token-ttl-days:14}") long capabilityTokenTtlDays) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.bookingAssignmentRepository = bookingAssignmentRepository;
        this.mailSender = mailSender;
        this.icsInviteGenerator = icsInviteGenerator;
        this.bookingManageLinkService = bookingManageLinkService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.recipientResolver = recipientResolver;
        this.deliverabilityPolicy = deliverabilityPolicy;
        this.notificationSendDedupService = notificationSendDedupService;
        this.conferencingCoordinator = conferencingCoordinator;
        this.conferencingResolver = conferencingResolver;
        this.conferencingEventMappingRepository = conferencingEventMappingRepository;
        this.calendarConnectionRepository = calendarConnectionRepository;
        this.bookingOwnershipRepository = bookingOwnershipRepository;
        this.bookingQuestionAnswerRepository = bookingQuestionAnswerRepository;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.notificationsEnabled = notificationsEnabled;
        this.fromAddress = fromAddress;
        this.calendarOrganizerEmail = calendarOrganizerEmail;
        this.calendarOrganizerName = calendarOrganizerName;
        this.debugEmlDir = debugEmlDir == null ? "" : debugEmlDir.trim();
        this.brandedMailSender = brandedMailSender;
        this.brandedCalendarHtml = brandedCalendarHtml;
        this.guestManageTokenTtl = Duration.ofDays(Math.max(1L, capabilityTokenTtlDays));
    }

    public BookingNotificationService(BookingRepository bookingRepository,
                                      UserRepository userRepository,
                                      EventTypeRepository eventTypeRepository,
                                      BookingAssignmentRepository bookingAssignmentRepository,
                                      JavaMailSender mailSender,
                                      IcsInviteGenerator icsInviteGenerator,
                                      BookingManageLinkService bookingManageLinkService,
                                      GuestCapabilityTokenService guestCapabilityTokenService,
                                      NotificationRecipientResolver recipientResolver,
                                      EmailDeliverabilityPolicy deliverabilityPolicy,
                                      NotificationSendDedupService notificationSendDedupService,
                                      ConferencingCoordinator conferencingCoordinator,
                                      EventConferencingResolver conferencingResolver,
                                      ConferencingEventMappingRepository conferencingEventMappingRepository,
                                      CalendarConnectionRepository calendarConnectionRepository,
                                      BookingOwnershipRepository bookingOwnershipRepository,
                                      boolean notificationsEnabled,
                                      String fromAddress,
                                      String calendarOrganizerEmail,
                                      String calendarOrganizerName,
                                      String debugEmlDir,
                                      long capabilityTokenTtlDays) {
        // Branded calendar HTML is off in this overload: callers that use it predate the
        // template and assert against the legacy text-only MIME shape.
        this(bookingRepository, userRepository, eventTypeRepository, bookingAssignmentRepository, mailSender,
                icsInviteGenerator, bookingManageLinkService, guestCapabilityTokenService, recipientResolver,
                deliverabilityPolicy, notificationSendDedupService, conferencingCoordinator, conferencingResolver,
                conferencingEventMappingRepository, calendarConnectionRepository, bookingOwnershipRepository, null,
                new BookingSubmissionFormatter(new ObjectMapper()), notificationsEnabled, fromAddress,
                calendarOrganizerEmail, calendarOrganizerName, debugEmlDir,
                new BrandedMailSender(mailSender, "", ""), false, capabilityTokenTtlDays);
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
        BookingOwnership ownership = bookingOwnershipRepository.findByBookingId(booking.getId()).orElse(null);

        if (eventType != null && eventType.getKind() == EventKind.COLLECTIVE) {
            handleCollectiveOutboxEvent(booking, host, eventType, event, ownership);
            return;
        }

        String summary = eventType != null && eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = buildBookingDescription(booking);
        Optional<String> attendee = recipientResolver.resolveAttendeeRecipient(booking);
        if (attendee.isEmpty()) {
            log.warn("booking_notification_skip_missing_attendee bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
        }
        Optional<String> hostRecipient = recipientResolver.resolveHostRecipient(host);
        if (hostRecipient.isEmpty()) {
            String normalizedHost = deliverabilityPolicy.normalize(host.getEmail());
            String reason = normalizedHost == null ? "MISSING_OR_INVALID" : "UNDELIVERABLE";
            log.info("booking_notification_host_recipient_skipped bookingId={} hostId={} eventType={} reason={} hostEmail={}",
                    booking.getId(), booking.getHostId(), event.getEventType(), reason, normalizedHost);
        }
        // The projection owner still gets an email — they just must not get a calendar part with
        // it. The Calendar API already wrote the event onto their calendar, so an iTIP REQUEST on
        // top would land a second, identical entry. Dropping them from the recipient list entirely
        // (the previous behaviour) meant the host was never told a booking had happened at all.
        // Ownership comes from the immutable booking_ownership record, not from calendar
        // connectivity: a user may hold an active connection without being the projection owner
        // (a non-assigned RR participant, a COLLECTIVE member) and must then receive the full ICS.
        String icsSuppressionReason = hostRecipient.isPresent()
                ? resolveSuppressionReason(booking.getHostId(), ownership)
                : null;
        String icsSuppressedRecipient = null;
        if (icsSuppressionReason != null) {
            // Send it to the mailbox that owns the calendar we wrote to, not to the login identity.
            // With multi-account calendars these are routinely different: a host can sign in as
            // alice@gmail while the booking projects to their work@company calendar. The whole
            // point of this mail is "the event is on your calendar" — it belongs in the inbox of
            // the account whose calendar that is. Falls back to the login email for pre-V118_0
            // rows that never captured an account_email.
            String writebackEmail = resolveProjectionOwnerEmail(ownership).orElse(hostRecipient.get());
            if (!sameRecipient(writebackEmail, hostRecipient.get())) {
                log.info("booking_notification_host_recipient_redirected_to_writeback bookingId={} hostId={} loginEmail={} writebackEmail={}",
                        booking.getId(), booking.getHostId(), hostRecipient.get(), writebackEmail);
                hostRecipient = Optional.of(writebackEmail);
            }

            // When the host booked their own event, one address is both host and guest and the two
            // roles collapse to a single recipient. The guest half still needs the invite, so the
            // ICS wins — and there is nothing to duplicate anyway, since the projection owner's
            // calendar entry comes from the API write either way.
            if (sameRecipient(hostRecipient.get(), attendee.orElse(null))) {
                log.info("booking_notification_host_ics_suppression_skipped_self_booking bookingId={} hostId={} eventType={}",
                        booking.getId(), booking.getHostId(), event.getEventType());
            } else {
                icsSuppressedRecipient = hostRecipient.get();
                log.info("booking_notification_host_ics_suppressed bookingId={} hostId={} eventType={} reason={} hostEmail={}",
                        booking.getId(), booking.getHostId(), event.getEventType(), icsSuppressionReason, hostRecipient.get());
            }
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
        // The body carried no date or time. Every other recipient gets an ICS and their mail client
        // renders the time from that; the projection owner gets no calendar part, so for them the
        // mail announced a meeting without ever saying when. Render it in the host's own zone.
        String when = formatWhen(booking.getStartTime(), booking.getEndTime(),
                host == null ? null : host.getTimezone());
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
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_skipped bookingId={} eventId={} recipient={} role={} channel=email eventType={} reasonCode={}",
                        booking.getId(), null, OpsLogSupport.maskEmail(recipient), role, event.getEventType(),
                        OpsLogSupport.notificationReasonCode("missing_event_id"));
                continue;
            }
            boolean claimed = notificationSendDedupService.claim(event.getId(), recipient, event.getEventType());
            if (!claimed) {
                log.info("booking_notification_send_skipped_duplicate eventId={} bookingId={} recipient={} eventType={}",
                        event.getId(), booking.getId(), recipient, event.getEventType());
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_skipped bookingId={} eventId={} recipient={} role={} channel=email eventType={} reasonCode={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), role, event.getEventType(),
                        OpsLogSupport.notificationReasonCode("duplicate"));
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
                            ownership == null ? "unknown" : ownership.getProjectionProvider(),
                            "",
                            "",
                            clientType);
                } else {
                    log.info("organizer_authority_verified bookingId={} provider={} externalEventId={} organizerIdentity={} clientType={}",
                            booking.getId(),
                            ownership == null ? "unknown" : ownership.getProjectionProvider(),
                            "",
                            calendarOrganizerEmail,
                            clientType);
                }
                // The projection owner gets the notification without a calendar part: the event is
                // already on their calendar from the API write, and attaching an iTIP REQUEST on
                // top is what made Outlook add a second copy. Everyone else gets the full invite.
                boolean withIcs = !sameRecipient(recipient, icsSuppressedRecipient);
                sendMail(recipient, summary, event.getEventType(),
                        withIcs ? standaloneIcs : null,
                        withIcs ? eventMethod : null,
                        manageLink, conferenceDetails, booking.getId(), description, when);
                log.info("booking_notification_send_success eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), withIcs);
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_success bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} conferenceProvider={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), role, event.getEventType(), withIcs,
                        conferenceDetails == null ? "NONE" : conferenceDetails.provider());
                log.info("lifecycle_client_reconciliation_verified bookingId={} provider={} externalEventId={} organizerIdentity={} clientType={} lifecycleOperation={}",
                        booking.getId(),
                        ownership == null ? "unknown" : ownership.getProjectionProvider(),
                        "",
                        calendarOrganizerEmail,
                        clientType,
                        event.getEventType());
            } catch (Exception ex) {
                notificationSendDedupService.release(event.getId(), recipient, event.getEventType());
                log.warn("booking_notification_send_failed_retryable eventId={} bookingId={} recipient={} role={} eventType={} hasIcs={} message={}",
                        event.getId(), booking.getId(), recipient, role, event.getEventType(), true, ex.getMessage());
                OpsLoggers.NOTIFICATION.warn(
                        "notification_send_failed bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} reasonCode={} message={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), role, event.getEventType(), true,
                        "MAIL_PROVIDER_ERROR", OpsLogSupport.truncate(ex.getMessage(), 160));
                throw new IllegalStateException("notification delivery failed for recipient " + recipient, ex);
            }
        }
    }

    private void handleCollectiveOutboxEvent(Booking booking, User owner, EventType eventType, OutboxEvent event,
                                              @org.springframework.lang.Nullable BookingOwnership ownership) {
        String summary = eventType.getName() != null && !eventType.getName().isBlank()
                ? eventType.getName()
                : "Scheduled Meeting";
        String description = buildBookingDescription(booking);
        String eventMethod = "BOOKING_CANCELLED".equals(event.getEventType()) ? "CANCEL" : "REQUEST";
        int sequence = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, booking.getCalendarSequence()));
        String eventTypeSlug = eventType.getSlug();
        String ownerUsername = owner.getUsername();

        boolean includeManageLink = "BOOKING_CONFIRMED".equals(event.getEventType())
                || "BOOKING_CONFIRMED_READY".equals(event.getEventType())
                || "BOOKING_UPDATED".equals(event.getEventType());
        boolean canBuildManageLink = includeManageLink
                && ownerUsername != null && !ownerUsername.isBlank()
                && eventTypeSlug != null && !eventTypeSlug.isBlank();

        // Load all participants from booking_assignments (immutable ledger set at hold time).
        List<BookingAssignment> assignments = bookingAssignmentRepository.findAllByBookingId(booking.getId());
        List<IcsInviteGenerator.CollectiveHost> icsHosts = new ArrayList<>();
        List<String> participantRecipients = new ArrayList<>();
        // The projection owner is still notified — they just receive the mail without a calendar
        // part, because the API write already put the event on their calendar. Holder, not a plain
        // local, because it is assigned from inside the lambda below.
        String[] icsSuppressedHolder = new String[1];
        for (BookingAssignment assignment : assignments) {
            userRepository.findById(assignment.getParticipantUserId()).ifPresent(participant -> {
                Optional<String> participantRecipient = recipientResolver.resolveHostRecipient(participant);
                if (participantRecipient.isEmpty()) {
                    log.info("booking_notification_participant_recipient_skipped bookingId={} participantId={} eventType={}",
                            booking.getId(), participant.getId(), event.getEventType());
                } else {
                    // The projection owner (event owner) has the booking written directly to their
                    // calendar by the sync pipeline — omit their calendar part to avoid a duplicate
                    // entry, but still send them the notification. Non-owner participants have no
                    // direct projection and must always receive the full ICS.
                    String suppressReason = participant.getId().equals(booking.getHostId())
                            ? resolveSuppressionReason(booking.getHostId(), ownership)
                            : null;
                    if (suppressReason != null) {
                        // Deliver to the mailbox that owns the calendar we wrote to, not to the
                        // login identity — with multi-account calendars they are often different.
                        String recipient = resolveProjectionOwnerEmail(ownership)
                                .orElse(participantRecipient.get());
                        if (!sameRecipient(recipient, participantRecipient.get())) {
                            log.info("booking_notification_collective_owner_redirected_to_writeback bookingId={} participantId={} loginEmail={} writebackEmail={}",
                                    booking.getId(), participant.getId(), participantRecipient.get(), recipient);
                        }
                        icsSuppressedHolder[0] = recipient;
                        participantRecipients.add(recipient);
                        log.info("booking_notification_collective_owner_ics_suppressed bookingId={} participantId={} reason={}",
                                booking.getId(), participant.getId(), suppressReason);
                    } else {
                        participantRecipients.add(participantRecipient.get());
                    }
                }
                icsHosts.add(new IcsInviteGenerator.CollectiveHost(participant.getName(), participant.getEmail()));
            });
        }

        Optional<String> attendee = recipientResolver.resolveAttendeeRecipient(booking);
        if (attendee.isEmpty()) {
            log.warn("booking_notification_skip_missing_attendee bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
        }

        // If the projection owner is also the guest, the two roles collapse to one address and the
        // guest half still needs the invite — the ICS wins.
        String icsSuppressedRecipient = icsSuppressedHolder[0];
        if (icsSuppressedRecipient != null && sameRecipient(icsSuppressedRecipient, attendee.orElse(null))) {
            log.info("booking_notification_collective_ics_suppression_skipped_self_booking bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
            icsSuppressedRecipient = null;
        }

        List<String> candidateRecipients = new ArrayList<>(participantRecipients);
        attendee.ifPresent(candidateRecipients::add);
        List<String> recipients = recipientResolver.deduplicate(candidateRecipients);
        if (recipients.isEmpty()) {
            log.info("booking_notification_all_recipients_skipped bookingId={} eventType={}",
                    booking.getId(), event.getEventType());
            return;
        }
        log.info("booking_notification_recipients_resolved bookingId={} eventId={} eventType={} participantCount={} attendeePresent={} dedupedRecipients={}",
                booking.getId(), event.getId(), event.getEventType(),
                icsHosts.size(), attendee.isPresent(), recipients.size());

        // See formatWhen: the body carried no date or time, which only shows on the mail that has
        // no ICS to render it from — the projection owner's.
        String when = formatWhen(booking.getStartTime(), booking.getEndTime(),
                userRepository.findById(booking.getHostId()).map(User::getTimezone).orElse(null));
        ConferenceDetails conferenceDetails = resolveConferenceDetails(booking, eventType, event.getEventType());
        String attendeeName = booking.getGuestName();
        String attendeeEmail = attendee.orElse(null);
        String standaloneIcs = "CANCEL".equals(eventMethod)
                ? icsInviteGenerator.buildCollectiveCancel(
                        booking.getId(), summary, description,
                        booking.getStartTime(), booking.getEndTime(),
                        calendarOrganizerName, calendarOrganizerEmail,
                        icsHosts, attendeeName, attendeeEmail, sequence, conferenceDetails)
                : icsInviteGenerator.buildCollectiveRequest(
                        booking.getId(), summary, description,
                        booking.getStartTime(), booking.getEndTime(),
                        calendarOrganizerName, calendarOrganizerEmail,
                        icsHosts, attendeeName, attendeeEmail, sequence, conferenceDetails);
        log.info("booking_notification_ics_built bookingId={} eventId={} eventType={} hasAttachment={} method={} attendeesInIcs={} hasConferenceUrl={}",
                booking.getId(), event.getId(), event.getEventType(), true, eventMethod,
                countIcsAttendees(standaloneIcs),
                conferenceDetails != null && conferenceDetails.joinUrl() != null && !conferenceDetails.joinUrl().isBlank());

        // Issue one manage token for the entire notification batch — all recipients
        // share the same booking and the same permissions, so one token is correct.
        String manageLink = null;
        if (canBuildManageLink) {
            String manageToken = guestCapabilityTokenService.issueToken(
                    booking.getId(), booking.getHostId(),
                    BookingActionType.MANAGE_BOOKING, guestManageTokenTtl, TokenCreatorType.SYSTEM);
            manageLink = bookingManageLinkService.build(
                    booking.getId(), manageToken, ownerUsername, eventTypeSlug);
        }

        for (String recipient : recipients) {
            if (event.getId() == null) {
                log.warn("booking_notification_send_skipped_missing_event_id bookingId={} recipient={} eventType={}",
                        booking.getId(), recipient, event.getEventType());
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_skipped bookingId={} eventId={} recipient={} role={} channel=email eventType={} reasonCode={}",
                        booking.getId(), null, OpsLogSupport.maskEmail(recipient), "PARTICIPANT", event.getEventType(),
                        OpsLogSupport.notificationReasonCode("missing_event_id"));
                continue;
            }
            boolean claimed = notificationSendDedupService.claim(event.getId(), recipient, event.getEventType());
            if (!claimed) {
                log.info("booking_notification_send_skipped_duplicate eventId={} bookingId={} recipient={} eventType={}",
                        event.getId(), booking.getId(), recipient, event.getEventType());
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_skipped bookingId={} eventId={} recipient={} role={} channel=email eventType={} reasonCode={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), "PARTICIPANT", event.getEventType(),
                        OpsLogSupport.notificationReasonCode("duplicate"));
                continue;
            }
            try {
                // The projection owner is notified without a calendar part — the API write already
                // put the event on their calendar; attaching an iTIP REQUEST would add a second one.
                boolean withIcs = !sameRecipient(recipient, icsSuppressedRecipient);
                sendMail(recipient, summary, event.getEventType(),
                        withIcs ? standaloneIcs : null,
                        withIcs ? eventMethod : null,
                        manageLink, conferenceDetails, booking.getId(), description, when);
                log.info("booking_notification_send_success eventId={} bookingId={} recipient={} eventType={} hasIcs={}",
                        event.getId(), booking.getId(), recipient, event.getEventType(), withIcs);
                OpsLoggers.NOTIFICATION.info(
                        "notification_send_success bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} conferenceProvider={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), "PARTICIPANT", event.getEventType(),
                        withIcs, conferenceDetails == null ? "NONE" : conferenceDetails.provider());
            } catch (Exception ex) {
                notificationSendDedupService.release(event.getId(), recipient, event.getEventType());
                log.warn("booking_notification_send_failed_retryable eventId={} bookingId={} recipient={} eventType={} message={}",
                        event.getId(), booking.getId(), recipient, event.getEventType(), ex.getMessage());
                OpsLoggers.NOTIFICATION.warn(
                        "notification_send_failed bookingId={} eventId={} recipient={} role={} channel=email eventType={} hasIcs={} reasonCode={} message={}",
                        booking.getId(), event.getId(), OpsLogSupport.maskEmail(recipient), "PARTICIPANT", event.getEventType(),
                        true, "MAIL_PROVIDER_ERROR", OpsLogSupport.truncate(ex.getMessage(), 160));
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
                          UUID bookingId,
                          String submissionDescription,
                          @org.springframework.lang.Nullable String when) throws Exception {
        var message = mailSender.createMimeMessage();
        // We must construct the MIME tree manually because the auto-rendering contract that
        // Outlook and Gmail honour requires a specific shape: multipart/alternative containing
        // text/plain + an INLINE text/calendar; method=REQUEST. MimeMessageHelper.addAttachment
        // alone collapses the calendar part to attachment-only, which is why Outlook previously
        // demanded a manual "Add to calendar" flow.
        //
        // The calendar payload is carried exactly ONCE. It used to be sent twice — inline *and*
        // as an invite.ics attachment, both with method=REQUEST and the same UID — on the theory
        // that the attachment was an inert fallback for clients that only read attachments. It is
        // not inert: Outlook honours the inline REQUEST and auto-processes the attached one too,
        // landing two identical events on the calendar. Gmail hid the bug by de-duplicating on UID.
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

        String textBody = body(summary, eventType, manageLink, conferenceDetails, submissionDescription, when);

        if (ics != null && method != null) {
            // See CalendarMimeAssembler for why the branded shape moves the calendar part out
            // of the multipart/alternative instead of adding HTML alongside it.
            if (brandedCalendarHtml) {
                String html = calendarTemplate(summary, eventType, manageLink, conferenceDetails,
                        submissionDescription, when).renderHtml();
                CalendarMimeAssembler.buildBranded(message, textBody, html, ics, method);
            } else {
                CalendarMimeAssembler.buildTextOnly(message, textBody, ics, method);
            }
        } else if (brandedCalendarHtml) {
            // No invite (e.g. the projection owner): still send the branded body.
            EmailTemplate template = calendarTemplate(summary, eventType, manageLink, conferenceDetails,
                    submissionDescription, when);
            helper.setText(textBody, template.renderHtml());
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
        ConferencingProviderType providerType = conferencingResolver.resolve(booking.getHostId(), eventType);
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
                OpsLoggers.CONFERENCE.info(
                        "conference_create_success bookingId={} provider={} hostId={} eventTypeId={} source={} hasJoinUrl={}",
                        booking.getId(), details.provider(), booking.getHostId(), eventType.getId(), details.sourceOfTruth(), true);
                return details;
            }
        } catch (RuntimeException ex) {
            // Synchronous conferencing prep is best-effort here — the sync worker
            // remains the durable retry path. Fall through to read-only lookup.
            log.warn("booking_notification_conferencing_prepare_failed bookingId={} provider={} eventType={} message={}",
                    booking.getId(), providerType, outboxEventType, ex.getMessage());
            OpsLoggers.CONFERENCE.warn(
                    "conference_create_failed bookingId={} provider={} hostId={} eventTypeId={} eventType={} reasonCode={} message={}",
                    booking.getId(), providerType, booking.getHostId(), eventType.getId(), outboxEventType,
                    "CONFERENCE_CREATE_FAILED", OpsLogSupport.truncate(ex.getMessage(), 160));
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
        OpsLoggers.CONFERENCE.info(
                "conference_create_skipped bookingId={} provider={} hostId={} eventTypeId={} source={} reasonCode={} hasJoinUrl={}",
                booking.getId(),
                details.provider(),
                booking.getHostId(),
                eventType.getId(),
                details.sourceOfTruth(),
                details.joinUrl() == null || details.joinUrl().isBlank() ? "NO_CONFERENCE_URL" : "EXISTING_PROJECTION",
                details.joinUrl() != null && !details.joinUrl().isBlank());
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

    /**
     * Renders the meeting time for the plain-text body.
     *
     * <p>The body never carried a date or time. Nobody noticed, because every recipient also got an
     * ICS and their mail client rendered the time from that. The projection owner is the one
     * recipient who gets no calendar part — so for them the mail said a meeting was confirmed
     * without ever saying when.
     *
     * <p>Rendered in the recipient's own zone, falling back to UTC when the host has none set.
     */
    private static String formatWhen(Instant start, Instant end, @org.springframework.lang.Nullable String timezone) {
        ZoneId zone;
        try {
            zone = (timezone == null || timezone.isBlank()) ? ZoneOffset.UTC : ZoneId.of(timezone);
        } catch (RuntimeException ex) {
            zone = ZoneOffset.UTC;
        }
        ZonedDateTime from = start.atZone(zone);
        ZonedDateTime to = end.atZone(zone);
        // e.g. "Sunday, 12 July 2026, 13:30–14:00 (Asia/Kolkata)"
        return WHEN_DATE.format(from) + ", " + WHEN_TIME.format(from) + "–" + WHEN_TIME.format(to)
                + " (" + zone.getId() + ")";
    }

    private static String body(String summary,
                               String eventType,
                               String manageLink,
                               ConferenceDetails conferenceDetails,
                               String submissionDescription,
                               @org.springframework.lang.Nullable String when) {
        String base = body(summary, eventType);
        StringBuilder builder = new StringBuilder(base);
        if (when != null && !when.isBlank()) {
            builder.append("\nWhen: ").append(when);
        }
        if (submissionDescription != null && !submissionDescription.isBlank()) {
            builder.append("\n\n").append(submissionDescription.trim());
        }
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
     * Branded HTML counterpart to {@link #body}. Carries the same substance — lifecycle
     * sentence, when, submission answers, join link, manage link — in the shared layout.
     *
     * <p>Only rendered when {@code booking.notifications.email-template.calendar-html-enabled}
     * is on; the plain-text body is always sent as the alternative.
     */
    private EmailTemplate calendarTemplate(String summary,
                                           String eventType,
                                           String manageLink,
                                           ConferenceDetails conferenceDetails,
                                           String submissionDescription,
                                           @org.springframework.lang.Nullable String when) {
        boolean cancelled = "BOOKING_CANCELLED".equals(eventType)
                || "BOOKING_EXTERNAL_TERMINATED".equals(eventType);
        boolean rescheduled = "BOOKING_UPDATED".equals(eventType);

        EmailTemplate.Builder b = brandedMailSender.template()
                .eyebrow(cancelled ? "Meeting cancelled" : rescheduled ? "Meeting rescheduled" : "Meeting confirmed")
                .headline(summary)
                .paragraph(cancelled
                        ? "Your meeting has been cancelled."
                        : rescheduled
                                ? "Your meeting has been rescheduled. The updated details are below."
                                : "Your meeting is confirmed. The details are below.")
                .detail("Event", summary);

        if (when != null && !when.isBlank()) {
            b.detail("When", when);
        }
        if (submissionDescription != null && !submissionDescription.isBlank()) {
            b.preformatted(submissionDescription.trim());
        }

        String joinUrl = conferenceDetails == null ? null : conferenceDetails.joinUrl();
        if (joinUrl != null && !joinUrl.isBlank() && !cancelled) {
            b.primaryAction("Join the meeting", joinUrl);
            if (manageLink != null && !manageLink.isBlank()) {
                b.secondaryAction("Manage booking", manageLink);
            }
        } else if (manageLink != null && !manageLink.isBlank() && !cancelled) {
            b.primaryAction("Manage booking", manageLink);
        }

        if (!cancelled && manageLink != null && !manageLink.isBlank()) {
            b.note("Need to cancel or reschedule? Use the manage link above.");
        }
        b.footerReason("you're receiving this because you're on this booking");
        return b.build();
    }

    private String buildBookingDescription(Booking booking) {
        return bookingSubmissionFormatter.buildBookingDescription(
                booking,
                bookingSubmissionFormatter.toResponses(
                        bookingQuestionAnswerRepository == null
                                ? java.util.List.of()
                                : bookingQuestionAnswerRepository.findByBookingIdAndHostId(booking.getId(), booking.getHostId())));
    }

    /**
     * The mailbox that owns the calendar this booking was written to.
     *
     * <p>Read from the projection connection recorded on {@code booking_ownership}, so it is
     * whatever the scheduling resolver actually chose — the event type's configured write-back
     * calendar for a 1:1/group/collective, and the assigned participant's default write-back
     * calendar for a round robin. No special-casing per event kind is needed here: ownership is the
     * record of where the event really went.
     *
     * <p>Empty when the connection predates V118_0 and never captured an account_email; callers
     * fall back to the login identity, which was the only address available then anyway.
     */
    private Optional<String> resolveProjectionOwnerEmail(@org.springframework.lang.Nullable BookingOwnership ownership) {
        if (ownership == null || ownership.getProjectionConnectionId() == null) {
            return Optional.empty();
        }
        return calendarConnectionRepository.findById(ownership.getProjectionConnectionId())
                .map(CalendarConnection::getAccountEmail)
                .map(deliverabilityPolicy::normalize)
                .filter(deliverabilityPolicy::isDeliverable);
    }

    /**
     * Email addresses are compared case-insensitively and whitespace-trimmed. The recipient list
     * has already been normalised by {@code NotificationRecipientResolver.deduplicate}, but the
     * address we hold the suppression decision against comes straight from the resolver, so the
     * two are compared on their own terms rather than assuming an identical rendering.
     */
    private static boolean sameRecipient(@org.springframework.lang.Nullable String a,
                                         @org.springframework.lang.Nullable String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Returns the suppression reason string if the host/participant's ICS email must be
     * suppressed, or {@code null} if they must receive it.
     *
     * <p>The rule is provider-agnostic: <b>the projection owner never receives an ICS.</b> The
     * Calendar API has already written the event directly onto their calendar, so an iTIP
     * REQUEST on top of that lands a second, identical entry. Both providers auto-process an
     * inbound REQUEST — Gmail imports it, and Outlook does too (for consumer MSA <em>and</em>
     * Entra work/school alike). Neither is a special case; the duplicate is caused by the API
     * write, not by the mailbox type.
     *
     * <p>This is safe because the API write is deliberately silent: Google is called with
     * {@code sendUpdates=none} and Graph with {@code responseRequested=false}, so the providers
     * send no invitations of their own. BunnyCal is the sole invite sender — the ICS email is
     * the one and only RSVP mechanism, and it goes to everyone <em>except</em> the person whose
     * calendar we already wrote to.
     *
     * <p>Ownership is determined from the immutable {@code booking_ownership} record (set at
     * booking time via {@code BookingOwnershipService.ensureOwnership}), not from calendar
     * connectivity alone. A user may have an active Google or Microsoft connection without being
     * the projection owner (e.g. a non-assigned RR participant, a COLLECTIVE member), and must
     * still receive the ICS in that case.
     */
    @org.springframework.lang.Nullable
    private String resolveSuppressionReason(UUID hostId, @org.springframework.lang.Nullable BookingOwnership ownership) {
        if (ownership == null || ownership.getProjectionConnectionId() == null) {
            return null;
        }
        CalendarConnection projectionConnection = calendarConnectionRepository
                .findById(ownership.getProjectionConnectionId())
                .orElse(null);
        if (projectionConnection == null) {
            return null;
        }
        // Only suppress when the projection connection belongs to this recipient.
        if (!hostId.equals(projectionConnection.getUserId())) {
            return null;
        }
        return switch (projectionConnection.getProvider()) {
            case GOOGLE -> "google_projection_owner";
            case MICROSOFT -> "microsoft_projection_owner";
        };
    }
}
