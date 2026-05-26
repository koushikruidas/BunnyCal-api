package io.bunnycal.booking.service;

import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.domain.BookingId;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingLifecycleService {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final GuestCapabilityTokenService guestCapabilityTokenService;
    private final boolean requireCapabilityToken;
    private final Instant legacyFallbackCutoff;

    public BookingLifecycleService(
            BookingRepository bookingRepository,
            BookingService bookingService,
            GuestCapabilityTokenService guestCapabilityTokenService,
            @Value("${booking.public.require-capability-token:false}") boolean requireCapabilityToken,
            @Value("${booking.public.capability-token-legacy-cutoff:}") String legacyFallbackCutoff) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.guestCapabilityTokenService = guestCapabilityTokenService;
        this.requireCapabilityToken = requireCapabilityToken;
        this.legacyFallbackCutoff = parseCutoff(legacyFallbackCutoff);
    }

    @Transactional
    public Booking cancelAsGuest(UUID bookingId, UUID hostId, UUID eventTypeId, String token) {
        Booking booking = requireBookingOwnership(bookingId, hostId, eventTypeId);
        enforceGuestCapabilityOrFallback(booking, token, BookingActionType.CANCEL);
        var state = bookingRepository.findStateByIdAndHostAndEventType(bookingId, hostId, eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        bookingService.cancelBooking(bookingId, hostId, state.getVersion(), CancellationSource.GUEST, null);
        return bookingRepository.findById(new BookingId(bookingId, hostId))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
    }

    @Transactional
    public void authorizeGuestReschedule(UUID bookingId, UUID hostId, UUID eventTypeId, String token) {
        Booking booking = requireBookingOwnership(bookingId, hostId, eventTypeId);
        enforceGuestCapabilityOrFallback(booking, token, BookingActionType.RESCHEDULE);
    }

    @Transactional(readOnly = true)
    public Booking authorizeGuestManageView(UUID bookingId, UUID hostId, UUID eventTypeId, String token) {
        Booking booking = requireBookingOwnership(bookingId, hostId, eventTypeId);
        enforceGuestCapabilityOrFallback(booking, token, BookingActionType.MANAGE_BOOKING);
        return booking;
    }

    private Booking requireBookingOwnership(UUID bookingId, UUID hostId, UUID eventTypeId) {
        bookingRepository.findStateByIdAndHostAndEventType(bookingId, hostId, eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
        return bookingRepository.findById(new BookingId(bookingId, hostId))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Booking not found."));
    }

    private void enforceGuestCapabilityOrFallback(Booking booking, String token, BookingActionType action) {
        boolean tokenValid = guestCapabilityTokenService.allows(booking.getId(), booking.getHostId(), token, action)
                || guestCapabilityTokenService.allows(booking.getId(), booking.getHostId(), token, BookingActionType.MANAGE_BOOKING);
        if (tokenValid) {
            return;
        }
        if (requireCapabilityToken) {
            throw new CustomException(ErrorCode.FORBIDDEN, "Guest capability token is required.");
        }
        if (!isLegacyEligible(booking)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "Legacy guest access is disabled for this booking.");
        }
    }

    private boolean isLegacyEligible(Booking booking) {
        if (legacyFallbackCutoff == null) {
            return true;
        }
        if (booking.getCreatedAt() == null) {
            return true;
        }
        return booking.getCreatedAt().isBefore(legacyFallbackCutoff);
    }

    private static Instant parseCutoff(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Instant.parse(text.trim());
    }
}
