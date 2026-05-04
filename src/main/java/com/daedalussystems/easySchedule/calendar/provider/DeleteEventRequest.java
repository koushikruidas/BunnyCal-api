package com.daedalussystems.easySchedule.calendar.provider;

import java.util.UUID;

public record DeleteEventRequest(UUID connectionId,
                                 String externalEventId) {
}
