package io.bunnycal.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.time.TimeSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new FixedTimeSource());

    @Test
    void handleCustomException_mapsExperienceNotActivatableToConflict() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleCustomException(new CustomException(ErrorCode.EXPERIENCE_NOT_ACTIVATABLE));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCode.EXPERIENCE_NOT_ACTIVATABLE.getCode(), response.getBody().getError().getCode());
        assertEquals(ErrorCode.EXPERIENCE_NOT_ACTIVATABLE.getMessage(), response.getBody().getError().getMessage());
    }

    private static final class FixedTimeSource implements TimeSource {
        @Override
        public Instant now() {
            return Instant.parse("2026-01-01T00:00:00Z");
        }
    }
}
