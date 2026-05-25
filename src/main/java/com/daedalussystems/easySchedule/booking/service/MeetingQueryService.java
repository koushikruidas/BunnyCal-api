package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.booking.dto.MeetingSummaryResponse;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.time.TimeSource;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingQueryService {
    private static final Logger log = LoggerFactory.getLogger(MeetingQueryService.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final BookingRepository bookingRepository;
    private final TimeSource timeSource;

    public MeetingQueryService(BookingRepository bookingRepository,
                               TimeSource timeSource) {
        this.bookingRepository = bookingRepository;
        this.timeSource = timeSource;
    }

    @Transactional(readOnly = true)
    public List<MeetingSummaryResponse> listHostMeetings(UUID hostId, Boolean upcomingOnly, Integer limit) {
        int safeLimit = sanitizeLimit(limit);
        List<BookingRepository.MeetingRow> rows = Boolean.TRUE.equals(upcomingOnly)
                ? bookingRepository.findUpcomingMeetingsForHost(hostId, timeSource.now(), safeLimit)
                : bookingRepository.findMeetingsForHost(hostId, safeLimit);
        return rows.stream()
                .map(this::toDto)
                .toList();
    }

    private static int sanitizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private MeetingSummaryResponse toDto(BookingRepository.MeetingRow row) {
        MeetingSummaryResponse response = new MeetingSummaryResponse(
                row.getBookingId(),
                row.getEventTypeId(),
                row.getEventTypeName(),
                row.getStartTime(),
                row.getEndTime(),
                row.getBookingStatus(),
                row.getGuestEmail(),
                row.getGuestName(),
                row.getProvider(),
                row.getCalendarSyncStatus(),
                row.getExternalEventId(),
                row.getProviderEventUrl(),
                row.getConferenceUrl(),
                row.getExternalLifecycleState(),
                row.getExternalLifecycleReason(),
                Boolean.TRUE.equals(row.getReconcileSuppressed()),
                "EXTERNAL_ACTION_REQUIRED".equals(row.getExternalLifecycleState())
                        || "PROVIDER_STATE_ORPHANED".equals(row.getExternalLifecycleState())
        );
        log.info("dashboard_meeting_lifecycle bookingId={} provider={} calendarSyncStatus={} externalLifecycleState={} externalLifecycleReason={} reconcileSuppressed={} actionRequired={}",
                response.bookingId(),
                response.provider(),
                response.calendarSyncStatus(),
                response.externalLifecycleState(),
                response.externalLifecycleReason(),
                response.reconcileSuppressed(),
                response.actionRequired());
        return response;
    }
}
