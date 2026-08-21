package io.bunnycal.session.notification;

import io.bunnycal.conferencing.service.ConferenceDetails;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One session notification described semantically, so the HTML body can render detail rows and
 * real buttons instead of a wall of pre-formatted text.
 *
 * <p>Group emails used to hand the whole plain-text body to {@code EmailTemplate.preformatted},
 * which is fine for a guest list but turns "Join the meeting:\n&lt;url&gt;" into a bare URL printed
 * in a monospace block — no button, unlike the 1:1 emails that {@code BookingNotificationService}
 * builds with {@code detail(...)} and {@code primaryAction(...)}.
 *
 * <p>The plain-text body stays authoritative and unchanged: it is the {@code text/plain} part of
 * the {@code multipart/alternative} that carries the calendar invite, and its exact shape is what
 * makes Outlook render the invite. This record travels beside that text, never instead of it, so
 * the two bodies always describe the same message.
 */
record SessionEmailContent(
        String eyebrow,
        String intro,
        Instant startTime,
        Instant endTime,
        String timezone,
        ConferenceDetails conferenceDetails,
        String manageLink,
        String notes,
        /** Free-form block whose line structure carries meaning — the attendee list. */
        String preformatted,
        Map<String, String> details,
        boolean cancelled) {

    static Builder builder(String eyebrow, String intro) {
        return new Builder(eyebrow, intro);
    }

    static final class Builder {
        private final String eyebrow;
        private final String intro;
        private Instant startTime;
        private Instant endTime;
        private String timezone;
        private ConferenceDetails conferenceDetails;
        private String manageLink;
        private String notes;
        private String preformatted;
        private final Map<String, String> details = new LinkedHashMap<>();
        private boolean cancelled;

        private Builder(String eyebrow, String intro) {
            this.eyebrow = eyebrow;
            this.intro = intro;
        }

        Builder when(Instant start, Instant end, String timezone) {
            this.startTime = start;
            this.endTime = end;
            this.timezone = timezone;
            return this;
        }

        Builder conference(ConferenceDetails value) {
            this.conferenceDetails = value;
            return this;
        }

        Builder manageLink(String value) {
            this.manageLink = value;
            return this;
        }

        Builder notes(String value) {
            this.notes = value;
            return this;
        }

        Builder preformatted(String value) {
            this.preformatted = value;
            return this;
        }

        Builder detail(String label, String value) {
            if (label != null && value != null && !value.isBlank()) {
                details.put(label, value);
            }
            return this;
        }

        /** A cancellation shows no join or manage button: there is nothing left to act on. */
        Builder cancelled(boolean value) {
            this.cancelled = value;
            return this;
        }

        SessionEmailContent build() {
            return new SessionEmailContent(eyebrow, intro, startTime, endTime, timezone,
                    conferenceDetails, manageLink, notes, preformatted,
                    Map.copyOf(details), cancelled);
        }
    }
}
