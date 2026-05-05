package com.daedalussystems.easySchedule.availability.dto;

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
        String slug
) {
}
