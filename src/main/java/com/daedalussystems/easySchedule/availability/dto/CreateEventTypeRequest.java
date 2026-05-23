package com.daedalussystems.easySchedule.availability.dto;

import com.daedalussystems.easySchedule.common.api.ForwardCompatibleRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateEventTypeRequest(
        String name,
        String description,
        String location,
        Integer durationMinutes,
        Integer bufferBeforeMinutes,
        Integer bufferAfterMinutes,
        Integer slotIntervalMinutes,
        Integer minNoticeMinutes,
        Integer maxAdvanceDays,
        Integer holdDurationMinutes,
        String slug,
        String conferencingProvider,
        String customConferenceUrl
 ) implements ForwardCompatibleRequest {
    public CreateEventTypeRequest(
            String name,
            String description,
            String location,
            Integer durationMinutes,
            Integer bufferBeforeMinutes,
            Integer bufferAfterMinutes,
            Integer slotIntervalMinutes,
            Integer minNoticeMinutes,
            Integer maxAdvanceDays,
            Integer holdDurationMinutes,
            String slug
    ) {
        this(name, description, location, durationMinutes, bufferBeforeMinutes, bufferAfterMinutes,
                slotIntervalMinutes, minNoticeMinutes, maxAdvanceDays, holdDurationMinutes,
                slug, null, null);
    }
}
