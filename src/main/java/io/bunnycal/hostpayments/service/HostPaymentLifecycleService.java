package io.bunnycal.hostpayments.service;

import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import io.bunnycal.hostpayments.domain.BookingPayment;
import io.bunnycal.hostpayments.domain.BookingPaymentStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.domain.PaymentReservationKind;
import io.bunnycal.hostpayments.dto.PaymentInitializationResponse;
import io.bunnycal.hostpayments.dto.PublicPaymentStatusResponse;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.BookingPaymentRepository;
import io.bunnycal.hostpayments.repository.EventPaymentConfigRepository;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import io.bunnycal.session.repository.SessionRegistrationRepository;
import io.bunnycal.session.repository.EventSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class HostPaymentLifecycleService {
    private final BookingPaymentRepository paymentRepository;
    private final EventPaymentConfigRepository configRepository;
    private final HostPaymentConnectionRepository connectionRepository;
    private final EventTypeRepository eventTypeRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final EventSessionRepository eventSessionRepository;
    private final HostPaymentProviderRegistry providers;
    private final HostCommerceProperties properties;
    private final ObjectProvider<PublicBookingService> publicBookingService;
    private final PaymentAuditService auditService;

    public HostPaymentLifecycleService(
            BookingPaymentRepository paymentRepository,
            EventPaymentConfigRepository configRepository,
            HostPaymentConnectionRepository connectionRepository,
            EventTypeRepository eventTypeRepository,
            UserRepository userRepository,
            BookingRepository bookingRepository,
            SessionRegistrationRepository registrationRepository,
            EventSessionRepository eventSessionRepository,
            HostPaymentProviderRegistry providers,
            HostCommerceProperties properties,
            ObjectProvider<PublicBookingService> publicBookingService,
            PaymentAuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.configRepository = configRepository;
        this.connectionRepository = connectionRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.registrationRepository = registrationRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.providers = providers;
        this.properties = properties;
        this.publicBookingService = publicBookingService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public boolean paymentRequired(UUID eventTypeId) {
        return configRepository.findByEventTypeIdAndEnabledTrue(eventTypeId).isPresent();
    }

    @Transactional(readOnly = true)
    public void requirePaidIfConfigured(UUID eventTypeId, UUID reservationId) {
        BookingPayment payment = findOptionalForEvent(eventTypeId, reservationId);
        if (payment == null && !paymentRequired(eventTypeId)) return;
        if (payment == null) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Verified payment is required before this booking can be confirmed.");
        }
        if (payment.getStatus() != BookingPaymentStatus.SUCCEEDED) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Verified payment is required before this booking can be confirmed.");
        }
    }

    /** Snapshots immutable commercial terms in the same transaction that creates the hold. */
    @Transactional
    public PaymentTerms snapshotTerms(UUID eventTypeId, UUID ownerId, UUID reservationId,
                                      PaymentReservationKind reservationKind, Instant expiresAt) {
        var config = configRepository.findByEventTypeIdAndEnabledTrue(eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.VALIDATION_ERROR,
                        "This event does not require payment."));
        var connection = connectionRepository.findById(config.getConnectionId())
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED));
        if (!connection.ready() || !ownerId.equals(connection.getUserId())) {
            throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        }
        BookingPayment payment = paymentRepository.findByReservationKindAndReservationId(reservationKind, reservationId)
                .orElseGet(() -> paymentRepository.save(BookingPayment.builder()
                        .reservationKind(reservationKind)
                        .reservationId(reservationId)
                        .eventTypeId(eventTypeId)
                        .eventOwnerId(ownerId)
                        .connectionId(connection.getId())
                        .provider(connection.getProvider())
                        .providerAccountId(connection.getProviderAccountId())
                        .amountMinor(config.getAmountMinor())
                        .currency(config.getCurrency())
                        .status(BookingPaymentStatus.CREATED)
                        .expiresAt(expiresAt)
                        .build()));
        if (!eventTypeId.equals(payment.getEventTypeId()) || !ownerId.equals(payment.getEventOwnerId())) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Payment reservation not found.");
        }
        return new PaymentTerms(payment.getAmountMinor(), payment.getCurrency(), payment.getProvider().name());
    }

    @Transactional
    public PaymentInitializationResponse initialize(String username, String slug, UUID reservationId) {
        var eventType = eventTypeRepository.findByUserIdAndSlugAndDeletedAtIsNull(
                        requireOwnerId(username), slug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        Reservation reservation = requireLiveReservation(eventType.getId(), eventType.getUserId(), reservationId,
                eventType.getKind() == io.bunnycal.availability.domain.EventKind.GROUP);
        BookingPayment payment = paymentRepository.findByReservationKindAndReservationId(reservation.kind(), reservationId)
                .orElseGet(() -> {
                    snapshotTerms(eventType.getId(), eventType.getUserId(), reservationId,
                            reservation.kind(), reservation.expiresAt());
                    return paymentRepository.findByReservationKindAndReservationId(reservation.kind(), reservationId)
                            .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR,
                                    "Payment snapshot was not created."));
                });
        var connection = connectionRepository.findById(payment.getConnectionId())
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED));
        if (!connection.ready() || !connection.getUserId().equals(eventType.getUserId())
                || !connection.getProviderAccountId().equals(payment.getProviderAccountId())) {
            throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        }
        HostPaymentProvider provider = providers.require(payment.getProvider());

        HostPaymentProvider.ProviderPayment existing = null;
        if (payment.getProviderPaymentId() != null) {
            existing = provider.retrievePayment(payment.getProviderAccountId(), payment.getProviderPaymentId());
            applyProviderStatus(payment, existing);
        } else {
            var created = provider.createPayment(new HostPaymentProvider.CreatePayment(
                    payment.getId(), reservationId, reservation.kind().name(), payment.getProviderAccountId(),
                    payment.getAmountMinor(), payment.getCurrency(), eventType.getName(), reservation.guestEmail(),
                    "host-payment-" + reservation.kind() + "-" + reservationId));
            payment.setProviderPaymentId(created.providerPaymentId());
            payment.setStatus(mapStatus(created.status()));
            paymentRepository.save(payment);
            auditService.recordHostCommerce(PaymentAuditService.ACTOR_SYSTEM, "BookingPayment", payment.getId(),
                    "PAYMENT_INITIALIZED", null, java.util.Map.of(
                            "provider", payment.getProvider().name(),
                            "providerPaymentId", created.providerPaymentId(),
                            "amountMinor", payment.getAmountMinor(),
                            "currency", payment.getCurrency(),
                            "status", payment.getStatus().name()));
            return response(payment, created.clientSecret());
        }
        paymentRepository.save(payment);
        return response(payment, existing.clientSecret());
    }

    @Transactional
    public PublicPaymentStatusResponse finalizePayment(String username, String slug, UUID reservationId) {
        UUID ownerId = requireOwnerId(username);
        var eventType = eventTypeRepository.findByUserIdAndSlugAndDeletedAtIsNull(ownerId, slug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        BookingPayment payment = findForEvent(eventType.getId(), reservationId);
        HostPaymentProvider provider = providers.require(payment.getProvider());
        if (payment.getProviderPaymentId() != null && payment.getStatus() != BookingPaymentStatus.SUCCEEDED) {
            applyProviderStatus(payment, provider.retrievePayment(payment.getProviderAccountId(), payment.getProviderPaymentId()));
            paymentRepository.save(payment);
        }
        if (payment.getStatus() == BookingPaymentStatus.SUCCEEDED) confirmOrCompensate(payment, username, slug);
        return statusResponse(payment);
    }

    @Transactional(readOnly = true)
    public PublicPaymentStatusResponse status(String username, String slug, UUID reservationId) {
        UUID ownerId = requireOwnerId(username);
        var eventType = eventTypeRepository.findByUserIdAndSlugAndDeletedAtIsNull(ownerId, slug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        return statusResponse(findForEvent(eventType.getId(), reservationId));
    }

    @Transactional
    public void handleProviderPayment(PaymentProviderType providerType, String accountId,
                                      String providerPaymentId, String eventType,
                                      Long amountRefundedMinor, Long chargeAmountMinor) {
        HostPaymentProvider provider = providers.require(providerType);
        if ("account.application.deauthorized".equals(eventType) && accountId != null) {
            connectionRepository.findByProviderAndProviderAccountId(providerType, accountId)
                    .ifPresent(connection -> {
                        connection.setStatus(io.bunnycal.hostpayments.domain.PaymentConnectionStatus.DISCONNECTED);
                        connection.setChargesEnabled(false);
                        connection.setPayoutsEnabled(false);
                        connectionRepository.save(connection);
                    });
            return;
        }
        if ("account.updated".equals(eventType) && accountId != null) {
            connectionRepository.findByProviderAndProviderAccountId(providerType, accountId)
                    .ifPresent(connection -> {
                        var account = provider.retrieveConnectedAccount(accountId);
                        connection.setChargesEnabled(account.chargesEnabled());
                        connection.setPayoutsEnabled(account.payoutsEnabled());
                        connection.setDetailsSubmitted(account.detailsSubmitted());
                        connection.setRestrictionReason(account.restrictionReason());
                        connection.setStatus(account.ready()
                                ? io.bunnycal.hostpayments.domain.PaymentConnectionStatus.READY
                                : account.detailsSubmitted()
                                    ? io.bunnycal.hostpayments.domain.PaymentConnectionStatus.RESTRICTED
                                    : io.bunnycal.hostpayments.domain.PaymentConnectionStatus.ONBOARDING);
                        connectionRepository.save(connection);
                    });
            return;
        }
        if (accountId == null || providerPaymentId == null) return;
        BookingPayment payment = paymentRepository.findByProviderAndProviderAccountIdAndProviderPaymentId(
                        providerType, accountId, providerPaymentId)
                .orElse(null);
        if (payment == null) return;
        if ("charge.refunded".equals(eventType)) {
            BookingPaymentStatus before = payment.getStatus();
            boolean full = amountRefundedMinor != null && chargeAmountMinor != null
                    && amountRefundedMinor >= chargeAmountMinor;
            payment.setStatus(full ? BookingPaymentStatus.REFUNDED : BookingPaymentStatus.PARTIALLY_REFUNDED);
            if (full) payment.setRefundedAt(Instant.now());
            paymentRepository.save(payment);
            auditTransition(payment, before, "PAYMENT_REFUND_UPDATED", PaymentAuditService.ACTOR_WEBHOOK);
            return;
        }
        if ("charge.dispute.created".equals(eventType)) {
            BookingPaymentStatus before = payment.getStatus();
            payment.setStatus(BookingPaymentStatus.DISPUTED);
            paymentRepository.save(payment);
            auditTransition(payment, before, "PAYMENT_DISPUTED", PaymentAuditService.ACTOR_WEBHOOK);
            return;
        }
        var current = provider.retrievePayment(accountId, providerPaymentId);
        applyProviderStatus(payment, current);
        paymentRepository.save(payment);
        if (payment.getStatus() == BookingPaymentStatus.SUCCEEDED) {
            var bookedEventType = eventTypeRepository.findById(payment.getEventTypeId()).orElse(null);
            if (bookedEventType != null) {
                String username = requireUsername(payment.getEventOwnerId());
                confirmOrCompensate(payment, username, bookedEventType.getSlug());
            }
        }
    }

    @Transactional
    public void markReservationExpired(UUID reservationId) {
        for (PaymentReservationKind kind : PaymentReservationKind.values()) {
            paymentRepository.findByReservationKindAndReservationId(kind, reservationId).ifPresent(payment -> {
                BookingPaymentStatus before = payment.getStatus();
                if (payment.getStatus() == BookingPaymentStatus.SUCCEEDED) {
                    payment.setStatus(BookingPaymentStatus.REFUND_REQUIRED);
                    payment.setFailureCode("HOLD_EXPIRED_AFTER_PAYMENT");
                } else if (!payment.getStatus().terminal()) {
                    payment.setStatus(BookingPaymentStatus.CANCEL_REQUESTED);
                }
                paymentRepository.save(payment);
                auditTransition(payment, before, "RESERVATION_EXPIRED", PaymentAuditService.ACTOR_SYSTEM);
            });
        }
    }

    private void confirmOrCompensate(BookingPayment payment, String username, String slug) {
        if (reservationConfirmed(payment)) return;
        if (Instant.now().isAfter(payment.getExpiresAt())) {
            BookingPaymentStatus before = payment.getStatus();
            payment.setStatus(BookingPaymentStatus.REFUND_REQUIRED);
            payment.setFailureCode("HOLD_EXPIRED_AFTER_PAYMENT");
            paymentRepository.save(payment);
            auditTransition(payment, before, "COMPENSATING_REFUND_REQUIRED", PaymentAuditService.ACTOR_SYSTEM);
            return;
        }
        try {
            publicBookingService.getObject().confirmAfterVerifiedPayment(username, slug, payment.getReservationId());
            auditService.recordHostCommerce(PaymentAuditService.ACTOR_SYSTEM, "BookingPayment", payment.getId(),
                    "BOOKING_CONFIRMED", null, java.util.Map.of(
                            "reservationId", payment.getReservationId(),
                            "reservationKind", payment.getReservationKind().name()));
        } catch (RuntimeException failure) {
            // Finalize and webhook delivery can race. If the competing confirmer won, this is
            // a successful idempotent outcome and must never trigger a compensating refund.
            if (reservationConfirmed(payment)) return;
            BookingPaymentStatus before = payment.getStatus();
            payment.setStatus(BookingPaymentStatus.REFUND_REQUIRED);
            payment.setFailureCode("BOOKING_CONFIRMATION_FAILED");
            paymentRepository.save(payment);
            auditTransition(payment, before, "COMPENSATING_REFUND_REQUIRED", PaymentAuditService.ACTOR_SYSTEM);
        }
    }

    private boolean reservationConfirmed(BookingPayment payment) {
        if (payment.getReservationKind() == PaymentReservationKind.SESSION_REGISTRATION) {
            return registrationRepository.findById(payment.getReservationId())
                    .map(row -> row.getStatus() == io.bunnycal.session.domain.RegistrationStatus.CONFIRMED)
                    .orElse(false);
        }
        return bookingRepository.findStateByIdAndEventTypeId(payment.getReservationId(), payment.getEventTypeId())
                .map(row -> "CONFIRMED".equals(row.getStatus()) || "COMPLETED".equals(row.getStatus()))
                .orElse(false);
    }

    private Reservation requireLiveReservation(UUID eventTypeId, UUID ownerId, UUID id, boolean group) {
        if (group) {
            var registration = registrationRepository.findByIdAndHostId(id, ownerId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Registration not found."));
            boolean correctEvent = eventSessionRepository.findById(registration.getSessionId())
                    .map(session -> eventTypeId.equals(session.getEventTypeId()))
                    .orElse(false);
            if (!correctEvent) throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Registration not found.");
            if (registration.getExpiresAt() == null || !registration.getExpiresAt().isAfter(Instant.now())
                    || registration.getStatus() != io.bunnycal.session.domain.RegistrationStatus.PENDING) {
                throw new CustomException(ErrorCode.REGISTRATION_EXPIRED);
            }
            return new Reservation(PaymentReservationKind.SESSION_REGISTRATION, registration.getExpiresAt(), registration.getGuestEmail());
        }
        var booking = bookingRepository.findAnyByIdAndEventTypeId(id, eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        var state = bookingRepository.findStateByIdAndEventTypeId(id, eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        if (!"PENDING".equals(state.getStatus()) || state.getExpiresAt() == null || !state.getExpiresAt().isAfter(Instant.now())) {
            throw new CustomException(ErrorCode.TOKEN_EXPIRED, "Booking hold has expired.");
        }
        return new Reservation(PaymentReservationKind.BOOKING, state.getExpiresAt(), booking.getGuestEmail());
    }

    private void applyProviderStatus(BookingPayment payment, HostPaymentProvider.ProviderPayment current) {
        BookingPaymentStatus before = payment.getStatus();
        if (current.amountMinor() != payment.getAmountMinor() || !current.currency().equalsIgnoreCase(payment.getCurrency())
                || !payment.getId().toString().equals(current.metadata().get("bunnycal_payment_id"))) {
            payment.setStatus(BookingPaymentStatus.FAILED);
            payment.setFailureCode("PROVIDER_DATA_MISMATCH");
            auditTransition(payment, before, "PROVIDER_PAYMENT_RECONCILED", PaymentAuditService.ACTOR_SYSTEM);
            return;
        }
        payment.setStatus(mapStatus(current.status()));
        payment.setProviderChargeId(current.chargeId());
        if (current.status() == HostPaymentProvider.ProviderPaymentStatus.SUCCEEDED && payment.getPaidAt() == null) {
            payment.setPaidAt(Instant.now());
        }
        auditTransition(payment, before, "PROVIDER_PAYMENT_RECONCILED", PaymentAuditService.ACTOR_SYSTEM);
    }

    private void auditTransition(BookingPayment payment, BookingPaymentStatus before,
                                 String action, String actor) {
        if (before == payment.getStatus()) return;
        auditService.recordHostCommerce(actor, "BookingPayment", payment.getId(), action,
                java.util.Map.of("status", before.name()),
                java.util.Map.of("status", payment.getStatus().name()));
    }

    private static BookingPaymentStatus mapStatus(HostPaymentProvider.ProviderPaymentStatus status) {
        return switch (status) {
            case SUCCEEDED -> BookingPaymentStatus.SUCCEEDED;
            case PROCESSING -> BookingPaymentStatus.PROCESSING;
            case CANCELLED -> BookingPaymentStatus.CANCELLED;
            case FAILED -> BookingPaymentStatus.FAILED;
            case REQUIRES_ACTION -> BookingPaymentStatus.REQUIRES_ACTION;
        };
    }

    private PaymentInitializationResponse response(BookingPayment payment, String clientSecret) {
        return new PaymentInitializationResponse(payment.getId(), payment.getProvider().name(),
                payment.getProviderAccountId(), properties.stripe().publishableKey(), clientSecret,
                payment.getAmountMinor(), payment.getCurrency(), payment.getStatus().name(), payment.getExpiresAt());
    }

    private PublicPaymentStatusResponse statusResponse(BookingPayment payment) {
        String bookingStatus;
        if (payment.getReservationKind() == PaymentReservationKind.SESSION_REGISTRATION) {
            bookingStatus = registrationRepository.findById(payment.getReservationId())
                    .map(row -> row.getStatus().name()).orElse("UNKNOWN");
        } else {
            bookingStatus = bookingRepository.findStateByIdAndEventTypeId(payment.getReservationId(), payment.getEventTypeId())
                    .map(BookingRepository.BookingStateRow::getStatus).orElse("UNKNOWN");
        }
        return new PublicPaymentStatusResponse(payment.getId(), payment.getStatus().name(), bookingStatus,
                "CONFIRMED".equals(bookingStatus), payment.getStatus() == BookingPaymentStatus.REFUNDED);
    }

    private BookingPayment findForEvent(UUID eventTypeId, UUID reservationId) {
        BookingPayment payment = findOptionalForEvent(eventTypeId, reservationId);
        if (payment == null) throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Payment not found.");
        return payment;
    }

    private BookingPayment findOptionalForEvent(UUID eventTypeId, UUID reservationId) {
        BookingPayment payment = paymentRepository.findByReservationKindAndReservationId(
                        PaymentReservationKind.BOOKING, reservationId)
                .or(() -> paymentRepository.findByReservationKindAndReservationId(
                        PaymentReservationKind.SESSION_REGISTRATION, reservationId))
                .orElse(null);
        if (payment == null) return null;
        if (!eventTypeId.equals(payment.getEventTypeId())) throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND);
        return payment;
    }

    private UUID requireOwnerId(String username) {
        return userRepository.findByUsername(username).map(io.bunnycal.auth.domain.user.User::getId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private String requireUsername(UUID userId) {
        return userRepository.findById(userId).map(io.bunnycal.auth.domain.user.User::getUsername)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
    }

    private record Reservation(PaymentReservationKind kind, Instant expiresAt, String guestEmail) {}

    public record PaymentTerms(long amountMinor, String currency, String provider) {}
}
