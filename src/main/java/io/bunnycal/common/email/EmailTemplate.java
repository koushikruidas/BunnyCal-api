package io.bunnycal.common.email;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the branded BunnyCal HTML email ("Mascot Header" treatment) plus a matching
 * plain-text alternative, so every transactional email shares one visual identity.
 *
 * <p>Callers describe the message semantically — eyebrow, headline, paragraphs, detail rows,
 * a call to action — and this class renders both bodies. Nothing here knows about any specific
 * notification; the chrome (gradient band, mascot, footer) lives only in {@link #renderHtml}.
 *
 * <p>The markup is deliberately old-fashioned: nested tables, inline styles, no shorthand CSS
 * and no web fonts. Mail clients (notably Outlook's Word renderer) do not support flexbox,
 * grid, {@code <style>} blocks or external stylesheets, so this is the portable subset.
 *
 * <p><b>Not for calendar invites.</b> {@code BookingNotificationService} and
 * {@code SessionNotificationService} hand-assemble a {@code multipart/alternative} carrying an
 * INLINE {@code text/calendar} part; that exact shape is what makes Outlook auto-render the
 * invite, and adding an HTML part to it changes the contract. Those two intentionally stay
 * plain-text — see the comment block in {@code BookingNotificationService#sendMail}.
 *
 * <p>Usage:
 * <pre>{@code
 * EmailTemplate.builder()
 *     .eyebrow("Team invitation")
 *     .headline("Priya invited you to Design Guild")
 *     .greeting("Hi Koushik,")
 *     .paragraph("Priya Raman has invited you to join Design Guild on BunnyCal.")
 *     .detail("Team", "Design Guild")
 *     .primaryAction("Accept invitation", acceptUrl)
 *     .note("This invitation expires in 7 days.")
 *     .build();
 * }</pre>
 */
public final class EmailTemplate {

    /* ─── brand tokens, mirrored from the web app's tailwind preset / tokens.css ─── */
    private static final String GRADIENT_FROM = "#8C74B8";
    private static final String GRADIENT_MID = "#A886C8";
    private static final String GRADIENT_TO = "#D29CB7";
    private static final String ACCENT = "#8C74B8";
    private static final String PAGE_BG = "#F5EEF4";
    private static final String CARD_BG = "#FFFDFA";
    private static final String SUNKEN_BG = "#FAF6F2";
    private static final String HAIRLINE = "#E9E1EB";
    private static final String INK = "#0F172A";
    private static final String INK_2 = "#4C586A";
    private static final String INK_3 = "#6F7787";
    private static final String INK_FAINT = "#857B90";
    private static final String BTN_BORDER = "#D9CDDF";

    private static final String FONT_STACK =
            "'Geist',-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif";

    private final String eyebrow;
    private final String headline;
    private final String greeting;
    private final List<String> paragraphs;
    private final Map<String, String> details;
    private final List<String> preformatted;
    private final String primaryLabel;
    private final String primaryUrl;
    private final String secondaryLabel;
    private final String secondaryUrl;
    private final String note;
    private final String footerReason;
    private final String mascotUrl;
    private final String appBaseUrl;

    private EmailTemplate(Builder b) {
        this.eyebrow = b.eyebrow;
        this.headline = b.headline;
        this.greeting = b.greeting;
        this.paragraphs = List.copyOf(b.paragraphs);
        this.details = new LinkedHashMap<>(b.details);
        this.preformatted = List.copyOf(b.preformatted);
        this.primaryLabel = b.primaryLabel;
        this.primaryUrl = b.primaryUrl;
        this.secondaryLabel = b.secondaryLabel;
        this.secondaryUrl = b.secondaryUrl;
        this.note = b.note;
        this.footerReason = b.footerReason;
        this.mascotUrl = b.mascotUrl;
        this.appBaseUrl = b.appBaseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    /* ══════════════════════════════ HTML ══════════════════════════════ */

    /** Renders the branded HTML body. */
    public String renderHtml() {
        StringBuilder h = new StringBuilder(4096);

        h.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" ")
         .append("\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">")
         .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>")
         .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
         .append("<title>").append(esc(headline)).append("</title>")
         // Outlook ignores max-width on tables; this keeps the 600px cap there too.
         .append("<!--[if mso]><style>table{border-collapse:collapse;}td{font-family:Arial,sans-serif;}</style><![endif]-->")
         .append("</head>")
         .append("<body style=\"margin:0;padding:0;background:").append(PAGE_BG).append(";\">");

        // Preheader: the snippet shown next to the subject in most inboxes. Hidden in-body.
        String preheader = firstNonBlank(note, paragraphs.isEmpty() ? null : paragraphs.get(0), headline);
        h.append("<div style=\"display:none;font-size:1px;color:").append(PAGE_BG)
         .append(";line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;\">")
         .append(esc(truncate(preheader, 140)))
         .append("</div>");

        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"background:").append(PAGE_BG).append(";padding:32px 12px;font-family:").append(FONT_STACK).append(";\">")
         .append("<tr><td align=\"center\">")
         .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
         .append("style=\"width:600px;max-width:600px;background:").append(CARD_BG)
         .append(";border-radius:20px;overflow:hidden;border:1px solid ").append(HAIRLINE).append(";\">");

        renderHeader(h);
        renderBody(h);
        renderFooter(h);

        h.append("</table></td></tr></table></body></html>");
        return h.toString();
    }

    /** Gradient band: wordmark, eyebrow, headline, and the mascot on the right. */
    private void renderHeader(StringBuilder h) {
        h.append("<tr><td style=\"background:").append(GRADIENT_FROM)
         .append(";background-image:linear-gradient(135deg,").append(GRADIENT_FROM).append(" 0%,")
         .append(GRADIENT_MID).append(" 48%,").append(GRADIENT_TO).append(" 100%);padding:21px 40px 14px;\">")
         .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
         .append("<td style=\"vertical-align:middle;\">");

        // Wordmark. Text rather than an image so it survives image blocking.
        h.append("<div style=\"font-size:15px;font-weight:600;letter-spacing:-0.01em;color:#FFFFFF;\">BunnyCal</div>");

        if (notBlank(eyebrow)) {
            h.append("<div style=\"padding-top:22px;font-size:11px;font-weight:600;letter-spacing:0.09em;")
             .append("text-transform:uppercase;color:#EADFF3;\">").append(esc(eyebrow)).append("</div>");
        }
        h.append("<div style=\"padding:6px 0 12px;font-size:23px;line-height:30px;font-weight:600;")
         .append("letter-spacing:-0.015em;color:#FFFFFF;max-width:280px;\">").append(esc(headline)).append("</div>")
         .append("</td>");

        if (notBlank(mascotUrl)) {
            h.append("<td width=\"106\" style=\"width:106px;vertical-align:bottom;text-align:right;font-size:0;line-height:0;\">")
             .append("<img src=\"").append(esc(mascotUrl)).append("\" width=\"99\" height=\"165\" alt=\"\" ")
             .append("style=\"display:block;border:0;outline:none;text-decoration:none;width:99px;height:165px;\"/>")
             .append("</td>");
        }
        h.append("</tr></table></td></tr>");
    }

    /** Greeting, paragraphs, detail table, preformatted blocks, buttons, note. */
    private void renderBody(StringBuilder h) {
        boolean opened = false;

        if (notBlank(greeting)) {
            h.append("<tr><td style=\"padding:30px 40px 0;font-size:15px;line-height:23px;color:")
             .append(INK_2).append(";\">").append(esc(greeting)).append("</td></tr>");
            opened = true;
        }

        for (String p : paragraphs) {
            h.append("<tr><td style=\"padding:").append(opened ? "14px" : "30px")
             .append(" 40px 0;font-size:15px;line-height:23px;color:").append(INK_2).append(";\">")
             .append(inlineMarkup(p)).append("</td></tr>");
            opened = true;
        }

        if (!details.isEmpty()) {
            h.append("<tr><td style=\"padding:24px 40px 0;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append("style=\"background:").append(SUNKEN_BG).append(";border-radius:14px;\">");
            int i = 0;
            int last = details.size() - 1;
            for (Map.Entry<String, String> e : details.entrySet()) {
                String top = i == 0 ? "15px" : "0";
                String bottom = i == last ? "16px" : "8px";
                // Label column shrink-wraps (width:1% + nowrap) so a short label like "Team"
                // doesn't strand its value behind a wide fixed gutter. The 22px right padding
                // here is the whole label/value gap; the value cell adds none on its left.
                h.append("<tr><td style=\"padding:").append(top).append(" 22px ").append(bottom)
                 .append(" 18px;font-size:12px;letter-spacing:0.02em;color:").append(INK_3)
                 .append(";white-space:nowrap;width:1%;vertical-align:top;\">")
                 .append(esc(e.getKey())).append("</td>")
                 .append("<td style=\"padding:").append(top).append(" 18px ").append(bottom)
                 .append(" 0;font-size:14px;font-weight:600;color:").append(INK)
                 .append(";vertical-align:top;\">").append(esc(e.getValue())).append("</td></tr>");
                i++;
            }
            h.append("</table></td></tr>");
        }

        // Monospaced block for list-shaped content (e.g. group digests, participant status).
        for (String block : preformatted) {
            h.append("<tr><td style=\"padding:20px 40px 0;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append("style=\"background:").append(SUNKEN_BG).append(";border-radius:14px;\"><tr>")
             .append("<td style=\"padding:14px 18px;font-family:'Geist Mono',Menlo,Consolas,monospace;")
             .append("font-size:13px;line-height:20px;color:").append(INK_2).append(";\">")
             .append(nl2br(esc(block)))
             .append("</td></tr></table></td></tr>");
        }

        if (notBlank(primaryLabel) && notBlank(primaryUrl)) {
            h.append("<tr><td style=\"padding:24px 40px 0;\">")
             .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
             .append("<td style=\"background:").append(ACCENT).append(";border-radius:10px;\">")
             .append("<a href=\"").append(esc(primaryUrl)).append("\" style=\"display:inline-block;padding:13px 26px;")
             .append("font-size:15px;font-weight:600;color:#FFFFFF;text-decoration:none;letter-spacing:-0.005em;\">")
             .append(esc(primaryLabel)).append("</a></td>");
            if (notBlank(secondaryLabel) && notBlank(secondaryUrl)) {
                h.append("<td style=\"width:10px;\">&nbsp;</td>")
                 .append("<td style=\"border:1px solid ").append(BTN_BORDER).append(";border-radius:10px;\">")
                 .append("<a href=\"").append(esc(secondaryUrl)).append("\" style=\"display:inline-block;padding:12px 22px;")
                 .append("font-size:15px;font-weight:600;color:#2B1F3D;text-decoration:none;letter-spacing:-0.005em;\">")
                 .append(esc(secondaryLabel)).append("</a></td>");
            }
            h.append("</tr></table></td></tr>");

            // Clients that strip or mangle the button still need the destination.
            h.append("<tr><td style=\"padding:16px 40px 0;font-size:12px;line-height:18px;color:").append(INK_3)
             .append(";\">Button not working? Paste this into your browser:<br/>")
             .append("<a href=\"").append(esc(primaryUrl)).append("\" style=\"color:").append(ACCENT)
             .append(";text-decoration:none;word-break:break-all;\">").append(esc(primaryUrl))
             .append("</a></td></tr>");
        }

        if (notBlank(note)) {
            h.append("<tr><td style=\"padding:22px 40px 0;\">")
             .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
             .append("style=\"background:#F6EFFC;border:1px solid #DBCBEC;border-radius:12px;\"><tr>")
             .append("<td style=\"padding:13px 16px;font-size:13px;line-height:19px;color:#654C94;\">")
             .append(inlineMarkup(note)).append("</td></tr></table></td></tr>");
        }
    }

    private void renderFooter(StringBuilder h) {
        h.append("<tr><td style=\"padding:28px 40px 30px;\">")
         .append("<div style=\"height:1px;line-height:1px;font-size:0;background:").append(HAIRLINE).append(";\">&nbsp;</div>")
         .append("<div style=\"padding-top:16px;font-size:11.5px;line-height:18px;color:").append(INK_FAINT).append(";\">");

        h.append("Sent by BunnyCal");
        if (notBlank(footerReason)) {
            h.append(" &middot; ").append(esc(footerReason));
        }
        if (notBlank(appBaseUrl)) {
            String base = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
            h.append("<br/><a href=\"").append(esc(base)).append("/dashboard/settings\" style=\"color:")
             .append(INK_FAINT).append(";text-decoration:underline;\">Notification settings</a>");
        }
        h.append("</div></td></tr>");
    }

    /* ══════════════════════════════ text ══════════════════════════════ */

    /**
     * Renders the plain-text alternative. Always sent alongside the HTML: some clients prefer
     * it, some users force it, and a message with no text part scores worse with spam filters.
     */
    public String renderText() {
        StringBuilder t = new StringBuilder(1024);

        if (notBlank(greeting)) {
            t.append(greeting).append("\n\n");
        }
        for (String p : paragraphs) {
            t.append(stripMarkup(p)).append("\n\n");
        }
        if (!details.isEmpty()) {
            for (Map.Entry<String, String> e : details.entrySet()) {
                t.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            t.append('\n');
        }
        for (String block : preformatted) {
            t.append(block);
            if (!block.endsWith("\n")) t.append('\n');
            t.append('\n');
        }
        if (notBlank(primaryLabel) && notBlank(primaryUrl)) {
            t.append(primaryLabel).append(":\n").append(primaryUrl).append("\n\n");
        }
        if (notBlank(secondaryLabel) && notBlank(secondaryUrl)) {
            t.append(secondaryLabel).append(":\n").append(secondaryUrl).append("\n\n");
        }
        if (notBlank(note)) {
            t.append(stripMarkup(note)).append("\n\n");
        }
        t.append("— BunnyCal");
        return t.toString();
    }

    /* ══════════════════════════════ helpers ══════════════════════════════ */

    /** Escapes HTML, then re-enables the one markup token callers may use: {@code **bold**}. */
    private static String inlineMarkup(String raw) {
        String escaped = esc(raw);
        StringBuilder out = new StringBuilder(escaped.length());
        int i = 0;
        boolean open = true;
        while (i < escaped.length()) {
            int at = escaped.indexOf("**", i);
            if (at < 0) {
                out.append(escaped, i, escaped.length());
                break;
            }
            out.append(escaped, i, at);
            out.append(open ? "<b style=\"color:" + INK + ";font-weight:600;\">" : "</b>");
            open = !open;
            i = at + 2;
        }
        // An unbalanced marker would leave a dangling tag; close it.
        if (!open) out.append("</b>");
        return nl2br(out.toString());
    }

    /** Removes {@code **} emphasis for the text part. */
    private static String stripMarkup(String raw) {
        return raw == null ? "" : raw.replace("**", "");
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String nl2br(String s) {
        return s.replace("\n", "<br/>");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String flat = s.replace("**", "").replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (notBlank(c)) return c;
        }
        return "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /* ══════════════════════════════ builder ══════════════════════════════ */

    public static final class Builder {
        private String eyebrow;
        private String headline = "BunnyCal";
        private String greeting;
        private final List<String> paragraphs = new ArrayList<>();
        private final Map<String, String> details = new LinkedHashMap<>();
        private final List<String> preformatted = new ArrayList<>();
        private String primaryLabel;
        private String primaryUrl;
        private String secondaryLabel;
        private String secondaryUrl;
        private String note;
        private String footerReason;
        private String mascotUrl;
        private String appBaseUrl;

        /** Small uppercase label above the headline, e.g. "Team invitation". */
        public Builder eyebrow(String v) { this.eyebrow = v; return this; }

        /** The headline inside the gradient band. Keep it short — it wraps at ~280px. */
        public Builder headline(String v) { this.headline = v; return this; }

        /** e.g. "Hi Koushik,". Omit for messages that read better without one. */
        public Builder greeting(String v) { this.greeting = v; return this; }

        /** Adds a body paragraph. {@code **text**} renders bold; newlines become line breaks. */
        public Builder paragraph(String v) {
            if (notBlank(v)) this.paragraphs.add(v);
            return this;
        }

        /** Adds a label/value row to the sunken detail panel. Skipped when value is blank. */
        public Builder detail(String label, String value) {
            if (notBlank(label) && notBlank(value)) this.details.put(label, value);
            return this;
        }

        /** Adds a monospaced block, for list-shaped content that must keep its line breaks. */
        public Builder preformatted(String v) {
            if (notBlank(v)) this.preformatted.add(v);
            return this;
        }

        public Builder primaryAction(String label, String url) {
            this.primaryLabel = label;
            this.primaryUrl = url;
            return this;
        }

        public Builder secondaryAction(String label, String url) {
            this.secondaryLabel = label;
            this.secondaryUrl = url;
            return this;
        }

        /** Lavender callout below the buttons — expiry windows, "ignore this if…" caveats. */
        public Builder note(String v) { this.note = v; return this; }

        /** Footer explanation, e.g. "you're receiving this because someone invited you to a team". */
        public Builder footerReason(String v) { this.footerReason = v; return this; }

        /** Absolute URL of the mascot PNG. When blank, the header renders without it. */
        public Builder mascotUrl(String v) { this.mascotUrl = v; return this; }

        /** Public app base URL, used to build the footer's settings link. */
        public Builder appBaseUrl(String v) { this.appBaseUrl = v; return this; }

        public EmailTemplate build() {
            return new EmailTemplate(this);
        }
    }
}
