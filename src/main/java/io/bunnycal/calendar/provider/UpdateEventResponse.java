package io.bunnycal.calendar.provider;

public record UpdateEventResponse(String externalEventId,
                                  String providerEventUrl,
                                  String conferenceUrl) {
}
