package com.daedalussystems.easySchedule.calendar.provider;

public record CreateEventResponse(String externalEventId,
                                  String providerEventUrl,
                                  String conferenceUrl,
                                  String organizerEmail) {
    public CreateEventResponse(String externalEventId, String providerEventUrl, String conferenceUrl) {
        this(externalEventId, providerEventUrl, conferenceUrl, null);
    }
}
