package io.bunnycal.calendar.provider;

import java.util.UUID;

public record DeleteEventRequest(UUID connectionId,
                                 String externalEventId) {
}
