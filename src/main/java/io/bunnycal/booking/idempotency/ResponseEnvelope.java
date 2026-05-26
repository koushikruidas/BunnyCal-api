package io.bunnycal.booking.idempotency;

public record ResponseEnvelope<T>(int httpStatus, T body) {
}
