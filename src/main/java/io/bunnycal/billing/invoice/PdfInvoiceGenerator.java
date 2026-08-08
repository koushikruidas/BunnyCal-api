package io.bunnycal.billing.invoice;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.config.InvoicePresentationProperties;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders a branded billing PDF on demand. In Merchant-of-Record mode this is a payment
 * receipt, not a tax invoice. Pure: takes the immutable payment record plus display fields
 * and returns bytes — no I/O or persistence.
 */
@Component
public class PdfInvoiceGenerator {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // Palette ported from the receipt design. The design expresses these in oklch; they are
    // converted to sRGB here because PDF has no oklch colour space.
    private static final Color INK = new Color(0x2a, 0x21, 0x38);
    // Wordmark colours, matching BrandWordmark.tsx: "Bunny" in plum-500, "Cal" in plum-900.
    private static final Color PLUM_500 = new Color(0x5E, 0x4E, 0x99);
    private static final Color PLUM_900 = new Color(0x1F, 0x15, 0x30);
    private static final Color MUTED = new Color(0x6b, 0x65, 0x77);
    private static final Color LABEL = new Color(0xa1, 0x9b, 0xad);
    private static final Color SUBTLE = new Color(0x8b, 0x84, 0x97);
    private static final Color HAIRLINE = new Color(0xed, 0xe8, 0xf2);
    private static final Color PANEL = new Color(0xfa, 0xf8, 0xfc);
    private static final Color TOTAL_BAND = new Color(0xf6, 0xf2, 0xfa);
    private static final Color ACCENT = new Color(0xd8, 0xc2, 0xe8);
    private static final Color ACCENT_WARM = new Color(0xe9, 0xc9, 0xdd);
    private static final Color PAID_BG = new Color(0xe4, 0xf5, 0xe9);
    private static final Color PAID_FG = new Color(0x2f, 0x7d, 0x4f);

    /** Product tagline, kept in step with the marketing site's index page. */
    private static final String TAGLINE = "Your calendar, simplified.";

    private final InvoicePresentationProperties presentation;
    private final BillingProperties billing;

    @org.springframework.beans.factory.annotation.Autowired
    public PdfInvoiceGenerator(
            InvoicePresentationProperties presentation,
            BillingProperties billing) {
        this.presentation = presentation;
        this.billing = billing;
    }

    /** Convenient constructor for pure rendering tests. */
    PdfInvoiceGenerator(InvoicePresentationProperties presentation) {
        this.presentation = presentation;
        this.billing = null;
    }

    /** Customer + plan display fields not stored on the invoice row. */
    public record InvoiceContext(
            String customerName,
            String customerEmail,
            String planName,
            String paymentId,
            java.time.Instant periodStart,
            java.time.Instant periodEnd) {
    }

    public byte[] generate(SubscriptionInvoice invoice, InvoiceContext ctx) {
        Document document = new Document(PageSize.A4, 44, 44, 40, 44);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, LABEL);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, INK);
            Font valueStrong = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, INK);
            Font noteFont = FontFactory.getFont(FontFactory.HELVETICA, 9, SUBTLE);

            // Dodo is always Merchant of Record. Do not require a second environment flag
            // to prevent BunnyCal accidentally presenting itself as the legal seller.
            boolean mor = presentation.isMerchantOfRecord()
                    || (billing != null && "dodo".equalsIgnoreCase(billing.provider()));

            document.add(accentBar());
            document.add(header(invoice, mor));
            document.add(referenceRow(invoice, mor));
            document.add(partiesGrid(invoice, ctx, labelFont, valueFont, valueStrong));
            document.add(lineItems(invoice, ctx, mor));
            document.add(paymentDetails(invoice, ctx, mor));
            document.add(footerNote(mor, noteFont));

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            if (document.isOpen()) {
                document.close();
            }
            throw new IllegalStateException("Failed to render billing PDF for " + invoice.getInvoiceNumber(), e);
        }
    }

    /**
     * The design's 6px gradient cap. PDF cells take a single fill, so the gradient is
     * approximated with two abutting bands of its end colours.
     */
    private static PdfPTable accentBar() {
        PdfPTable bar = new PdfPTable(2);
        bar.setWidthPercentage(100);
        bar.setSpacingAfter(26);
        bar.addCell(band(ACCENT));
        bar.addCell(band(ACCENT_WARM));
        return bar;
    }

    private static PdfPCell band(Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setFixedHeight(5f);
        cell.setBackgroundColor(color);
        return cell;
    }

    /** Brand mark + wordmark on the left, document title and status pill on the right. */
    private PdfPTable header(SubscriptionInvoice invoice, boolean mor) {
        PdfPTable header = new PdfPTable(new float[] {1.6f, 1f});
        header.setWidthPercentage(100);
        header.setSpacingAfter(24);

        PdfPTable brandRow = new PdfPTable(new float[] {40f, 200f});
        brandRow.setWidthPercentage(100);
        brandRow.getDefaultCell().setBorder(0);

        // Mirrors the dashboard sidebar lockup (.dash-side-brand, align-items:center): the mark
        // is centred against the two-line text block as a whole, not aligned to its first line.
        // Both cells must be phrase-based — a cell built with addElement() is in composite mode,
        // where setVerticalAlignment is ignored and content stacks from the top, which is what
        // left the mark sitting high against the wordmark.
        Image mark = brandMark();
        PdfPCell markCell;
        if (mark != null) {
            mark.scaleToFit(38, 38);
            markCell = new PdfPCell(mark, false);
        } else {
            markCell = new PdfPCell();
        }
        markCell.setBorder(0);
        markCell.setPaddingRight(10);
        markCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        markCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        // Tall enough to hold the two-line lockup; both cells centre within it.
        markCell.setFixedHeight(42f);
        brandRow.addCell(markCell);

        // Two paragraphs rather than one with a newline: a single phrase uses the font's own
        // leading, so the kicker crowds the wordmark and setLeading has no effect on it.
        // Composite mode ignores vertical centring, so the row is given a fixed height and both
        // cells centre against it — the same result as the sidebar's align-items:center.
        Paragraph name = wordmarkParagraph(presentation.sellerName());
        name.setLeading(15f);
        Paragraph tagline = new Paragraph(
                TAGLINE, FontFactory.getFont(FontFactory.HELVETICA, 8.5f, SUBTLE));
        tagline.setLeading(11f);
        tagline.setSpacingBefore(2f);

        PdfPCell wordmark = new PdfPCell();
        wordmark.setBorder(0);
        wordmark.setVerticalAlignment(Element.ALIGN_MIDDLE);
        wordmark.setPaddingTop(3);
        wordmark.addElement(name);
        wordmark.addElement(tagline);
        brandRow.addCell(wordmark);

        PdfPCell brandCell = new PdfPCell(brandRow);
        brandCell.setBorder(0);
        header.addCell(brandCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(0);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph title = new Paragraph(
                (mor ? "PAYMENT RECEIPT" : "INVOICE").toUpperCase(Locale.ROOT),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, SUBTLE));
        title.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(title);
        titleCell.addElement(statusPill(invoice.getStatus()));
        header.addCell(titleCell);
        return header;
    }

    /**
     * The wordmark's two-tone treatment from BrandWordmark.tsx — "Bunny" in plum-500 against
     * "Cal" in plum-900. Only the BunnyCal name splits; a configured seller name is drawn in a
     * single colour rather than being cut at an arbitrary point.
     */
    private static Paragraph wordmarkParagraph(String sellerName) {
        Font bunnyFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, PLUM_500);
        Font calFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, PLUM_900);
        if (!"bunnycal".equalsIgnoreCase(sellerName)) {
            return new Paragraph(sellerName, calFont);
        }
        Paragraph mark = new Paragraph();
        // Preserve the configured casing rather than hardcoding the split halves.
        mark.add(new Phrase(sellerName.substring(0, 5), bunnyFont));
        mark.add(new Phrase(sellerName.substring(5), calFont));
        return mark;
    }

    /**
     * The design's rounded status chip. Rendered as a right-aligned single-cell table so the
     * tint hugs the label instead of spanning the column.
     */
    private static PdfPTable statusPill(InvoiceStatus status) {
        boolean paid = status == InvoiceStatus.PAID;
        Color bg = paid ? PAID_BG : new Color(0xf0, 0xee, 0xf4);
        Color fg = paid ? PAID_FG : MUTED;
        String text = status == null ? "—" : status.name();

        // Sized in absolute points and right-aligned so the tint hugs the label like the
        // design's chip, instead of stretching across the whole column.
        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setTotalWidth(Math.max(52f, text.length() * 6.2f + 22f));
        wrapper.setLockedWidth(true);
        wrapper.setHorizontalAlignment(Element.ALIGN_RIGHT);
        wrapper.setSpacingBefore(9);

        PdfPCell pill = new PdfPCell(new Phrase(
                text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, fg)));
        pill.setBorder(0);
        pill.setBackgroundColor(bg);
        pill.setHorizontalAlignment(Element.ALIGN_CENTER);
        pill.setPaddingTop(5);
        pill.setPaddingBottom(6);
        wrapper.addCell(pill);
        return wrapper;
    }

    /** Receipt number and issue date, above the first hairline rule. */
    private PdfPTable referenceRow(SubscriptionInvoice invoice, boolean mor) {
        PdfPTable table = new PdfPTable(new float[] {1f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(20);

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, LABEL);
        Font monoFont = FontFactory.getFont(FontFactory.COURIER, 10.5f, INK);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, INK);

        PdfPCell number = labelValueCell(
                mor ? "RECEIPT NO." : "INVOICE NO.", invoice.getInvoiceNumber(), labelFont, monoFont);
        PdfPCell issued = labelValueCell(
                "ISSUED", DATE.format(invoice.getIssuedAt()), labelFont, valueFont);
        number.setPaddingBottom(16);
        issued.setPaddingBottom(16);
        number.setBorderWidthBottom(0.7f);
        number.setBorderColorBottom(HAIRLINE);
        issued.setBorderWidthBottom(0.7f);
        issued.setBorderColorBottom(HAIRLINE);
        table.addCell(number);
        table.addCell(issued);
        return table;
    }

    /** Two-by-two grid: billed to / subscription / billing period / next renewal. */
    private PdfPTable partiesGrid(
            SubscriptionInvoice invoice, InvoiceContext ctx, Font labelFont, Font valueFont, Font valueStrong) {
        PdfPTable grid = new PdfPTable(new float[] {1f, 1f});
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(4);
        grid.setSpacingAfter(24);

        PdfPCell billedTo = new PdfPCell();
        billedTo.setBorder(0);
        billedTo.setPaddingBottom(18);
        billedTo.addElement(new Paragraph("BILLED TO", labelFont));
        Paragraph who = new Paragraph(ctx.customerName(), valueStrong);
        who.setSpacingBefore(3);
        billedTo.addElement(who);
        if (ctx.customerEmail() != null && !ctx.customerEmail().isBlank()) {
            billedTo.addElement(new Paragraph(
                    ctx.customerEmail(), FontFactory.getFont(FontFactory.HELVETICA, 9.5f, MUTED)));
        }
        grid.addCell(billedTo);

        PdfPCell plan = new PdfPCell();
        plan.setBorder(0);
        plan.setPaddingBottom(18);
        plan.addElement(new Paragraph("SUBSCRIPTION", labelFont));
        Paragraph planName = new Paragraph(subscriptionName(ctx.planName()), valueStrong);
        planName.setSpacingBefore(3);
        plan.addElement(planName);
        grid.addCell(plan);

        Instant periodStart = ctx.periodStart() != null ? ctx.periodStart() : invoice.getPeriodStart();
        Instant periodEnd = ctx.periodEnd() != null ? ctx.periodEnd() : invoice.getPeriodEnd();
        String period = periodStart != null && periodEnd != null
                ? DATE.format(periodStart) + " – "
                        + DATE.format(periodEnd.minus(1, java.time.temporal.ChronoUnit.DAYS))
                : "—";
        grid.addCell(labelValueCell("BILLING PERIOD", period, labelFont, valueFont));
        grid.addCell(labelValueCell(
                "NEXT RENEWAL", periodEnd == null ? "—" : DATE.format(periodEnd), labelFont, valueFont));
        return grid;
    }

    /** Bordered line-item card: tinted header, description rows, tinted total band. */
    private PdfPTable lineItems(SubscriptionInvoice invoice, InvoiceContext ctx, boolean mor) {
        PdfPTable inner = new PdfPTable(new float[] {3.1f, 1f});
        inner.setWidthPercentage(100);
        PdfPTable items = inner;

        Font colHead = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, LABEL);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, INK);
        Font bodyMuted = FontFactory.getFont(FontFactory.HELVETICA, 9, SUBTLE);
        Font mono = FontFactory.getFont(FontFactory.COURIER, 10.5f, INK);
        Font totalLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, INK);
        Font totalValue = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, INK);

        items.addCell(cardHeaderCell("DESCRIPTION", colHead, Element.ALIGN_LEFT));
        items.addCell(cardHeaderCell("AMOUNT", colHead, Element.ALIGN_RIGHT));

        // Primary line: plan name with the covered dates beneath it, as in the design.
        PdfPCell desc = new PdfPCell();
        desc.setBorder(0);
        desc.setBorderWidthTop(0.7f);
        desc.setBorderColorTop(HAIRLINE);
        desc.setPaddingLeft(16);
        desc.setPaddingTop(13);
        desc.setPaddingBottom(13);
        desc.addElement(new Paragraph(subscriptionName(ctx.planName()), body));
        String covered = coveredPeriod(invoice, ctx);
        if (covered != null) {
            Paragraph sub = new Paragraph(covered, bodyMuted);
            sub.setSpacingBefore(2);
            desc.addElement(sub);
        }
        items.addCell(desc);
        items.addCell(amountCell(
                money(invoice.getSubtotalMinor(), invoice.getCurrency()), mono, true));

        if (invoice.getDiscountMinor() > 0) {
            items.addCell(secondaryLabel("Discount", body));
            items.addCell(amountCell(
                    "-" + money(invoice.getDiscountMinor(), invoice.getCurrency()), mono, false));
        }

        // Tax is rendered only when the record actually carries one. Under Merchant-of-Record
        // billing the MoR collects and reports tax on its own invoice, so BunnyCal's receipt
        // must not imply a tax line it did not charge.
        if (invoice.getTaxMinor() > 0) {
            items.addCell(secondaryLabel("Tax", body));
            items.addCell(amountCell(money(invoice.getTaxMinor(), invoice.getCurrency()), mono, false));
        }

        PdfPCell totalText = new PdfPCell(new Phrase(mor ? "Total paid" : "Total due", totalLabel));
        totalText.setBorder(0);
        totalText.setBorderWidthTop(0.7f);
        totalText.setBorderColorTop(HAIRLINE);
        totalText.setBackgroundColor(TOTAL_BAND);
        totalText.setPaddingLeft(16);
        totalText.setPaddingTop(13);
        totalText.setPaddingBottom(13);
        totalText.setVerticalAlignment(Element.ALIGN_MIDDLE);
        items.addCell(totalText);

        PdfPCell totalAmount = new PdfPCell(new Phrase(
                money(invoice.getTotalMinor(), invoice.getCurrency()), totalValue));
        totalAmount.setBorder(0);
        totalAmount.setBorderWidthTop(0.7f);
        totalAmount.setBorderColorTop(HAIRLINE);
        totalAmount.setBackgroundColor(TOTAL_BAND);
        totalAmount.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalAmount.setPaddingRight(16);
        totalAmount.setPaddingTop(13);
        totalAmount.setPaddingBottom(13);
        totalAmount.setVerticalAlignment(Element.ALIGN_MIDDLE);
        items.addCell(totalAmount);

        if (invoice.getAmountRefundedMinor() > 0) {
            items.addCell(secondaryLabel("Refunded", body));
            items.addCell(amountCell(
                    "-" + money(invoice.getAmountRefundedMinor(), invoice.getCurrency()), mono, false));
        }

        // The inner cells all draw their own hairlines, so the card outline goes on a wrapper.
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);
        card.setSpacingAfter(22);
        PdfPCell shell = new PdfPCell(inner);
        shell.setBorderColor(HAIRLINE);
        shell.setBorderWidth(0.7f);
        shell.setPadding(0);
        card.addCell(shell);
        return card;
    }

    /** Tinted panel of payment references, matching the design's "Payment details" block. */
    private PdfPTable paymentDetails(SubscriptionInvoice invoice, InvoiceContext ctx, boolean mor) {
        PdfPTable panel = new PdfPTable(1);
        panel.setWidthPercentage(100);
        panel.setSpacingAfter(20);

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, LABEL);
        Font keyFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, SUBTLE);
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK);
        Font monoFont = FontFactory.getFont(FontFactory.COURIER, 8, INK);

        PdfPTable rows = new PdfPTable(new float[] {1f, 1f});
        rows.setWidthPercentage(100);

        rows.addCell(detailCell("Status", statusLabel(invoice.getStatus()), keyFont,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f,
                        invoice.getStatus() == InvoiceStatus.PAID ? PAID_FG : INK)));
        if (mor) {
            rows.addCell(detailCell("Issued by", presentation.merchantOfRecordName(), keyFont, valFont));
        } else {
            rows.addCell(detailCell("Issued by", presentation.sellerName(), keyFont, valFont));
        }
        if (ctx.paymentId() != null) {
            rows.addCell(detailCell("Payment ID", ctx.paymentId(), keyFont, monoFont));
        }
        // The Merchant of Record's own invoice number (Dodo's invoice_id). A customer chasing a
        // payment with the MoR is asked for this, not for our internal receipt number. The
        // issuer is already named in the "Issued by" row above, so this stays simply "Invoice"
        // — prefixing it with the MoR name wraps the narrow label column onto three lines.
        if (invoice.getOfficialInvoiceNumber() != null) {
            rows.addCell(detailCell(
                    "Invoice", invoice.getOfficialInvoiceNumber(), keyFont, monoFont));
        }
        // Keep the two-column grid balanced so a lone final entry does not leave a ragged cell.
        if (rows.getRows().size() * 2 != rows.size()) {
            rows.addCell(blank());
        }

        PdfPCell shell = new PdfPCell();
        shell.setBorder(0);
        shell.setBackgroundColor(PANEL);
        shell.setPadding(16);
        shell.addElement(new Paragraph("PAYMENT DETAILS", labelFont));
        rows.setSpacingBefore(10);
        shell.addElement(rows);
        panel.addCell(shell);
        return panel;
    }

    /**
     * Closing note, separated from the panel above by a hairline. Rendered as a table rather than
     * a bare Paragraph so it stays anchored below the preceding tables in the content stream.
     */
    private PdfPTable footerNote(boolean mor, Font noteFont) {
        String text = mor
                ? "This is a payment receipt for your records. The official tax invoice for this "
                        + "purchase was issued by " + presentation.merchantOfRecordName()
                        + ". Questions? Reach us at " + presentation.supportEmail() + "."
                : "Thank you for your business. Questions? Reach us at "
                        + presentation.supportEmail() + ".";

        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        Paragraph note = new Paragraph(text, noteFont);
        note.setLeading(13f);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setBorderWidthTop(0.7f);
        cell.setBorderColorTop(HAIRLINE);
        cell.setPaddingTop(14);
        cell.addElement(note);
        table.addCell(cell);
        return table;
    }

    /** Dates the charge covers, shown under the line-item description. */
    private static String coveredPeriod(SubscriptionInvoice invoice, InvoiceContext ctx) {
        Instant start = ctx.periodStart() != null ? ctx.periodStart() : invoice.getPeriodStart();
        Instant end = ctx.periodEnd() != null ? ctx.periodEnd() : invoice.getPeriodEnd();
        if (start == null || end == null) {
            return null;
        }
        return DATE.format(start) + " – "
                + DATE.format(end.minus(1, java.time.temporal.ChronoUnit.DAYS));
    }

    private static String statusLabel(InvoiceStatus status) {
        if (status == null) {
            return "—";
        }
        String name = status.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Loads the brand mark from resources; the receipt still renders if it is missing. */
    private static Image brandMark() {
        try (InputStream in = PdfInvoiceGenerator.class
                .getResourceAsStream("/assets/receipt/bunnycal-mark.png")) {
            if (in == null) {
                return null;
            }
            return Image.getInstance(in.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private String subscriptionName(String planName) {
        if (planName == null || planName.isBlank()) {
            return presentation.sellerName() + " Subscription";
        }
        if (planName.toLowerCase(Locale.ROOT).startsWith(
                presentation.sellerName().toLowerCase(Locale.ROOT))) {
            return planName;
        }
        return presentation.sellerName() + " " + planName;
    }

    /**
     * Formats a minor-unit amount prefixed by its currency. A symbol is used only when the PDF
     * base fonts can actually draw it — they are WinAnsi-encoded, so glyphs outside that set
     * (₹ among them) would be dropped silently and leave the amount with no currency at all.
     * Anything unencodable falls back to the ISO code.
     */
    private static String money(long minor, String currency) {
        java.text.NumberFormat fmt = java.text.NumberFormat.getNumberInstance(Locale.US);
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        String amount = fmt.format(minor / 100.0);
        String prefix = currencyPrefix(currency);
        return prefix.isEmpty() ? amount : prefix + " " + amount;
    }

    private static String currencyPrefix(String currency) {
        if (currency == null || currency.isBlank()) {
            return "";
        }
        String code = currency.toUpperCase(Locale.ROOT);
        try {
            String symbol = Currency.getInstance(code).getSymbol(Locale.US);
            if (symbol == null || symbol.isBlank() || !isWinAnsiEncodable(symbol)) {
                return code;
            }
            return symbol;
        } catch (IllegalArgumentException e) {
            return code;
        }
    }

    /** True when every character has a glyph in the PDF base-font encoding. */
    private static boolean isWinAnsiEncodable(String text) {
        return java.nio.charset.Charset.forName("windows-1252").newEncoder().canEncode(text);
    }

    private static PdfPCell blank() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        return cell;
    }

    private static PdfPCell labelValueCell(String labelText, String valueText, Font label, Font value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPaddingBottom(6);
        cell.addElement(new Paragraph(labelText, label));
        Paragraph v = new Paragraph(valueText, value);
        v.setSpacingBefore(3);
        cell.addElement(v);
        return cell;
    }

    private static PdfPCell cardHeaderCell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setBackgroundColor(PANEL);
        cell.setHorizontalAlignment(alignment);
        cell.setPaddingTop(9);
        cell.setPaddingBottom(9);
        cell.setPaddingLeft(16);
        cell.setPaddingRight(16);
        return cell;
    }

    private static PdfPCell secondaryLabel(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setBorderWidthTop(0.7f);
        cell.setBorderColorTop(HAIRLINE);
        cell.setPaddingLeft(16);
        cell.setPaddingTop(10);
        cell.setPaddingBottom(10);
        return cell;
    }

    private static PdfPCell amountCell(String text, Font font, boolean firstRow) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setBorderWidthTop(0.7f);
        cell.setBorderColorTop(HAIRLINE);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPaddingRight(16);
        cell.setPaddingTop(firstRow ? 13 : 10);
        cell.setPaddingBottom(firstRow ? 13 : 10);
        return cell;
    }

    private static PdfPCell detailCell(String key, String value, Font keyFont, Font valueFont) {
        // Provider identifiers are long unbroken tokens; the value column gets the larger share
        // so they sit on one line rather than splitting mid-token.
        PdfPTable row = new PdfPTable(new float[] {0.72f, 2f});
        row.setWidthPercentage(100);

        PdfPCell k = new PdfPCell(new Phrase(key, keyFont));
        k.setBorder(0);
        k.setPaddingBottom(7);
        k.setVerticalAlignment(Element.ALIGN_MIDDLE);
        row.addCell(k);

        PdfPCell v = new PdfPCell(new Phrase(value, valueFont));
        v.setBorder(0);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        v.setPaddingBottom(7);
        v.setPaddingLeft(4);
        row.addCell(v);

        PdfPCell wrapper = new PdfPCell(row);
        wrapper.setBorder(0);
        wrapper.setPaddingRight(14);
        return wrapper;
    }
}
