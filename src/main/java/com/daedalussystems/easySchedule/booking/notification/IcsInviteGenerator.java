package com.daedalussystems.easySchedule.booking.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IcsInviteGenerator {

    private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    public String buildRequest(UUID bookingId,
                               String summary,
                               String description,
                               Instant start,
                               Instant end,
                               String organizerEmail,
                               String attendeeEmail) {
        return build("REQUEST", bookingId, summary, description, start, end, organizerEmail, attendeeEmail);
    }

    public String buildCancel(UUID bookingId,
                              String summary,
                              String description,
                              Instant start,
                              Instant end,
                              String organizerEmail,
                              String attendeeEmail) {
        return build("CANCEL", bookingId, summary, description, start, end, organizerEmail, attendeeEmail);
    }

    private String build(String method,
                         UUID bookingId,
                         String summary,
                         String description,
                         Instant start,
                         Instant end,
                         String organizerEmail,
                         String attendeeEmail) {
        String uid = bookingId + "@easySchedule";
        String dtStamp = ICS_TIME.format(Instant.now());
        String dtStart = ICS_TIME.format(start);
        String dtEnd = ICS_TIME.format(end);
        String escapedSummary = escape(summary == null ? "Scheduled Meeting" : summary);
        String escapedDescription = escape(description == null ? "" : description);
        String organizer = normalizeEmail(organizerEmail);
        String attendee = normalizeEmail(attendeeEmail);

        return "BEGIN:VCALENDAR\r\n"
                + "PRODID:-//easySchedule//EN\r\n"
                + "VERSION:2.0\r\n"
                + "CALSCALE:GREGORIAN\r\n"
                + "METHOD:" + method + "\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "DTSTAMP:" + dtStamp + "\r\n"
                + "DTSTART:" + dtStart + "\r\n"
                + "DTEND:" + dtEnd + "\r\n"
                + "SUMMARY:" + escapedSummary + "\r\n"
                + "DESCRIPTION:" + escapedDescription + "\r\n"
                + "ORGANIZER:mailto:" + organizer + "\r\n"
                + "ATTENDEE;CN=" + escape(attendee) + ":mailto:" + attendee + "\r\n"
                + "SEQUENCE:0\r\n"
                + "STATUS:" + ("CANCEL".equals(method) ? "CANCELLED" : "CONFIRMED") + "\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            return "no-reply@localhost";
        }
        return value.trim().toLowerCase();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\n", "\\n");
    }
}
