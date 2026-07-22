package io.bunnycal.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Locks in the MIME structure for calendar-bearing email.
 *
 * <p>These assertions encode the two client contracts that were expensive to discover:
 * the invite is carried exactly once, and it never sits inside the {@code multipart/alternative}
 * where it would compete with the HTML body for the client's chosen rendering.
 */
class CalendarMimeAssemblerTest {

    private static final String ICS = """
            BEGIN:VCALENDAR
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:abc-123
            END:VEVENT
            END:VCALENDAR
            """;

    private static MimeMessage newMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void textOnly_keepsLegacyAlternativeShape() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildTextOnly(m, "plain body", ICS, "REQUEST");
        m.saveChanges();

        MimeMultipart root = (MimeMultipart) m.getContent();
        assertTrue(root.getContentType().toLowerCase(Locale.ROOT).contains("multipart/alternative"));
        assertEquals(2, root.getCount());
        assertEquals(1, countCalendarParts(root));
        assertTrue(readPart(root.getBodyPart(0)).contains("plain body"));
    }

    @Test
    void branded_putsCalendarOutsideTheAlternative() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "plain body", "<html>rich</html>", ICS, "REQUEST");
        m.saveChanges();

        MimeMultipart root = (MimeMultipart) m.getContent();
        assertTrue(root.getContentType().toLowerCase(Locale.ROOT).contains("multipart/mixed"));
        assertEquals(2, root.getCount());

        // [0] is the body group. It is wrapped in multipart/related when the mascot is embedded,
        // so find the alternative rather than assuming a fixed depth.
        MimeMultipart bodyGroup = (MimeMultipart) root.getBodyPart(0).getContent();
        MimeMultipart alternative = findAlternative(bodyGroup);
        assertNotNull(alternative, "branded body must contain a multipart/alternative");
        assertEquals(2, alternative.getCount());
        assertEquals(0, countCalendarParts(alternative),
                "calendar must not sit inside the alternative — it would compete with the HTML body");

        // Text first, HTML last: clients render the last part they understand.
        assertTrue(readPart(alternative.getBodyPart(0)).contains("plain body"));
        assertTrue(alternative.getBodyPart(1).getContentType().toLowerCase(Locale.ROOT).contains("text/html"));
        assertTrue(readPart(alternative.getBodyPart(1)).contains("<html>rich</html>"));

        // [1] is the calendar, as a sibling.
        assertTrue(root.getBodyPart(1).getContentType().toLowerCase(Locale.ROOT).contains("text/calendar"));
    }

    @Test
    void branded_embedsTheMascotAlongsideTheBody() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "REQUEST");
        m.saveChanges();

        // Calendar mail gets the same inline mascot as every other branded email.
        MimeMultipart bodyGroup = (MimeMultipart) ((MimeMultipart) m.getContent()).getBodyPart(0).getContent();
        assertTrue(bodyGroup.getContentType().toLowerCase(Locale.ROOT).contains("multipart/related"));
        assertTrue(bodyGroup.getBodyPart(1).getContentType().toLowerCase(Locale.ROOT).contains("image/png"));
    }

    @Test
    void branded_carriesTheInviteExactlyOnce() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "REQUEST");
        m.saveChanges();

        // Two copies (one inline, one attached) makes Outlook create two calendar entries.
        assertEquals(1, countCalendarParts((MimeMultipart) m.getContent()));
    }

    @Test
    void branded_calendarPartIsInlineNotAnAttachment() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "REQUEST");
        m.saveChanges();

        BodyPart calendar = ((MimeMultipart) m.getContent()).getBodyPart(1);
        String[] disposition = calendar.getHeader("Content-Disposition");
        // An attachment disposition makes clients offer a file to open instead of
        // rendering the meeting.
        assertTrue(disposition == null || disposition.length == 0
                        || !disposition[0].toLowerCase(Locale.ROOT).contains("attachment"),
                "calendar part must not be marked as an attachment");
    }

    @Test
    void branded_preservesOutlookAutoRenderHeaders() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "REQUEST");
        m.saveChanges();

        BodyPart calendar = ((MimeMultipart) m.getContent()).getBodyPart(1);
        assertTrue(header(calendar, "Content-Type").toLowerCase(Locale.ROOT).contains("method=request"));
        assertEquals("urn:content-classes:calendarmessage", header(calendar, "Content-Class"));
    }

    @Test
    void branded_carriesTheIcsPayloadIntact() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "REQUEST");
        m.saveChanges();

        String body = readPart(((MimeMultipart) m.getContent()).getBodyPart(1));
        assertTrue(body.contains("METHOD:REQUEST"));
        assertTrue(body.contains("UID:abc-123"));
    }

    @Test
    void branded_cancelMethodIsPropagated() throws Exception {
        MimeMessage m = newMessage();
        CalendarMimeAssembler.buildBranded(m, "t", "<html>h</html>", ICS, "CANCEL");
        m.saveChanges();

        BodyPart calendar = ((MimeMultipart) m.getContent()).getBodyPart(1);
        assertTrue(header(calendar, "Content-Type").toLowerCase(Locale.ROOT).contains("method=cancel"));
    }

    /** Depth-first search for the alternative, so the optional 'related' layer is transparent. */
    private static MimeMultipart findAlternative(MimeMultipart multipart) throws Exception {
        if (multipart.getContentType().toLowerCase(Locale.ROOT).contains("multipart/alternative")) {
            return multipart;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            if (multipart.getBodyPart(i).getContent() instanceof MimeMultipart nested) {
                MimeMultipart found = findAlternative(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String header(BodyPart part, String name) throws Exception {
        String[] values = part.getHeader(name);
        assertNotNull(values, name + " header missing");
        assertTrue(values.length > 0);
        return values[0];
    }

    private static int countCalendarParts(MimeMultipart multipart) throws Exception {
        int found = 0;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String ct = part.getContentType();
            String lowered = ct == null ? "" : ct.toLowerCase(Locale.ROOT);
            if (lowered.startsWith("text/calendar")) {
                found++;
            } else if (lowered.startsWith("multipart/") && part.getContent() instanceof MimeMultipart nested) {
                found += countCalendarParts(nested);
            }
        }
        return found;
    }

    private static String readPart(BodyPart part) throws Exception {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
            if (content instanceof InputStream in) {
                try (InputStream stream = in) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (jakarta.mail.MessagingException | java.io.IOException ignored) {
            // text/calendar has no registered DataContentHandler; fall through.
        }
        try (InputStream stream = part.getDataHandler().getDataSource().getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
