package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvailabilityPurityArchitectureTest {

    @Test
    void slotEngine_hasNoProviderSpecificBranching() throws Exception {
        String slotEngine = Files.readString(Path.of("src/main/java/io/bunnycal/availability/engine/SlotGenerationEngine.java"));
        assertFalse(slotEngine.contains("GOOGLE"));
        assertFalse(slotEngine.contains("MICROSOFT"));
        assertFalse(slotEngine.contains("provider"));
    }

    @Test
    void slotService_consumesCanonicalBusyIntervals() throws Exception {
        String slotService = Files.readString(Path.of("src/main/java/io/bunnycal/availability/service/SlotService.java"));
        assertTrue(slotService.contains("busyIntervalsForDateCanonical"));
        assertTrue(slotService.contains("BusyInterval"));
    }

    @Test
    void everyCalendarAvailabilityConsumer_routesThroughTheCanonicalBusyService() throws Exception {
        String slotService = Files.readString(
                Path.of("src/main/java/io/bunnycal/availability/service/SlotService.java"));
        String participantService = Files.readString(
                Path.of("src/main/java/io/bunnycal/availability/service/ParticipantAvailabilityService.java"));
        String publicBookingService = Files.readString(
                Path.of("src/main/java/io/bunnycal/booking/service/PublicBookingService.java"));
        String availabilityController = Files.readString(
                Path.of("src/main/java/io/bunnycal/calendar/controller/CalendarEventsController.java"));
        String eventRepository = Files.readString(
                Path.of("src/main/java/io/bunnycal/calendar/repository/CalendarEventRepository.java"));

        assertTrue(slotService.contains("calendarBusyTimeService.busyIntervalsForDateCanonical"));
        assertTrue(participantService.contains("calendarBusyTimeService.busyIntervalsForDateCanonical"));
        assertTrue(publicBookingService.contains("calendarBusyTimeService.hasBusyConflict"));
        assertTrue(availabilityController.contains("calendarBusyTimeService.busyEvents"));
        assertFalse(availabilityController.contains("CalendarEventRepository"));
        assertFalse(eventRepository.contains("findDisplayEventsOnPrimaryCalendars"));
        assertFalse(eventRepository.contains("findBusySelected"));
    }
}
