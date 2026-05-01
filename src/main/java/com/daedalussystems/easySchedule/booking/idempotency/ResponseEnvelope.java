package com.daedalussystems.easySchedule.booking.idempotency;

public record ResponseEnvelope<T>(int httpStatus, T body) {
}
