package io.bunnycal.billing.invoice;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.config.InvoicePresentationProperties;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private static final Color PLUM = new Color(95, 61, 142);
    private static final Color MUTED = new Color(110, 110, 110);

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
        Document document = new Document(PageSize.A4, 48, 48, 56, 48);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            Font brandFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, PLUM);
            Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);
            Font label = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);
            Font value = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);

            // Dodo is always Merchant of Record. Do not require a second environment flag
            // to prevent BunnyCal accidentally presenting itself as the legal seller.
            boolean mor = presentation.isMerchantOfRecord()
                    || (billing != null && "dodo".equalsIgnoreCase(billing.provider()));

            Paragraph brand = new Paragraph(presentation.sellerName(), brandFont);
            document.add(brand);
            Paragraph sub = new Paragraph(mor ? "Payment Receipt" : "Invoice", label);
            sub.setSpacingAfter(mor ? 6 : 16);
            document.add(sub);

            // In Merchant-of-Record mode this document records payment but is not the legal
            // tax invoice. The MoR is the legal seller and issues that document.
            if (mor) {
                Paragraph note = new Paragraph(
                        "This is a payment receipt for your records. The official tax invoice for "
                                + "this purchase was issued by " + presentation.merchantOfRecordName() + ".",
                        label);
                note.setSpacingAfter(16);
                document.add(note);
            }

            // Meta block: number / dates / customer.
            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            meta.setSpacingAfter(20);
            meta.addCell(labelValueCell(mor ? "Receipt number" : "Invoice number",
                    invoice.getInvoiceNumber(), label, value));
            meta.addCell(labelValueCell("Issued", DATE.format(invoice.getIssuedAt()), label, value));
            meta.addCell(labelValueCell(mor ? "Customer" : "Billed to",
                    ctx.customerName() + "\n" + nullToEmpty(ctx.customerEmail()), label, value));
            Instant periodStart = ctx.periodStart() != null ? ctx.periodStart() : invoice.getPeriodStart();
            Instant periodEnd = ctx.periodEnd() != null ? ctx.periodEnd() : invoice.getPeriodEnd();
            String period = periodStart != null && periodEnd != null
                    ? DATE.format(periodStart) + " – "
                            + DATE.format(periodEnd.minus(1, java.time.temporal.ChronoUnit.DAYS))
                    : "—";
            meta.addCell(labelValueCell("Billing period", period, label, value));
            meta.addCell(labelValueCell("Subscription", subscriptionName(ctx.planName()), label, value));
            meta.addCell(labelValueCell("Next renewal",
                    periodEnd == null ? "—" : DATE.format(periodEnd), label, value));
            document.add(meta);

            // Line items.
            document.add(new Paragraph(mor ? "Payment" : "Summary", h2));
            PdfPTable items = new PdfPTable(new float[] {3f, 1f});
            items.setWidthPercentage(100);
            items.setSpacingBefore(8);
            items.addCell(headerCell("Description", label));
            items.addCell(headerCellRight("Amount", label));
            items.addCell(bodyCell(subscriptionName(ctx.planName()), value));
            items.addCell(bodyCellRight(money(invoice.getSubtotalMinor(), invoice.getCurrency()), value));

            if (invoice.getDiscountMinor() > 0) {
                items.addCell(bodyCell("Discount", value));
                items.addCell(bodyCellRight("-" + money(invoice.getDiscountMinor(), invoice.getCurrency()), value));
            }
            document.add(items);

            // Total.
            PdfPTable totals = new PdfPTable(new float[] {3f, 1f});
            totals.setWidthPercentage(100);
            totals.setSpacingBefore(10);
            totals.addCell(noBorderRight("Total paid", totalFont));
            totals.addCell(noBorderRight(money(invoice.getTotalMinor(), invoice.getCurrency()), totalFont));
            if (invoice.getAmountRefundedMinor() > 0) {
                totals.addCell(noBorderRight("Refunded", value));
                totals.addCell(noBorderRight("-" + money(invoice.getAmountRefundedMinor(), invoice.getCurrency()), value));
            }
            document.add(totals);

            Paragraph footer = new Paragraph(
                    "\nStatus: " + invoice.getStatus()
                            + (ctx.paymentId() != null ? "    Payment ID: " + ctx.paymentId() : ""),
                    label);
            footer.setSpacingBefore(24);
            document.add(footer);

            if (mor) {
                Paragraph issued = new Paragraph(
                        "Official invoice"
                                + (invoice.getOfficialInvoiceNumber() == null
                                        ? "" : ": " + invoice.getOfficialInvoiceNumber())
                                + "\nIssued by: " + presentation.merchantOfRecordName(),
                        label);
                issued.setSpacingBefore(4);
                document.add(issued);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            if (document.isOpen()) {
                document.close();
            }
            throw new IllegalStateException("Failed to render billing PDF for " + invoice.getInvoiceNumber(), e);
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

    private static String money(long minor, String currency) {
        java.text.NumberFormat fmt = java.text.NumberFormat.getNumberInstance(Locale.US);
        fmt.setMinimumFractionDigits(2);
        fmt.setMaximumFractionDigits(2);
        return currency + " " + fmt.format(minor / 100.0);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static PdfPCell labelValueCell(String labelText, String valueText, Font label, Font value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPaddingBottom(6);
        cell.addElement(new Paragraph(labelText, label));
        Paragraph v = new Paragraph(valueText, value);
        cell.addElement(v);
        return cell;
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setBorderWidthBottom(0.5f);
        cell.setBorderColorBottom(MUTED);
        cell.setPaddingBottom(6);
        return cell;
    }

    private static PdfPCell headerCellRight(String text, Font font) {
        PdfPCell cell = headerCell(text, font);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setPaddingTop(8);
        return cell;
    }

    private static PdfPCell bodyCellRight(String text, Font font) {
        PdfPCell cell = bodyCell(text, font);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }

    private static PdfPCell noBorderRight(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(0);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPaddingTop(4);
        return cell;
    }
}
