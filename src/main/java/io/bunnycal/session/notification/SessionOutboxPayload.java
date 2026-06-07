package io.bunnycal.session.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed view over the raw JSON payload stored in outbox_events for Session aggregate events.
 */
record SessionOutboxPayload(
        UUID sessionId,
        UUID registrationId,
        UUID hostId,
        String hostUsername,
        String eventName,
        String eventSlug,
        Instant startTime,
        Instant endTime,
        int calendarSequence,

        // REGISTRATION_CONFIRMED
        String newAttendeeEmail,
        String newAttendeeName,
        String capabilityToken,
        List<AttendeeDto> allConfirmedAttendees,

        // REGISTRATION_CANCELLED
        String cancelledAttendeeEmail,
        String cancelledAttendeeName,

        // SESSION_CANCELLED / SESSION_RESCHEDULED
        List<AttendeeDto> allAttendees
) {
    record AttendeeDto(String email, String name) {}

    @SuppressWarnings("unchecked")
    static SessionOutboxPayload from(String eventType, Map<String, Object> data) {
        UUID sessionId = uuid(data, "sessionId");
        UUID registrationId = uuid(data, "registrationId");
        UUID hostId = uuid(data, "hostId");
        String hostUsername = str(data, "hostUsername");
        String eventName = str(data, "eventName");
        String eventSlug = str(data, "eventSlug");
        Instant startTime = instant(data, "startTime");
        Instant endTime = instant(data, "endTime");
        int calendarSequence = intVal(data, "calendarSequence");

        String newAttendeeEmail = str(data, "guestEmail");
        String newAttendeeName = str(data, "guestName");
        String capabilityToken = str(data, "capabilityToken");

        List<AttendeeDto> allConfirmed = attendeeList(data, "allConfirmedAttendees");
        List<AttendeeDto> allAttendees = attendeeList(data, "attendees");
        if (allAttendees.isEmpty()) {
            allAttendees = attendeeList(data, "allAttendees");
        }

        String cancelledEmail = null;
        String cancelledName = null;
        if ("REGISTRATION_CANCELLED".equals(eventType)) {
            cancelledEmail = newAttendeeEmail;
            cancelledName = newAttendeeName;
            newAttendeeEmail = null;
            newAttendeeName = null;
        }

        return new SessionOutboxPayload(sessionId, registrationId, hostId,
                hostUsername, eventName, eventSlug, startTime, endTime, calendarSequence,
                newAttendeeEmail, newAttendeeName, capabilityToken, allConfirmed,
                cancelledEmail, cancelledName, allAttendees);
    }

    private static UUID uuid(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    private static Instant instant(Map<String, Object> data, String key) {
        String s = str(data, key);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int intVal(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return 0; }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<AttendeeDto> attendeeList(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (!(v instanceof List<?> list)) return new ArrayList<>();
        List<AttendeeDto> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String email = obj(m, "email");
            String name = obj(m, "name");
            if (email != null && !email.isBlank()) {
                result.add(new AttendeeDto(email, name));
            }
        }
        return result;
    }

    private static String obj(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }
}
