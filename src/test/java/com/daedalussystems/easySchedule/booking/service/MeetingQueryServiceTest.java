package com.daedalussystems.easySchedule.booking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.time.TimeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingQueryServiceTest {
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private TimeSource timeSource;
    @Mock
    private BookingRepository.MeetingRow meetingRow;

    private MeetingQueryService service;

    @BeforeEach
    void setUp() {
        service = new MeetingQueryService(bookingRepository, timeSource);
    }

    @Test
    void upcomingOnly_usesUpcomingQuery() {
        UUID hostId = UUID.randomUUID();
        Instant now = Instant.parse("2026-05-09T12:00:00Z");
        when(timeSource.now()).thenReturn(now);
        when(bookingRepository.findUpcomingMeetingsForHost(hostId, now, 50)).thenReturn(List.of());

        service.listHostMeetings(hostId, true, null);

        verify(bookingRepository).findUpcomingMeetingsForHost(hostId, now, 50);
    }

    @Test
    void nonUpcoming_usesGeneralQuery() {
        UUID hostId = UUID.randomUUID();
        when(bookingRepository.findMeetingsForHost(hostId, 25)).thenReturn(List.of());

        service.listHostMeetings(hostId, false, 25);

        verify(bookingRepository).findMeetingsForHost(hostId, 25);
    }

    @Test
    void mapsBookingStatusFromRow() {
        UUID hostId = UUID.randomUUID();
        when(bookingRepository.findMeetingsForHost(hostId, 10)).thenReturn(List.of(meetingRow));
        when(meetingRow.getBookingId()).thenReturn(UUID.randomUUID());
        when(meetingRow.getEventTypeId()).thenReturn(UUID.randomUUID());
        when(meetingRow.getEventTypeName()).thenReturn("30 min Intro");
        when(meetingRow.getStartTime()).thenReturn(Instant.parse("2026-05-10T09:00:00Z"));
        when(meetingRow.getEndTime()).thenReturn(Instant.parse("2026-05-10T09:30:00Z"));
        when(meetingRow.getBookingStatus()).thenReturn("CONFIRMED");
        when(meetingRow.getGuestEmail()).thenReturn("guest@example.com");
        when(meetingRow.getGuestName()).thenReturn("Guest");
        when(meetingRow.getProvider()).thenReturn("google");
        when(meetingRow.getCalendarSyncStatus()).thenReturn("CREATED");
        when(meetingRow.getExternalEventId()).thenReturn("evt-1");
        when(meetingRow.getProviderEventUrl()).thenReturn("https://calendar.google.com/event?eid=1");
        when(meetingRow.getConferenceUrl()).thenReturn("https://meet.google.com/abc-defg-hij");

        var result = service.listHostMeetings(hostId, false, 10);

        assertEquals(1, result.size());
        assertEquals("CONFIRMED", result.get(0).bookingStatus());
        assertEquals("google", result.get(0).provider());
        assertEquals("CREATED", result.get(0).calendarSyncStatus());
        assertEquals("https://calendar.google.com/event?eid=1", result.get(0).providerEventUrl());
        assertEquals("https://meet.google.com/abc-defg-hij", result.get(0).conferenceUrl());
    }
}
