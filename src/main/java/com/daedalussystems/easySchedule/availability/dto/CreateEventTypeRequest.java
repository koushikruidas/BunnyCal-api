package com.daedalussystems.easySchedule.availability.dto;

public record CreateEventTypeRequest(
        String name,
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
