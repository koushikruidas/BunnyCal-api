package io.bunnycal.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailTemplateTest {

    private static EmailTemplate.Builder base() {
        return EmailTemplate.builder()
                .mascotUrl("https://api.example.test/assets/email/bunny.png")
                .appBaseUrl("https://app.example.test");
    }

    @Test
    void html_carriesBrandChromeAndContent() {
        String html = base()
                .eyebrow("Team invitation")
                .headline("Priya invited you to Design Guild")
                .greeting("Hi Koushik,")
                .paragraph("You have been invited.")
                .detail("Team", "Design Guild")
                .primaryAction("Accept invitation", "https://app.example.test/invite/abc")
                .note("Expires in 7 days.")
                .footerReason("you were invited to a team")
                .build()
                .renderHtml();

        assertThat(html).contains("Team invitation");
        assertThat(html).contains("Priya invited you to Design Guild");
        assertThat(html).contains("Hi Koushik,");
        assertThat(html).contains("Design Guild");
        assertThat(html).contains("https://app.example.test/invite/abc");
        assertThat(html).contains("Expires in 7 days.");
        assertThat(html).contains("you were invited to a team");
        // brand chrome
        assertThat(html).contains("linear-gradient(135deg,#8C74B8");
        assertThat(html).contains("assets/email/bunny.png");
        assertThat(html).contains("BunnyCal");
    }

    @Test
    void text_altIsPlainAndCarriesTheSameSubstance() {
        String text = base()
                .headline("Priya invited you to Design Guild")
                .greeting("Hi Koushik,")
                .paragraph("**Priya** invited you.")
                .detail("Team", "Design Guild")
                .primaryAction("Accept invitation", "https://app.example.test/invite/abc")
                .build()
                .renderText();

        assertThat(text).doesNotContain("<");
        assertThat(text).doesNotContain("**");
        assertThat(text).contains("Hi Koushik,");
        assertThat(text).contains("Priya invited you.");
        assertThat(text).contains("Team: Design Guild");
        assertThat(text).contains("https://app.example.test/invite/abc");
        assertThat(text).endsWith("— BunnyCal");
    }

    @Test
    void boldMarkupBecomesTagsInHtmlAndDisappearsInText() {
        EmailTemplate t = base()
                .headline("H")
                .paragraph("Hello **Priya Raman** welcome")
                .build();

        assertThat(t.renderHtml()).contains("<b style=\"color:#0F172A;font-weight:600;\">Priya Raman</b>");
        assertThat(t.renderText()).contains("Hello Priya Raman welcome");
    }

    @Test
    void unbalancedBoldMarkerStillProducesClosedTags() {
        // A stray "**" from interpolated content must not leave a dangling <b>.
        String html = base().headline("H").paragraph("Oops ** unbalanced").build().renderHtml();

        assertThat(countOccurrences(html, "<b style=")).isEqualTo(countOccurrences(html, "</b>"));
    }

    @Test
    void userContentIsHtmlEscaped() {
        String html = base()
                .headline("<script>alert(1)</script>")
                .paragraph("Team \"A&B\" <b>raw</b>")
                .detail("Label<>", "Value&More")
                .build()
                .renderHtml();

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("A&amp;B");
        // the only <b> tags present are the ones the template itself emits
        assertThat(html).doesNotContain("<b>raw</b>");
        assertThat(html).contains("Value&amp;More");
    }

    @Test
    void omittedSectionsAreSkippedEntirely() {
        String html = base().headline("Just a headline").build().renderHtml();

        assertThat(html).contains("Just a headline");
        // no detail panel, no buttons, no note callout
        assertThat(html).doesNotContain("Button not working?");
        assertThat(html).doesNotContain("#F6EFFC");
    }

    @Test
    void blankDetailValuesAreDropped() {
        String html = base()
                .headline("H")
                .detail("Kept", "yes")
                .detail("Dropped", "")
                .detail("AlsoDropped", null)
                .build()
                .renderHtml();

        assertThat(html).contains("Kept");
        assertThat(html).doesNotContain("Dropped");
    }

    @Test
    void missingMascotUrlRendersHeaderWithoutImage() {
        String html = EmailTemplate.builder()
                .headline("No mascot")
                .paragraph("body")
                .build()
                .renderHtml();

        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("No mascot");
        assertThat(html).contains("linear-gradient(135deg,#8C74B8");
    }

    @Test
    void preformattedBlockPreservesLineBreaks() {
        String html = base()
                .headline("H")
                .preformatted("line one\nline two")
                .build()
                .renderHtml();

        assertThat(html).contains("line one<br/>line two");
        assertThat(base().headline("H").preformatted("line one\nline two").build().renderText())
                .contains("line one\nline two");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            count++;
            i += needle.length();
        }
        return count;
    }
}
