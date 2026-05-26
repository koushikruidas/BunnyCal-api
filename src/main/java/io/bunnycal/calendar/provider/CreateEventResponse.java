package io.bunnycal.calendar.provider;

public record CreateEventResponse(String externalEventId,
                                  String providerEventUrl,
                                  String conferenceUrl) {
}
