package io.bunnycal.common.email;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;

/**
 * Builds the MIME tree for emails that carry an iTIP calendar invite, in either the legacy
 * text-only shape or the branded HTML shape.
 *
 * <p><b>Why the structure matters.</b> A {@code multipart/alternative} holds competing
 * representations of the SAME content, and a client renders the LAST part it understands,
 * discarding the rest. The calendar part therefore cannot simply sit alongside an HTML body:
 *
 * <ul>
 *   <li>{@code alternative(text, html, calendar)} — Gmail may select the calendar and render a
 *       raw {@code BEGIN:VCALENDAR} dump instead of the message.</li>
 *   <li>{@code alternative(text, calendar, html)} — Outlook selects the HTML and stops
 *       auto-rendering the invite, regressing to a manual "Add to calendar" step.</li>
 * </ul>
 *
 * <p>The fix is to stop making them compete. In {@link #buildBranded}, the calendar part becomes a
 * SIBLING of the alternative group rather than a member of it:
 *
 * <pre>
 * multipart/mixed
 * ├── multipart/alternative
 * │   ├── text/plain     (fallback)
 * │   └── text/html      (branded body)
 * └── text/calendar; method=REQUEST   (inline — no Content-Disposition)
 * </pre>
 *
 * <p>Outlook still processes the invite because the part carries
 * {@code Content-Class: urn:content-classes:calendarmessage} and no attachment disposition.
 * This is the shape Google Calendar and other major senders use.
 *
 * <p><b>The invite is carried exactly once.</b> It must never appear both inline and as an
 * {@code invite.ics} attachment with the same UID and method: Outlook honours both and creates
 * one calendar entry per copy. Gmail masks the bug by de-duplicating on UID.
 */
public final class CalendarMimeAssembler {

    private CalendarMimeAssembler() {
    }

    /**
     * Legacy shape: {@code multipart/alternative(text/plain, text/calendar)}.
     *
     * <p>Kept as the default until the branded shape has been confirmed against real Outlook,
     * Gmail and Apple Mail clients.
     */
    public static void buildTextOnly(MimeMessage message,
                                     String textBody,
                                     String ics,
                                     String method) throws Exception {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, StandardCharsets.UTF_8.name(), "plain");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(calendarPart(ics, method));

        message.setContent(alternative);
    }

    /**
     * Branded shape: {@code multipart/mixed(multipart/alternative(text, html), text/calendar)}.
     *
     * @param htmlBody the rendered {@link EmailTemplate} HTML
     */
    public static void buildBranded(MimeMessage message,
                                    String textBody,
                                    String htmlBody,
                                    String ics,
                                    String method) throws Exception {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, StandardCharsets.UTF_8.name(), "plain");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        // Body representations compete here; HTML is last so clients prefer it.
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);

        MimeBodyPart alternativeWrapper = new MimeBodyPart();
        alternativeWrapper.setContent(alternative);

        // The calendar sits OUTSIDE the alternative, so it never competes with the body.
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(alternativeWrapper);
        mixed.addBodyPart(calendarPart(ics, method));

        message.setContent(mixed);
    }

    /**
     * The single inline calendar part. Deliberately carries no
     * {@code Content-Disposition: attachment} — that disposition is what makes clients treat the
     * invite as a file to open rather than a meeting to render.
     */
    private static MimeBodyPart calendarPart(String ics, String method) throws Exception {
        MimeBodyPart calendar = new MimeBodyPart();
        calendar.setContent(ics, "text/calendar; charset=UTF-8; method=" + method);
        calendar.setHeader("Content-Type",
                "text/calendar; charset=UTF-8; method=" + method + "; name=\"invite.ics\"");
        calendar.setHeader("Content-Transfer-Encoding", "8bit");
        calendar.setHeader("Content-Class", "urn:content-classes:calendarmessage");
        return calendar;
    }
}
