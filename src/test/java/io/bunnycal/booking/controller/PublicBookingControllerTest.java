package io.bunnycal.booking.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.booking.idempotency.IdempotencyOutcome;
import io.bunnycal.booking.idempotency.IdempotencyService;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.booking.service.PublicGroupSessionQueryService;
import io.bunnycal.common.time.TimeConversionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicBookingControllerTest {
    @Mock
    private PublicBookingService publicBookingService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private TimeConversionService timeConversionService;
    @Mock
    private PublicGroupSessionQueryService publicGroupSessionQueryService;

    @Test
    void hold_requestHashIncludesNormalizedGuestFields() {
        PublicBookingController controller = new PublicBookingController(
                publicBookingService,
                publicGroupSessionQueryService,
                idempotencyService,
                new ObjectMapper().findAndRegisterModules(),
                timeConversionService);
        when(timeConversionService.normalizeClientInstant(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.execute(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new IdempotencyOutcome.Fresh<>(201, Map.of("ok", true)));

        PublicBookRequest request = new PublicBookRequest(
                Instant.parse("2026-05-10T10:00:00Z"),
                "  Guest@Example.COM ",
                "  Guest User  "
        );
        controller.hold("host", "intro", "idem-1", null, request);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(idempotencyService).execute(
                anyString(), any(), anyString(), hashCaptor.capture(), any());
        String firstHash = hashCaptor.getValue();

        PublicBookRequest normalizedEquivalent = new PublicBookRequest(
                Instant.parse("2026-05-10T10:00:00Z"),
                "guest@example.com",
                "Guest User"
        );
        controller.hold("host", "intro", "idem-2", null, normalizedEquivalent);
        org.mockito.Mockito.verify(idempotencyService, org.mockito.Mockito.times(2)).execute(
                anyString(), any(), anyString(), hashCaptor.capture(), any());
        String secondHash = hashCaptor.getAllValues().get(1);
        assertEquals(firstHash, secondHash);
    }
}
