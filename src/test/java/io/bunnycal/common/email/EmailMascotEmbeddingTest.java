package io.bunnycal.common.email;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Covers the inline mascot: the image travels with the message rather than being fetched from a
 * URL, so it renders with no public hosting and survives remote-image blocking.
 *
 * <p>The regression these guard against is the mascot showing as a broken-image placeholder,
 * which is what a {@code http://localhost:8080/…} src produced in real clients.
 */
class EmailMascotEmbeddingTest {

    private static MimeMessage newMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private static final String HTML =
            "<html><body><img src=\"" + EmailMascot.CID_URL + "\"/></body></html>";

    @Test
    void mascotAssetIsBundledOnTheClasspath() {
        // Everything else here is conditional on this; if the asset stops being packaged the
        // mascot silently disappears from every email.
        assertTrue(EmailMascot.isAvailable(),
                "bunny.png must be on the classpath at /static/assets/email/bunny.png");
    }

    @Test
    void brandedBody_nestsAlternativeInsideRelatedWithTheImage() throws Exception {
        MimeMessage m = newMessage();
        BrandedMimeAssembler.build(m, "plain body", HTML);
        m.saveChanges();

        MimeMultipart root = (MimeMultipart) m.getContent();
        assertTrue(root.getContentType().toLowerCase(Locale.ROOT).contains("multipart/related"),
                "the image must be 'related' to the body, not a 'mixed' sibling, or clients "
                        + "list it as an attachment");
        assertEquals(2, root.getCount());

        MimeMultipart alternative = (MimeMultipart) root.getBodyPart(0).getContent();
        assertTrue(alternative.getContentType().toLowerCase(Locale.ROOT).contains("multipart/alternative"));
        assertTrue(readPart(alternative.getBodyPart(0)).contains("plain body"));
        assertTrue(alternative.getBodyPart(1).getContentType().toLowerCase(Locale.ROOT).contains("text/html"));

        assertTrue(root.getBodyPart(1).getContentType().toLowerCase(Locale.ROOT).contains("image/png"));
    }

    @Test
    void contentIdMatchesTheCidReferencedInTheHtml() throws Exception {
        MimeMessage m = newMessage();
        BrandedMimeAssembler.build(m, "t", HTML);
        m.saveChanges();

        BodyPart image = ((MimeMultipart) m.getContent()).getBodyPart(1);
        String[] contentId = image.getHeader("Content-ID");
        assertNotNull(contentId, "Content-ID header missing — the cid: reference cannot resolve");

        // RFC 2392: the header is bracketed, the cid: URL in the body is not.
        assertEquals("<" + EmailMascot.CONTENT_ID + ">", contentId[0]);
        assertTrue(HTML.contains("cid:" + EmailMascot.CONTENT_ID),
                "the html must reference exactly the Content-ID the part declares");
    }

    @Test
    void imagePartIsInlineNotAnAttachment() throws Exception {
        MimeMessage m = newMessage();
        BrandedMimeAssembler.build(m, "t", HTML);
        m.saveChanges();

        BodyPart image = ((MimeMultipart) m.getContent()).getBodyPart(1);
        String[] disposition = image.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition[0].toLowerCase(Locale.ROOT).contains("inline"),
                "an attachment disposition puts the mascot in the client's attachment list");
    }

    @Test
    void transmittedBytesAreTheRealPngUnaltered() throws Exception {
        MimeMessage m = newMessage();
        BrandedMimeAssembler.build(m, "t", HTML);
        m.saveChanges();

        // Round-trip through a serialized message, so this reflects what is actually sent
        // (base64-encoded on the wire) rather than the in-memory object.
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        m.writeTo(wire);
        MimeMessage received = new MimeMessage(
                Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(wire.toByteArray()));

        BodyPart image = ((MimeMultipart) received.getContent()).getBodyPart(1);
        byte[] decoded;
        try (InputStream in = image.getInputStream()) {
            decoded = in.readAllBytes();
        }

        byte[] original;
        try (InputStream in = EmailMascot.class.getResourceAsStream("/static/assets/email/bunny.png")) {
            original = in.readAllBytes();
        }

        assertArrayEquals(original, decoded, "the mascot must survive MIME encoding intact");
        // PNG magic number, as a readable failure if the above ever regresses.
        assertEquals((byte) 0x89, decoded[0]);
        assertEquals('P', decoded[1]);
    }

    @Test
    void renderedHtmlReferencesTheEmbeddedImageNotAUrl() {
        String html = EmailTemplate.builder()
                .mascotUrl(EmailMascot.CID_URL)
                .headline("Hello")
                .paragraph("Body")
                .build()
                .renderHtml();

        assertTrue(html.contains("src=\"cid:" + EmailMascot.CONTENT_ID + "\""),
                "the template must emit the cid: reference verbatim — escaping would break it");
        assertFalse(html.contains("http://localhost"),
                "a localhost src resolves to the reader's machine and renders as a broken image");
    }

    @Test
    void messageStaysUnderGmailsClippingThreshold() throws Exception {
        MimeMessage m = newMessage();
        m.setFrom("no-reply@example.com");
        m.setRecipients(MimeMessage.RecipientType.TO, "guest@example.com");
        m.setSubject("Booking confirmed");
        EmailTemplate template = EmailTemplate.builder()
                .mascotUrl(EmailMascot.CID_URL)
                .eyebrow("New registration")
                .headline("First registration: Yoga Class")
                .paragraph("A guest registered for your group event.")
                .detail("Event", "Yoga Class")
                .detail("Session", "Fri, Jul 31, 2026 at 3:00 PM IST")
                .primaryAction("View booking", "https://app.example.com/bookings")
                .build();
        BrandedMimeAssembler.build(m, template.renderText(), template.renderHtml());
        m.saveChanges();

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        m.writeTo(wire);

        // Gmail clips messages past ~102 KB, hiding the footer behind a "View entire message"
        // link. The base64-encoded mascot is the bulk of the payload, so this is the real budget.
        assertTrue(wire.size() < 102_400,
                "message is " + wire.size() + " bytes; Gmail clips past 102400");
    }

    @Test
    void withoutTheMascot_bodyStaysAPlainAlternative() throws Exception {
        // Mirrors the asset-missing path: no empty 'related' wrapper for clients to walk.
        MimeMultipart content = BrandedMimeAssembler.alternative("plain", "<html>h</html>");

        assertTrue(content.getContentType().toLowerCase(Locale.ROOT).contains("multipart/alternative"));
        assertEquals(2, content.getCount());
    }

    private static String readPart(BodyPart part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        try (InputStream stream = part.getDataHandler().getDataSource().getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
