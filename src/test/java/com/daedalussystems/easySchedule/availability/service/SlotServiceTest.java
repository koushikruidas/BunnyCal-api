package com.daedalussystems.easySchedule.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.CachedSlots;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheService.ComputeOutcome;
import com.daedalussystems.easySchedule.availability.cache.SlotCacheVersionService;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.dto.SlotDto;
import com.daedalussystems.easySchedule.availability.dto.SlotRequest;
import com.daedalussystems.easySchedule.availability.dto.SlotResponse;
import com.daedalussystems.easySchedule.availability.engine.SlotGenerationEngine;
import com.daedalussystems.easySchedule.availability.identity.SlotIdGenerator;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityOverrideRepository;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityRuleRepository;
import com.daedalussystems.easySchedule.availability.repository.DbClockRepository;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SlotServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private AvailabilityRuleRepository availabilityRuleRepository;
    @Mock private AvailabilityOverrideRepository availabilityOverrideRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private DbClockRepository dbClockRepository;
    @Mock private SlotCacheService slotCacheService;
    @Mock private SlotCacheVersionService slotCacheVersionService;

    private SlotService slotService;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID eventTypeId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final LocalDate date = LocalDate.of(2026, 5, 4); // Monday

    private User host;
    private EventType eventType;
    private AvailabilityRule mondayRule;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        slotService = new SlotService(
                userRepository,
                eventTypeRepository,
                availabilityRuleRepository,
                availabilityOverrideRepository,
                bookingRepository,
                dbClockRepository,
                slotCacheService,
                slotCacheVersionService);

        host = User.builder()
                .id(userId)
                .email("u@e.com")
                .name("U")
                .timezone("UTC")
                .build();

        eventType = EventType.builder()
                .id(eventTypeId)
                .userId(userId)
                .name("30-min")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .build();

        mondayRule = new AvailabilityRule();
        mondayRule.setId(UUID.randomUUID());
        mondayRule.setUserId(userId);
        mondayRule.setDayOfWeek(DayOfWeek.MONDAY);
        mondayRule.setStartTime(LocalTime.of(9, 0));
        mondayRule.setEndTime(LocalTime.of(10, 0));
    }

    @Test
    void missingUser_throwsResourceNotFound_andSkipsAllDownstreamWork() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(userId, eventTypeId, date)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());

        verify(eventTypeRepository, never()).findByIdAndUserId(any(), any());
        verify(slotCacheVersionService, never()).getCurrentVersion(any());
        verify(slotCacheService, never()).getOrCompute(any(), any(), any(), any(Long.class), any());
        verify(dbClockRepository, never()).now();
    }

    @Test
    void missingEventType_throwsResourceNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(userId, eventTypeId, date)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());

        verify(slotCacheVersionService, never()).getCurrentVersion(any());
        verify(slotCacheService, never()).getOrCompute(any(), any(), any(), any(Long.class), any());
    }

    @Test
    void cacheHit_returnsCachedSlots_andDbClockIsNotCalled() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(7L);

        Instant cachedGeneratedAt = Instant.parse("2026-05-04T08:00:00Z");
        Instant slotStart = Instant.parse("2026-05-04T09:00:00Z");
        Instant slotEnd = Instant.parse("2026-05-04T09:30:00Z");
        List<SlotGenerationEngine.SlotUtc> cachedSlotList =
                List.of(new SlotGenerationEngine.SlotUtc(slotStart, slotEnd));

        // Simulate cache hit: never invoke the supplier.
        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(7L), any()))
                .thenReturn(new CachedSlots(cachedSlotList, cachedGeneratedAt));

        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        assertEquals(7L, response.version());
        assertEquals(cachedGeneratedAt, response.generatedAt());
        assertEquals(1, response.slots().size());
        assertEquals(slotStart, response.slots().get(0).start());
        assertEquals(slotEnd, response.slots().get(0).end());
        assertEquals(host.getTimezone(), response.timezone());
        assertFalse(response.degraded());

        // Fix #2: DB clock must NOT be invoked on cache hit.
        verify(dbClockRepository, never()).now();
        // Fix #1: with cache hit, supplier never runs, so no second version read.
        verify(slotCacheVersionService, times(1)).getCurrentVersion(userId);
        // No DB fetches on cache hit.
        verify(availabilityRuleRepository, never()).findByUserIdOrderByDayOfWeekAscStartTimeAsc(any());
        verify(availabilityOverrideRepository, never()).findByUserIdAndDate(any(), any());
        verify(bookingRepository, never())
                .findActiveOverlappingBookings(any(), any(), any());
    }

    @Test
    void cacheMiss_runsSupplier_readsDbClockOnce_reChecksVersion_marksCacheable_andStampsSlotIdsWithV1() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        // Same version V1 read both before and after the data fetch → cacheable=true.
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(7L);

        Instant dbNow = Instant.parse("2026-05-04T00:00:00Z");
        when(dbClockRepository.now()).thenReturn(dbNow);
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule));
        when(availabilityOverrideRepository.findByUserIdAndDate(userId, date))
                .thenReturn(Optional.empty());
        when(bookingRepository.findActiveOverlappingBookings(
                eq(userId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        // Capture the supplier the service passes to the cache, then invoke it
        // ourselves (simulating cache miss path).
        AtomicReference<ComputeOutcome> capturedOutcome = new AtomicReference<>();
        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(7L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get();
                    capturedOutcome.set(outcome);
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        // Fix #2: DB clock invoked exactly once, and only inside the supplier.
        verify(dbClockRepository, times(1)).now();
        // Fix #1: version read at least twice — once before cache call (V1), once after data fetch (V2).
        verify(slotCacheVersionService, atLeastOnce()).getCurrentVersion(userId);
        // Engine output: rule MONDAY 09:00-10:00 + 30-min duration + 30-min interval → 2 slots.
        assertEquals(2, response.slots().size());
        assertEquals(Instant.parse("2026-05-04T09:00:00Z"), response.slots().get(0).start());
        assertEquals(Instant.parse("2026-05-04T09:30:00Z"), response.slots().get(0).end());
        assertEquals(Instant.parse("2026-05-04T09:30:00Z"), response.slots().get(1).start());
        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), response.slots().get(1).end());
        assertEquals(7L, response.version());
        assertEquals(dbNow, response.generatedAt());
        assertTrue(capturedOutcome.get().cacheable(),
                "V1 == V2 → outcome must be cacheable");

        // Fix: slotIds use V1 (the snapshot read at step 4).
        for (int i = 0; i < response.slots().size(); i++) {
            SlotDto dto = response.slots().get(i);
            String expected = SlotIdGenerator.generate(
                    userId, eventTypeId, dto.start(), dto.end(), 7L);
            assertEquals(expected, dto.slotId());
        }
    }

    @Test
    void versionDriftDuringFetch_resultsInNonCacheableOutcome_butSlotsStillReturned_withV1SlotIds() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        // V1 = 7 on the first read (snapshot). V2 = 8 on the post-fetch re-check.
        when(slotCacheVersionService.getCurrentVersion(userId))
                .thenReturn(7L)  // step 4: snapshot
                .thenReturn(8L); // step 6.9: re-check after data fetch

        when(dbClockRepository.now()).thenReturn(Instant.parse("2026-05-04T00:00:00Z"));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule));
        when(availabilityOverrideRepository.findByUserIdAndDate(userId, date))
                .thenReturn(Optional.empty());
        when(bookingRepository.findActiveOverlappingBookings(
                eq(userId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        AtomicReference<ComputeOutcome> capturedOutcome = new AtomicReference<>();
        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(7L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get();
                    capturedOutcome.set(outcome);
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        // Fix #1: drift → cacheable=false.
        assertFalse(capturedOutcome.get().cacheable(),
                "V1 != V2 → outcome must be non-cacheable");
        // Result is still returned to the caller.
        assertEquals(2, response.slots().size());
        // Response version is V1 (the snapshot we anchored on).
        assertEquals(7L, response.version());
        // SlotIds use V1, not V2 — see plan's "NOTE FOR FUTURE MAINTAINERS".
        for (SlotDto dto : response.slots()) {
            String expectedV1 = SlotIdGenerator.generate(userId, eventTypeId, dto.start(), dto.end(), 7L);
            String wouldBeV2 = SlotIdGenerator.generate(userId, eventTypeId, dto.start(), dto.end(), 8L);
            assertEquals(expectedV1, dto.slotId());
            // Sanity: V2 stamping would have produced a different ID.
            assertFalse(dto.slotId().equals(wouldBeV2));
        }
    }

    @Test
    void versionIsReadBefore_anyDataFetch_orCacheCall() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(1L);
        when(dbClockRepository.now()).thenReturn(Instant.parse("2026-05-04T00:00:00Z"));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule));
        when(availabilityOverrideRepository.findByUserIdAndDate(userId, date)).thenReturn(Optional.empty());
        when(bookingRepository.findActiveOverlappingBookings(
                eq(userId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(1L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get();
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        // The first version read MUST happen before the cache call. After cache call
        // the supplier runs (also reads version, fetches data). So the cache call
        // sits between the two version reads. We assert the first version read is
        // before the cache call and before any data fetch.
        InOrder order = inOrder(
                slotCacheVersionService,
                slotCacheService,
                dbClockRepository,
                availabilityRuleRepository,
                availabilityOverrideRepository,
                bookingRepository);
        order.verify(slotCacheVersionService).getCurrentVersion(userId);
        order.verify(slotCacheService).getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(1L), any());
        order.verify(dbClockRepository).now();
        order.verify(availabilityRuleRepository).findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId);
        order.verify(availabilityOverrideRepository).findByUserIdAndDate(userId, date);
        order.verify(bookingRepository).findActiveOverlappingBookings(
                eq(userId), any(Instant.class), any(Instant.class));
        // After data fetch, version is read again.
        order.verify(slotCacheVersionService).getCurrentVersion(userId);
    }

    @Test
    void serviceDoesNoFiltering_passesEngineOutputThroughUnchanged() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(1L);
        when(dbClockRepository.now()).thenReturn(Instant.parse("2026-05-04T00:00:00Z"));

        // Use a wider rule (09:00-12:00) so the engine produces 6 slots.
        AvailabilityRule wideRule = new AvailabilityRule();
        wideRule.setId(UUID.randomUUID());
        wideRule.setUserId(userId);
        wideRule.setDayOfWeek(DayOfWeek.MONDAY);
        wideRule.setStartTime(LocalTime.of(9, 0));
        wideRule.setEndTime(LocalTime.of(12, 0));

        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(wideRule));
        when(availabilityOverrideRepository.findByUserIdAndDate(userId, date)).thenReturn(Optional.empty());
        when(bookingRepository.findActiveOverlappingBookings(
                eq(userId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        AtomicReference<ComputeOutcome> capturedOutcome = new AtomicReference<>();
        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(1L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get();
                    capturedOutcome.set(outcome);
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        // Engine produced N slots → service produced exactly N SlotDtos with identical start/end.
        List<SlotGenerationEngine.SlotUtc> engineSlots = capturedOutcome.get().slots();
        assertEquals(engineSlots.size(), response.slots().size(),
                "service must not drop or add slots");
        for (int i = 0; i < engineSlots.size(); i++) {
            assertEquals(engineSlots.get(i).start(), response.slots().get(i).start(),
                    "service must not modify slot start");
            assertEquals(engineSlots.get(i).end(), response.slots().get(i).end(),
                    "service must not modify slot end");
        }
    }

    @Test
    void bookingsArePassedToEngineInUtc_mappedFromBookingStartAndEnd() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(1L);
        when(dbClockRepository.now()).thenReturn(Instant.parse("2026-05-04T00:00:00Z"));
        when(availabilityRuleRepository.findByUserIdOrderByDayOfWeekAscStartTimeAsc(userId))
                .thenReturn(List.of(mondayRule));
        when(availabilityOverrideRepository.findByUserIdAndDate(userId, date)).thenReturn(Optional.empty());

        // Pre-existing booking 09:00-09:30 should remove the first slot, leaving only 09:30-10:00.
        Booking existing = Booking.builder()
                .id(UUID.randomUUID())
                .hostId(userId)
                .eventTypeId(eventTypeId)
                .startTime(Instant.parse("2026-05-04T09:00:00Z"))
                .endTime(Instant.parse("2026-05-04T09:30:00Z"))
                .build();

        // Verify the bookings query uses dayStart/dayEnd derived from the user's timezone.
        // Host timezone is UTC, date is 2026-05-04 → query bounds are [00:00Z, +1day 00:00Z).
        Instant expectedDayStart = Instant.parse("2026-05-04T00:00:00Z");
        Instant expectedDayEnd = Instant.parse("2026-05-05T00:00:00Z");
        when(bookingRepository.findActiveOverlappingBookings(
                eq(userId), eq(expectedDayEnd), eq(expectedDayStart)))
                .thenReturn(List.of(existing));

        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(1L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get();
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        SlotResponse response = slotService.getSlots(new SlotRequest(userId, eventTypeId, date));

        // Booking 09:00-09:30 removes that slot → only 09:30-10:00 remains.
        assertEquals(1, response.slots().size());
        assertEquals(Instant.parse("2026-05-04T09:30:00Z"), response.slots().get(0).start());
        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), response.slots().get(0).end());

        // Verify the booking query was actually invoked with the right UTC bounds.
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository).findActiveOverlappingBookings(
                eq(userId), endCaptor.capture(), startCaptor.capture());
        assertEquals(expectedDayEnd, endCaptor.getValue());
        assertEquals(expectedDayStart, startCaptor.getValue());
    }

    @Test
    void invalidTimezoneOnHost_throwsInvalidTimezone() {
        host.setTimezone("Not/A_Real_Zone");
        when(userRepository.findById(userId)).thenReturn(Optional.of(host));
        when(eventTypeRepository.findByIdAndUserId(eventTypeId, userId)).thenReturn(Optional.of(eventType));
        when(slotCacheVersionService.getCurrentVersion(userId)).thenReturn(1L);
        when(dbClockRepository.now()).thenReturn(Instant.parse("2026-05-04T00:00:00Z"));

        // Invoke supplier on cache miss to surface the timezone parsing error.
        when(slotCacheService.getOrCompute(eq(userId), eq(eventTypeId), eq(date), eq(1L), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<ComputeOutcome> supplier = invocation.getArgument(4);
                    ComputeOutcome outcome = supplier.get(); // will throw INVALID_TIMEZONE
                    return new CachedSlots(outcome.slots(), outcome.generatedAt());
                });

        CustomException ex = assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(userId, eventTypeId, date)));
        assertEquals(ErrorCode.INVALID_TIMEZONE, ex.getErrorCode());
    }

    @Test
    void nullRequestFields_throwValidationError() {
        assertThrows(CustomException.class, () -> slotService.getSlots(null));
        assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(null, eventTypeId, date)));
        assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(userId, null, date)));
        assertThrows(CustomException.class,
                () -> slotService.getSlots(new SlotRequest(userId, eventTypeId, null)));
        // None of these reach the user lookup.
        verify(userRepository, never()).findById(any());
        // And atMost(0) version reads.
        verify(slotCacheVersionService, atMost(0)).getCurrentVersion(any());
    }
}
