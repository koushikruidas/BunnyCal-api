package com.daedalussystems.easySchedule.booking.notification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IcsInviteGenerator {

    private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private final String uidDomain;

    public IcsInviteGenerator(@Value("${booking.notifications.uid-domain:easyschedule.local}") String uidDomain) {
        this.uidDomain = uidDomain;
    }

    public String buildStandaloneRequest(UUID bookingId,
                                         String summary,
                                         String description,
                                         Instant start,
                                         Instant end,
                                         String organizerName,
                                         String organizerEmail,
                                         String hostName,
                                         String hostEmail,
                                         String guestName,
                                         String guestEmail,
                                         int sequence) {
        List<Participant> attendees = buildAttendees(hostName, hostEmail, guestName, guestEmail, organizerEmail);
        return build("REQUEST", bookingId, summary, description, start, end, organizerName, organizerEmail, attendees, sequence, true);
    }

    public String buildStandaloneCancel(UUID bookingId,
                                        String summary,
                                        String description,
                                        Instant start,
                                        Instant end,
                                        String organizerName,
                                        String organizerEmail,
                                        String hostName,
                                        String hostEmail,
                                        String guestName,
                                        String guestEmail,
                                        int sequence) {
        List<Participant> attendees = buildAttendees(hostName, hostEmail, guestName, guestEmail, organizerEmail);
        return build("CANCEL", bookingId, summary, description, start, end, organizerName, organizerEmail, attendees, sequence, true);
    }

    public String buildConnectedSnapshot(UUID bookingId,
                                         String summary,
                                         String description,
                                         Instant start,
                                         Instant end,
                                         String organizerName,
                                         String organizerEmail,
                                         int sequence) {
        return build("PUBLISH", bookingId, summary, description, start, end, organizerName, organizerEmail, List.of(), sequence, false);
    }

    private String build(String method,
                         UUID bookingId,
                         String summary,
                         String description,
                         Instant start,
                         Instant end,
                         String organizerName,
                         String organizerEmail,
                         List<Participant> attendees,
                         int sequence,
                         boolean includeRsvpSemantics) {
        String uid = "booking-" + bookingId + "@" + uidDomain;
        String dtStamp = ICS_TIME.format(Instant.now());
        String dtStart = ICS_TIME.format(start);
        String dtEnd = ICS_TIME.format(end);
        String escapedSummary = escape(summary == null ? "Scheduled Meeting" : summary);
        String escapedDescription = escape(description == null ? "" : description);
        String organizerDisplayName = paramQuote(organizerName == null || organizerName.isBlank() ? "easySchedule" : organizerName.trim());
        String organizer = normalizeEmail(organizerEmail);

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n");
        builder.append("PRODID:-//easySchedule//EN\r\n");
        builder.append("VERSION:2.0\r\n");
        builder.append("CALSCALE:GREGORIAN\r\n");
        builder.append("METHOD:").append(method).append("\r\n");
        builder.append("BEGIN:VEVENT\r\n");
        builder.append("UID:").append(uid).append("\r\n");
        builder.append("DTSTAMP:").append(dtStamp).append("\r\n");
        appendLine(builder, "CREATED:" + dtStamp);
        // LAST-MODIFIED tracks server-side mutation; Outlook/Apple Mail use it to
        // distinguish redundant REQUESTs from real updates and avoid duplicate calendar
        // entries when the same event is sent twice.
        appendLine(builder, "LAST-MODIFIED:" + dtStamp);
        builder.append("DTSTART:").append(dtStart).append("\r\n");
        builder.append("DTEND:").append(dtEnd).append("\r\n");
        appendLine(builder, "SUMMARY:" + escapedSummary);
        appendLine(builder, "DESCRIPTION:" + escapedDescription);
        appendLine(builder, "TRANSP:OPAQUE");
        appendLine(builder, "CLASS:PUBLIC");
        appendLine(builder, "PRIORITY:5");
        // CUTYPE=INDIVIDUAL and ROLE=CHAIR are not strictly required by RFC 5545 but
        // Outlook/Exchange-based clients render the invite more reliably when both
        // sides have explicit roles.
        appendLine(builder, "ORGANIZER;CN=" + organizerDisplayName + ":MAILTO:" + organizer);
        for (Participant attendee : attendees) {
            String attendeeLine = includeRsvpSemantics
                    ? "ATTENDEE;CN=" + attendee.displayName
                      + ";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:MAILTO:" + attendee.email
                    : "ATTENDEE;CN=" + attendee.displayName
                      + ";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT:MAILTO:" + attendee.email;
            appendLine(builder, attendeeLine);
        }
        appendLine(builder, "SEQUENCE:" + Math.max(0, sequence));
        appendLine(builder, "STATUS:" + ("CANCEL".equals(method) ? "CANCELLED" : "CONFIRMED"));
        builder.append("END:VEVENT\r\n");
        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    private static void appendLine(StringBuilder builder, String line) {
        final int max = 73;
        if (line.length() <= max) {
            builder.append(line).append("\r\n");
            return;
        }
        int index = 0;
        builder.append(line, index, max).append("\r\n");
        index = max;
        while (index < line.length()) {
            int next = Math.min(index + max - 1, line.length());
            builder.append(' ').append(line, index, next).append("\r\n");
            index = next;
        }
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            return "no-reply@localhost";
        }
        return value.trim().toLowerCase();
    }

    private static List<Participant> buildAttendees(String hostName,
                                                    String hostEmail,
                                                    String guestName,
                                                    String guestEmail,
                                                    String organizerEmail) {
        Map<String, Participant> deduped = new LinkedHashMap<>();
        addAttendee(deduped, hostName, hostEmail, organizerEmail);
        addAttendee(deduped, guestName, guestEmail, organizerEmail);
        return new ArrayList<>(deduped.values());
    }

    private static void addAttendee(Map<String, Participant> deduped,
                                    String name,
                                    String email,
                                    String organizerEmail) {
        String normalizedEmail = normalizeEmail(email);
        String organizer = normalizeEmail(organizerEmail);
        if (normalizedEmail.equals("no-reply@localhost") || normalizedEmail.equals(organizer)) {
            return;
        }
        String normalizedName = paramQuote(name == null || name.isBlank() ? normalizedEmail : name.trim());
        deduped.put(normalizedEmail.toLowerCase(Locale.ROOT), new Participant(normalizedName, normalizedEmail));
    }

    private record Participant(String displayName, String email) {}

    private static String escape(String value) {
        // Order matters: backslash first, then list separators, then line breaks.
        // CRLF is normalised to a single \n escape so a single CR doesn't leak through.
        return value
                .replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String paramQuote(String value) {
        String v = value == null ? "" : value.trim();
        String escaped = v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
