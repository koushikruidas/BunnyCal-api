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
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders a branded invoice PDF on demand. Pure: takes the immutable invoice plus the
 * customer's display fields and returns bytes — no I/O or persistence. Phase 1 has no
 * tax line (tax_minor is always 0).
 */
@Component
public class PdfInvoiceGenerator {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final Color PLUM = new Color(95, 61, 142);
    private static final Color MUTED = new Color(110, 110, 110);

    /** Customer + plan display fields not stored on the invoice row. */
    public record InvoiceContext(String customerName, String customerEmail, String planName) {
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

            Paragraph brand = new Paragraph("BunnyCal", brandFont);
            document.add(brand);
            Paragraph sub = new Paragraph("Invoice", label);
            sub.setSpacingAfter(16);
            document.add(sub);

            // Meta block: number / dates / customer.
            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            meta.setSpacingAfter(20);
            meta.addCell(labelValueCell("Invoice number", invoice.getInvoiceNumber(), label, value));
            meta.addCell(labelValueCell("Issued", DATE.format(invoice.getIssuedAt()), label, value));
            meta.addCell(labelValueCell("Billed to",
                    ctx.customerName() + "\n" + nullToEmpty(ctx.customerEmail()), label, value));
            String period = invoice.getPeriodStart() != null && invoice.getPeriodEnd() != null
                    ? DATE.format(invoice.getPeriodStart()) + " – " + DATE.format(invoice.getPeriodEnd())
                    : "—";
            meta.addCell(labelValueCell("Billing period", period, label, value));
            document.add(meta);

            // Line items.
            document.add(new Paragraph("Summary", h2));
            PdfPTable items = new PdfPTable(new float[] {3f, 1f});
            items.setWidthPercentage(100);
            items.setSpacingBefore(8);
            items.addCell(headerCell("Description", label));
            items.addCell(headerCellRight("Amount", label));
            items.addCell(bodyCell(ctx.planName(), value));
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
                            + (invoice.getProviderInvoiceId() != null
                                    ? "    Payment ref: " + invoice.getProviderInvoiceId() : ""),
                    label);
            footer.setSpacingBefore(24);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            if (document.isOpen()) {
                document.close();
            }
            throw new IllegalStateException("Failed to render invoice PDF for " + invoice.getInvoiceNumber(), e);
        }
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
