package io.bunnycal.common.email;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.activation.DataHandler;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;

/**
 * Embeds the bunny mascot directly in the message as a {@code cid:} inline image.
 *
 * <p><b>Why not an {@code <img src="https://…">}.</b> A remote URL has to be reachable from
 * wherever the mail is rendered — Microsoft's or Google's image proxy, not the sending host. That
 * makes the mascot depend on the API being publicly exposed over HTTPS, breaks it entirely in local
 * development ({@code http://localhost:8080} resolves to the reader's own machine), and still
 * leaves it blank whenever the recipient has remote images turned off, which most clients do by
 * default for unknown senders.
 *
 * <p>Embedding sidesteps all of it: the bytes travel with the message, so the mascot renders on
 * the first open with no hosting, no public URL, and no proxy involvement. The cost is ~38 KB per
 * message, comfortably under Gmail's 102 KB clipping threshold.
 *
 * <p>The image is loaded once at class-init from the classpath and shared; {@link MimeBodyPart}
 * copies the bytes into each message, so the shared array is never mutated.
 */
public final class EmailMascot {

    /** Matches the {@code cid:} reference {@link EmailTemplate} renders in its {@code <img>}. */
    public static final String CONTENT_ID = "bunny-mascot";

    /** Value to pass to {@link EmailTemplate.Builder#mascotUrl}, when embedding is available. */
    public static final String CID_URL = "cid:" + CONTENT_ID;

    private static final String RESOURCE = "/static/assets/email/bunny.png";

    /** Null when the asset is missing, in which case the header simply renders without a mascot. */
    private static final byte[] PNG = load();

    private EmailMascot() {
    }

    /** Whether the mascot bytes are available to embed. */
    public static boolean isAvailable() {
        return PNG != null;
    }

    /**
     * Appends the inline image part to a {@code multipart/related} container.
     *
     * <p>The caller is responsible for the container being {@code related} rather than
     * {@code mixed} — {@code related} is what tells clients the part is a resource referenced by
     * the body, not a user-facing attachment. Under {@code mixed}, Gmail shows it as a downloadable
     * file.
     */
    public static void addTo(MimeMultipart related) throws Exception {
        if (PNG == null) {
            return;
        }
        MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new DataHandler(new ByteArrayDataSource(PNG, "image/png")));
        // Angle brackets are required by RFC 2392; the cid: URL in the body omits them.
        image.setHeader("Content-ID", "<" + CONTENT_ID + ">");
        image.setHeader("Content-Type", "image/png; name=\"bunny.png\"");
        // "inline" keeps the part out of the client's attachment list.
        image.setDisposition(MimeBodyPart.INLINE);
        image.setFileName("bunny.png");
        related.addBodyPart(image);
    }

    private static byte[] load() {
        try (InputStream in = EmailMascot.class.getResourceAsStream(RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
