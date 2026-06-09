package io.bunnycal.availability.repository;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/** Spring Data projection used by {@link GroupEventReservationWindowRepository#findAllWindowsWithEventNameByHost}. */
public interface GroupReservationBlockerView {
    UUID getWindowId();
    UUID getEventTypeId();
    String getEventTypeName();
    DayOfWeek getDayOfWeek();
    LocalTime getStartTime();
    LocalTime getEndTime();
}
