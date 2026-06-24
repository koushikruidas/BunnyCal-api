package io.bunnycal.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.booking.dto.BookingDetailResponse;
import io.bunnycal.booking.dto.MeetingSummaryResponse;
import io.bunnycal.embed.public_.BookingQuestionAnswerRepository;
import io.bunnycal.booking.repository.BookingRepository;
import io.bunnycal.common.time.TimeSource;
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
    private final BookingQuestionAnswerRepository bookingQuestionAnswerRepository;
    private final BookingSubmissionFormatter bookingSubmissionFormatter;
    private final TimeSource timeSource;

    public MeetingQueryService(BookingRepository bookingRepository,
                               BookingQuestionAnswerRepository bookingQuestionAnswerRepository,
                               BookingSubmissionFormatter bookingSubmissionFormatter,
                               TimeSource timeSource) {
        this.bookingRepository = bookingRepository;
        this.bookingQuestionAnswerRepository = bookingQuestionAnswerRepository;
        this.bookingSubmissionFormatter = bookingSubmissionFormatter;
        this.timeSource = timeSource;
    }

    public MeetingQueryService(BookingRepository bookingRepository,
                               TimeSource timeSource) {
        this(bookingRepository, null, new BookingSubmissionFormatter(new ObjectMapper()), timeSource);
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

    @Transactional(readOnly = true)
    public BookingDetailResponse getHostMeetingDetail(UUID hostId, UUID bookingId) {
        Booking booking = bookingRepository.findAnyByIdAndHostId(bookingId, hostId)
                .orElseThrow(() -> new io.bunnycal.common.exception.CustomException(
                        io.bunnycal.common.enums.ErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found."));
        BookingRepository.MeetingRow row = bookingRepository
                .findManageRow(bookingId, hostId, booking.getEventTypeId())
                .orElseThrow(() -> new io.bunnycal.common.exception.CustomException(
                        io.bunnycal.common.enums.ErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found."));
        var answers = bookingQuestionAnswerRepository == null
                ? List.<io.bunnycal.embed.public_.BookingQuestionAnswer>of()
                : bookingQuestionAnswerRepository.findByBookingIdAndHostId(bookingId, hostId);
        var responses = bookingSubmissionFormatter.toResponses(answers);
        String conferenceUrl = row.getConferenceUrl();
        String provider = row.getProvider();
        io.bunnycal.booking.dto.ConferenceDetailsResponse conferenceDetails = conferenceUrl == null || conferenceUrl.isBlank()
                ? io.bunnycal.booking.dto.ConferenceDetailsResponse.none()
                : new io.bunnycal.booking.dto.ConferenceDetailsResponse(
                        provider == null ? "UNKNOWN" : provider.toUpperCase(java.util.Locale.ROOT),
                        conferenceUrl, null, null, null, "projection");
        return new BookingDetailResponse(
                row.getBookingId(),
                row.getEventTypeId(),
                row.getEventTypeName(),
                row.getStartTime(),
                row.getEndTime(),
                row.getBookingStatus(),
                row.getGuestEmail(),
                row.getGuestName(),
                booking.getGuestNotes(),
                responses,
                provider,
                row.getCalendarSyncStatus(),
                row.getExternalEventId(),
                row.getProviderEventUrl(),
                conferenceDetails,
                row.getExternalLifecycleState(),
                row.getExternalLifecycleReason(),
                Boolean.TRUE.equals(row.getReconcileSuppressed()),
                "EXTERNAL_ACTION_REQUIRED".equals(row.getExternalLifecycleState())
                        || "PROVIDER_STATE_ORPHANED".equals(row.getExternalLifecycleState())
        );
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
        String conferenceUrl = row.getConferenceUrl();
        String provider = row.getProvider();
        io.bunnycal.booking.dto.ConferenceDetailsResponse conferenceDetails = conferenceUrl == null || conferenceUrl.isBlank()
                ? io.bunnycal.booking.dto.ConferenceDetailsResponse.none()
                : new io.bunnycal.booking.dto.ConferenceDetailsResponse(
                        provider == null ? "UNKNOWN" : provider.toUpperCase(java.util.Locale.ROOT),
                        conferenceUrl, null, null, null, "projection");
        MeetingSummaryResponse response = new MeetingSummaryResponse(
                row.getBookingId(),
                row.getEventTypeId(),
                row.getEventTypeName(),
                row.getStartTime(),
                row.getEndTime(),
                row.getBookingStatus(),
                row.getGuestEmail(),
                row.getGuestName(),
                provider,
                row.getCalendarSyncStatus(),
                row.getExternalEventId(),
                row.getProviderEventUrl(),
                conferenceDetails,
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
