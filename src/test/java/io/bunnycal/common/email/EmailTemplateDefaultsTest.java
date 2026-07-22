package io.bunnycal.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the shipped defaults for the email-template settings.
 *
 * <p>Every other test in this package constructs services with explicit flag values, so nothing
 * otherwise notices if a default in {@code application.yaml} changes. These settings decide what
 * real recipients receive, which makes an accidental edit expensive and silent.
 */
class EmailTemplateDefaultsTest {

    private static String defaultOf(String key) throws IOException {
        Properties yaml = new Properties();
        try (InputStream in = EmailTemplateDefaultsTest.class.getResourceAsStream("/application.yaml")) {
            // Deliberately a line scan rather than a YAML parse: the value is a
            // ${ENV_VAR:default} placeholder, and we assert on the default inside it.
            String content = new String(in.readAllBytes());
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + ":")) {
                    String value = trimmed.substring(key.length() + 1).trim();
                    int colon = value.indexOf(':');
                    return value.startsWith("${") && colon >= 0
                            ? value.substring(colon + 1, value.length() - 1)
                            : value;
                }
            }
        }
        return null;
    }

    @Test
    void calendarHtmlIsEnabledByDefault() throws IOException {
        // Branded HTML on calendar mail. If this is ever turned off again, it should be a
        // deliberate edit here too — not a silent regression to plain-text invites.
        assertEquals("true", defaultOf("calendar-html-enabled"));
    }

    @Test
    void mascotUrlDefaultsToEmptySoTheEmbeddedImageWins() throws IOException {
        // A non-empty default would be a hotlinked URL, which is what rendered as a broken
        // image in real clients. Empty means BrandedMailSender falls through to the cid: form.
        assertEquals("", defaultOf("mascot-url"));
    }

    @Test
    void embeddedMascotTakesPrecedenceOverAConfiguredUrl() {
        // Even if someone sets the URL, the embedded image is the better answer while the
        // asset is present.
        BrandedMailSender sender = new BrandedMailSender(
                null, "https://cdn.example.com/bunny.png", "https://app.example.com");

        String html = sender.template().headline("Hi").build().renderHtml();

        assertTrue(html.contains("cid:" + EmailMascot.CONTENT_ID));
        assertTrue(!html.contains("cdn.example.com"),
                "a configured URL must not win while the bundled asset is available");
    }
}
