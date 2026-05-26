package io.bunnycal.sync.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.cache.SlotCacheVersionService;
import io.bunnycal.booking.repository.BookingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ExternalUpdateConvergenceServiceTest {
    @Mock private BookingRepository bookingRepository;
    @Mock private SlotCacheVersionService slotCacheVersionService;

    private ExternalUpdateConvergenceService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ExternalUpdateConvergenceService(
                bookingRepository,
                slotCacheVersionService,
                new SimpleMeterRegistry());
    }

    @Test
    void providerUpdate_appliesWindowAndBumpsSlotCache() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Instant startBefore = Instant.parse("2026-06-01T10:00:00Z");
        Instant endBefore = Instant.parse("2026-06-01T10:30:00Z");
        Instant startIncoming = Instant.parse("2026-06-01T11:00:00Z");
        Instant endIncoming = Instant.parse("2026-06-01T11:30:00Z");
        when(bookingRepository.findWindowStateById(bookingId))
                .thenReturn(Optional.of(state(bookingId, hostId, "CONFIRMED", startBefore, endBefore)));
        when(bookingRepository.projectExternalActiveWindow(bookingId, startIncoming, endIncoming)).thenReturn(1);

        var result = service.convergeProviderUpdate(
                bookingId, "GOOGLE", "ext-1", startIncoming, endIncoming, "provider_projection");

        assertEquals("applied", result.result());
        assertEquals(1, result.bookingRows());
        verify(slotCacheVersionService).bumpVersion(hostId);
    }

    @Test
    void providerUpdate_terminalBookingNoops() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-01T10:00:00Z");
        Instant end = Instant.parse("2026-06-01T10:30:00Z");
        when(bookingRepository.findWindowStateById(bookingId))
                .thenReturn(Optional.of(state(bookingId, hostId, "CANCELLED", start, end)));

        var result = service.convergeProviderUpdate(
                bookingId, "GOOGLE", "ext-1", start, end, "provider_projection");

        assertEquals("already_terminal", result.result());
        assertEquals(0, result.bookingRows());
        verify(bookingRepository, never()).projectExternalActiveWindow(bookingId, start, end);
        verify(slotCacheVersionService, never()).bumpVersion(hostId);
    }

    @Test
    void providerUpdate_unchangedWindowNoops() {
        UUID bookingId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-01T10:00:00Z");
        Instant end = Instant.parse("2026-06-01T10:30:00Z");
        when(bookingRepository.findWindowStateById(bookingId))
                .thenReturn(Optional.of(state(bookingId, hostId, "CONFIRMED", start, end)));

        var result = service.convergeProviderUpdate(
                bookingId, "GOOGLE", "ext-1", start, end, "provider_projection");

        assertEquals("unchanged_window", result.result());
        assertEquals(0, result.bookingRows());
        verify(bookingRepository, never()).projectExternalActiveWindow(bookingId, start, end);
    }

    private static BookingRepository.BookingWindowStateRow state(UUID bookingId,
                                                                 UUID hostId,
                                                                 String status,
                                                                 Instant start,
                                                                 Instant end) {
        return new BookingRepository.BookingWindowStateRow() {
            public UUID getId() { return bookingId; }
            public UUID getHostId() { return hostId; }
            public String getStatus() { return status; }
            public Instant getStartTime() { return start; }
            public Instant getEndTime() { return end; }
        };
    }
}
