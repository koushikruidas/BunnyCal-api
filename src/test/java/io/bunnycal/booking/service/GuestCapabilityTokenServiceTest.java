package io.bunnycal.booking.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.domain.BookingActionToken;
import io.bunnycal.booking.repository.BookingActionTokenRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestCapabilityTokenServiceTest {

    @Mock private BookingActionTokenRepository repository;
    private GuestCapabilityTokenService service;

    @BeforeEach
    void setUp() {
        service = new GuestCapabilityTokenService(repository, new SimpleMeterRegistry());
    }

    @Test
    void issueToken_persistsHashedToken() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        String token = service.issueToken(bookingId, hostId, BookingActionType.MANAGE_BOOKING, Duration.ofDays(1), TokenCreatorType.SYSTEM);

        assertNotNull(token);
        ArgumentCaptor<BookingActionToken> captor = ArgumentCaptor.forClass(BookingActionToken.class);
        verify(repository).save(captor.capture());
        BookingActionToken saved = captor.getValue();
        assertNotNull(saved.getTokenHash());
        org.junit.jupiter.api.Assertions.assertEquals(hostId, saved.getBookingHostId());
        assertTrue(saved.getTokenHash().length() >= 64);
    }

    @Test
    void allows_trueWhenActiveTokenMatchesBookingAndAction() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        BookingActionToken row = new BookingActionToken();
        row.setBookingId(bookingId);
        row.setBookingHostId(hostId);
        row.setActionType(BookingActionType.MANAGE_BOOKING);
        when(repository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(any(), any()))
                .thenReturn(Optional.of(row));

        assertTrue(service.allows(bookingId, hostId, "raw-token", BookingActionType.CANCEL));
    }

    @Test
    void allows_falseWhenTokenMissing() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(repository.findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(any(), any()))
                .thenReturn(Optional.empty());

        assertFalse(service.allows(bookingId, hostId, "raw-token", BookingActionType.CANCEL));
    }
}
