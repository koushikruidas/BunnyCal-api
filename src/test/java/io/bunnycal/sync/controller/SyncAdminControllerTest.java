package io.bunnycal.sync.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.common.exception.CustomException;
import io.bunnycal.sync.service.SyncAdminService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class SyncAdminControllerTest {
    @Mock
    private SyncAdminService syncAdminService;

    private SyncAdminController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new SyncAdminController(syncAdminService);
    }

    @Test
    void deadLetters_returnsRows() {
        Authentication auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null);
        UUID id = UUID.randomUUID();
        when(syncAdminService.deadLetters("google", 10)).thenReturn(List.of(
                new SyncAdminService.DeadLetterView(
                        id, "google", "BOOKING", UUID.randomUUID(), "CREATE", "FAILED", 3, "INVALID_REQUEST", Instant.now())));

        var body = controller.deadLetters(auth, "google", 10).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals(1, body.getData().size());
        assertEquals(id, body.getData().get(0).id());
    }

    @Test
    void requeue_returnsStatus() {
        Authentication auth = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null);
        UUID id = UUID.randomUUID();
        when(syncAdminService.requeue(id)).thenReturn(true);

        var body = controller.requeue(auth, id).getBody();

        assertEquals(true, body.isSuccess());
        assertEquals(Map.of("jobId", id, "requeued", true), body.getData());
        verify(syncAdminService).requeue(id);
    }

    @Test
    void unauthenticated_throwsUnauthorized() {
        CustomException ex = assertThrows(CustomException.class,
                () -> controller.deadLetters(null, "google", 10));
        assertEquals("UNAUTHORIZED", ex.getErrorCode().getCode());
    }
}
