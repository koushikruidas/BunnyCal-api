package com.daedalussystems.easySchedule.calendar.provider;

public record UpdateEventResponse(String externalEventId,
                                  String providerEventUrl,
                                  String conferenceUrl) {
}
