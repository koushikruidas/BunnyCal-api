package io.bunnycal.common.email;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;

/**
 * Builds the body tree for branded HTML email that references the embedded mascot.
 *
 * <pre>
 * multipart/related
 * ├── multipart/alternative
 * │   ├── text/plain   (fallback)
 * │   └── text/html    (branded body, references cid:bunny-mascot)
 * └── image/png        (inline, Content-ID: &lt;bunny-mascot&gt;)
 * </pre>
 *
 * <p><b>Why {@code related} and not {@code mixed}.</b> Both let the image coexist with the body
 * rather than compete with it, but {@code related} additionally declares that the image is a
 * resource the body refers to. Clients use that to keep it out of the attachment list; under
 * {@code mixed} Gmail shows a downloadable {@code bunny.png} alongside the message.
 *
 * <p>When the mascot asset is unavailable the wrapper is skipped entirely and the message stays a
 * plain {@code multipart/alternative}, since an empty {@code related} container would be pointless
 * structure for clients to walk.
 */
public final class BrandedMimeAssembler {

    private BrandedMimeAssembler() {
    }

    /** Sets the message content to the branded body tree. */
    public static void build(MimeMessage message, String textBody, String htmlBody) throws Exception {
        message.setContent(buildContent(textBody, htmlBody));
    }

    /**
     * Returns the branded body as a multipart, for callers that need to nest it inside a larger
     * tree — notably {@link CalendarMimeAssembler}, which adds a calendar sibling.
     */
    static MimeMultipart buildContent(String textBody, String htmlBody) throws Exception {
        MimeMultipart alternative = alternative(textBody, htmlBody);
        if (!EmailMascot.isAvailable()) {
            return alternative;
        }

        MimeBodyPart alternativeWrapper = new MimeBodyPart();
        alternativeWrapper.setContent(alternative);

        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(alternativeWrapper);
        EmailMascot.addTo(related);
        return related;
    }

    /**
     * The competing body representations. Order matters: text first, HTML second, because clients
     * render the LAST part they understand.
     */
    static MimeMultipart alternative(String textBody, String htmlBody) throws Exception {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, StandardCharsets.UTF_8.name(), "plain");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);
        return alternative;
    }
}
