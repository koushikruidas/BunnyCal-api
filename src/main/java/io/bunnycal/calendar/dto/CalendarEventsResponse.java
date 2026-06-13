package io.bunnycal.calendar.dto;

import java.util.List;

public record CalendarEventsResponse(List<CalendarEventDto> events) {}
