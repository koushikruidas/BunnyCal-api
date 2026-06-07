package io.bunnycal.booking.notification;

import io.bunnycal.conferencing.service.ConferenceDetails;
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

    public IcsInviteGenerator(@Value("${booking.notifications.uid-domain:BunnyCal.local}") String uidDomain) {
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
                                         int sequence,
                                         ConferenceDetails conferenceDetails) {
        List<Participant> attendees = buildAttendees(hostName, hostEmail, guestName, guestEmail, organizerEmail);
        return build("REQUEST", bookingId, summary, description, start, end, organizerName, organizerEmail,
                attendees, sequence, true, conferenceDetails);
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
                                        int sequence,
                                        ConferenceDetails conferenceDetails) {
        List<Participant> attendees = buildAttendees(hostName, hostEmail, guestName, guestEmail, organizerEmail);
        return build("CANCEL", bookingId, summary, description, start, end, organizerName, organizerEmail,
                attendees, sequence, true, conferenceDetails);
    }


    public String buildGroupRequest(UUID sessionId,
                                     String summary,
                                     String description,
                                     Instant start,
                                     Instant end,
                                     String organizerName,
                                     String organizerEmail,
                                     List<GroupAttendee> attendees,
                                     int sequence,
                                     ConferenceDetails conferenceDetails) {
        List<Participant> participants = buildGroupAttendees(attendees, organizerEmail);
        return build("REQUEST", sessionId, summary, description, start, end, organizerName, organizerEmail,
                participants, sequence, true, conferenceDetails);
    }

    public String buildGroupCancel(UUID sessionId,
                                    String summary,
                                    String description,
                                    Instant start,
                                    Instant end,
                                    String organizerName,
                                    String organizerEmail,
                                    List<GroupAttendee> attendees,
                                    int sequence,
                                    ConferenceDetails conferenceDetails) {
        List<Participant> participants = buildGroupAttendees(attendees, organizerEmail);
        return build("CANCEL", sessionId, summary, description, start, end, organizerName, organizerEmail,
                participants, sequence, true, conferenceDetails);
    }

    public record GroupAttendee(String name, String email) {}

    private static List<Participant> buildGroupAttendees(List<GroupAttendee> attendees, String organizerEmail) {
        Map<String, Participant> deduped = new LinkedHashMap<>();
        if (attendees != null) {
            for (GroupAttendee a : attendees) {
                addAttendee(deduped, a.name(), a.email(), organizerEmail, ParticipantRole.GUEST);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    public String buildConnectedSnapshot(UUID bookingId,
                                         String summary,
                                         String description,
                                         Instant start,
                                         Instant end,
                                         String organizerName,
                                         String organizerEmail,
                                         int sequence) {
        return build("PUBLISH", bookingId, summary, description, start, end, organizerName, organizerEmail,
                List.of(), sequence, false, null);
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
                         boolean includeRsvpSemantics,
                         ConferenceDetails conferenceDetails) {
        String uid = "booking-" + bookingId + "@" + uidDomain;
        String dtStamp = ICS_TIME.format(Instant.now());
        String dtStart = ICS_TIME.format(start);
        String dtEnd = ICS_TIME.format(end);
        String escapedSummary = escape(summary == null ? "Scheduled Meeting" : summary);
        String trimmedJoinUrl = conferenceDetails == null || conferenceDetails.joinUrl() == null
                ? ""
                : conferenceDetails.joinUrl().trim();
        String descriptionWithJoin = trimmedJoinUrl.isEmpty()
                ? (description == null ? "" : description)
                : ((description == null || description.isBlank()
                        ? "Join the meeting"
                        : description) + "\n\nJoin: " + trimmedJoinUrl);
        String escapedDescription = escape(descriptionWithJoin);
        String organizerDisplayName = escape(organizerName == null || organizerName.isBlank() ? "BunnyCal" : organizerName.trim());
        String organizer = normalizeEmail(organizerEmail);

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n");
        builder.append("PRODID:-//BunnyCal//EN\r\n");
        builder.append("VERSION:2.0\r\n");
        builder.append("CALSCALE:GREGORIAN\r\n");
        builder.append("METHOD:").append(method).append("\r\n");
        builder.append("BEGIN:VEVENT\r\n");
        builder.append("UID:").append(uid).append("\r\n");
        builder.append("DTSTAMP:").append(dtStamp).append("\r\n");
        builder.append("DTSTART:").append(dtStart).append("\r\n");
        builder.append("DTEND:").append(dtEnd).append("\r\n");
        appendLine(builder, "SUMMARY:" + escapedSummary);
        appendLine(builder, "DESCRIPTION:" + escapedDescription);
        if (!trimmedJoinUrl.isEmpty()) {
            appendLine(builder, "LOCATION:" + escape(trimmedJoinUrl));
            appendLine(builder, "URL:" + trimmedJoinUrl);
            // Provider-specific conference hints must match the actual meeting provider.
            // Emitting X-MICROSOFT-SKYPETEAMSMEETINGURL with a non-Teams URL, or
            // X-GOOGLE-CONFERENCE with a non-Meet URL, makes Outlook's calendar parser
            // treat the invite as a malformed/foreign online meeting and can suppress
            // the meeting banner. For CUSTOM_URL we surface the link via LOCATION/URL only.
            String provider = conferenceDetails == null || conferenceDetails.provider() == null
                    ? ""
                    : conferenceDetails.provider().trim().toUpperCase(Locale.ROOT);
            if ("MICROSOFT_TEAMS".equals(provider)) {
                appendLine(builder, "X-MICROSOFT-SKYPETEAMSMEETINGURL:" + trimmedJoinUrl);
            } else if ("GOOGLE_MEET".equals(provider)) {
                appendLine(builder, "X-GOOGLE-CONFERENCE:" + trimmedJoinUrl);
            }
        }
        appendLine(builder, "TRANSP:OPAQUE");
        appendLine(builder, "CLASS:PUBLIC");
        appendLine(builder, "PRIORITY:5");
        appendLine(builder, "ORGANIZER;CN=" + organizerDisplayName + ":mailto:" + organizer);
        for (Participant attendee : attendees) {
            String attendeeLine;
            if (!includeRsvpSemantics) {
                attendeeLine = "ATTENDEE;CN=" + attendee.displayName + ":mailto:" + attendee.email;
            } else if (attendee.role == ParticipantRole.HOST) {
                attendeeLine = "ATTENDEE;CN=" + attendee.displayName
                        + ";CUTYPE=INDIVIDUAL;ROLE=CHAIR;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:" + attendee.email;
            } else {
                attendeeLine = "ATTENDEE;CN=" + attendee.displayName
                        + ";CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + attendee.email;
            }
            appendLine(builder, attendeeLine);
        }
        appendLine(builder, "SEQUENCE:" + Math.max(0, sequence));
        appendLine(builder, "STATUS:" + ("CANCEL".equals(method) ? "CANCELLED" : "CONFIRMED"));
        appendLine(builder, "X-MICROSOFT-CDO-BUSYSTATUS:BUSY");
        appendLine(builder, "X-MICROSOFT-CDO-INTENDEDSTATUS:BUSY");
        appendLine(builder, "X-MICROSOFT-DISALLOW-COUNTER:FALSE");
        builder.append("END:VEVENT\r\n");
        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    private static void appendLine(StringBuilder builder, String line) {
        // RFC 5545 §3.1: lines SHOULD NOT exceed 75 octets, excluding the CRLF.
        // Continuation lines begin with a single leading whitespace octet which
        // also counts toward the 75-octet budget, so the payload per continuation
        // is 74 octets.
        final int firstChunk = 75;
        final int contChunk = 74;
        if (line.length() <= firstChunk) {
            builder.append(line).append("\r\n");
            return;
        }
        builder.append(line, 0, firstChunk).append("\r\n");
        int index = firstChunk;
        while (index < line.length()) {
            int end = Math.min(index + contChunk, line.length());
            builder.append(' ').append(line, index, end).append("\r\n");
            index = end;
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
        addAttendee(deduped, hostName, hostEmail, organizerEmail, ParticipantRole.HOST);
        addAttendee(deduped, guestName, guestEmail, organizerEmail, ParticipantRole.GUEST);
        return new ArrayList<>(deduped.values());
    }

    private static void addAttendee(Map<String, Participant> deduped,
                                    String name,
                                    String email,
                                    String organizerEmail,
                                    ParticipantRole role) {
        String normalizedEmail = normalizeEmail(email);
        String organizer = normalizeEmail(organizerEmail);
        if (normalizedEmail.equals("no-reply@localhost") || normalizedEmail.equals(organizer)) {
            return;
        }
        String normalizedName = escape(name == null || name.isBlank() ? normalizedEmail : name.trim());
        deduped.put(normalizedEmail.toLowerCase(Locale.ROOT), new Participant(normalizedName, normalizedEmail, role));
    }

    private enum ParticipantRole { HOST, GUEST }

    private record Participant(String displayName, String email, ParticipantRole role) {}

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\n", "\\n");
    }
}
