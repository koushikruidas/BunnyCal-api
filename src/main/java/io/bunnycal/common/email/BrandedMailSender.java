package io.bunnycal.common.email;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends an {@link EmailTemplate} as a {@code multipart/alternative} carrying both the
 * plain-text and branded HTML bodies, so the seven notification services do not each
 * hand-assemble MIME.
 *
 * <p>Supplies the shared presentation config — the mascot URL and the public app base URL —
 * so individual services never need to know about them.
 *
 * <p><b>Not for calendar invites.</b> Booking and session invites need an INLINE
 * {@code text/calendar} part in a very specific position for Outlook to auto-render them; those
 * services build their own MIME tree and must not route through here.
 */
@Component
public class BrandedMailSender {

    private final JavaMailSender mailSender;
    private final String mascotUrl;
    private final String appBaseUrl;

    public BrandedMailSender(
            JavaMailSender mailSender,
            @Value("${booking.notifications.email-template.mascot-url:}") String mascotUrl,
            @Value("${app.public-base-url:}") String appBaseUrl) {
        this.mailSender = mailSender;
        this.mascotUrl = mascotUrl;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * Returns a builder pre-populated with the shared branding, so callers only describe
     * the message content.
     */
    public EmailTemplate.Builder template() {
        return EmailTemplate.builder()
                .mascotUrl(mascotUrl)
                .appBaseUrl(appBaseUrl);
    }

    /**
     * Sends the rendered template.
     *
     * @param fromAddress envelope From
     * @param fromName    display name; when blank, only the address is used
     * @param to          recipient
     * @param subject     message subject
     * @param template    body content
     * @throws Exception when message construction or delivery fails; callers translate this
     *                   into a retryable outbox failure
     */
    public void send(String fromAddress,
                     String fromName,
                     String to,
                     String subject,
                     EmailTemplate template) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        // multipart=true so the helper builds multipart/alternative for the text+HTML pair.
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

        if (fromName != null && !fromName.isBlank()) {
            helper.setFrom(fromAddress, fromName);
        } else {
            helper.setFrom(fromAddress);
        }
        helper.setTo(to);
        helper.setSubject(subject);
        // Order matters: text first, HTML second. Clients render the LAST part they understand,
        // so this makes HTML the preferred body and text the fallback.
        helper.setText(template.renderText(), template.renderHtml());

        message.saveChanges();
        mailSender.send(message);
    }
}
