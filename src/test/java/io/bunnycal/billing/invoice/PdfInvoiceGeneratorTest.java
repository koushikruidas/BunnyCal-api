package io.bunnycal.billing.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import io.bunnycal.billing.domain.InvoiceStatus;
import io.bunnycal.billing.domain.SubscriptionInvoice;
import io.bunnycal.payments.config.InvoicePresentationProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdfInvoiceGeneratorTest {

    @Test
    void merchantOfRecordDocumentIsClearlyAReceipt() throws Exception {
        PdfInvoiceGenerator generator = new PdfInvoiceGenerator(
                new InvoicePresentationProperties(
                        InvoicePresentationProperties.Mode.MOR_RECORD_ONLY,
                        "BunnyCal",
                        "Dodo Payments",
                        "support@bunnycal.io"));
        SubscriptionInvoice receipt = SubscriptionInvoice.builder()
                .invoiceNumber("BCR-000001")
                .officialInvoiceNumber("inv_123")
                .providerInvoiceId("pay_123")
                .status(InvoiceStatus.PAID)
                .subtotalMinor(500)
                .totalMinor(500)
                .currency("USD")
                .issuedAt(Instant.parse("2026-07-24T00:00:00Z"))
                .build();

        byte[] bytes = generator.generate(
                receipt,
                new PdfInvoiceGenerator.InvoiceContext(
                        "Ada Lovelace", "ada@example.com", "Professional", "pay_123",
                        Instant.parse("2026-07-24T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")));
        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);

        assertThat(text)
                .contains("PAYMENT RECEIPT")
                .contains("RECEIPT NO.")
                .contains("BCR-000001")
                .contains("BunnyCal Professional")
                .contains("24 Jul 2026 – 23 Aug 2026")
                .contains("NEXT RENEWAL")
                .contains("24 Aug 2026")
                // Cycle sub-line under the subscription, read from the covered period.
                .contains("Monthly plan")
                .contains("PAID")
                .contains("Payment ID")
                .contains("pay_123")
                // The MoR's own invoice number is what the customer is asked for when chasing a
                // payment with them, so it sits in the details panel beside the payment ID. The
                // issuer is named by the adjacent "Issued by" row.
                .contains("Invoice")
                .contains("inv_123")
                .contains("Issued by")
                .contains("Dodo Payments")
                .contains("support@bunnycal.io")
                .doesNotContain("Payment Summary")
                .doesNotContain("Official receipt")
                // Tax is the Merchant of Record's to report; a zero-tax record must not print a
                // tax line at all.
                .doesNotContain("GST")
                .doesNotContain("VAT")
                .doesNotContain("Tax");
        reader.close();
    }

    /**
     * A receipt can exist before the MoR has issued its invoice number — the webhook carrying
     * invoice_id may not have arrived yet. The meta block must then simply omit the row rather than
     * printing a label with nothing under it.
     */
    @Test
    void omitsTheMerchantInvoiceRowWhenNoNumberIsKnownYet() throws Exception {
        PdfInvoiceGenerator generator = new PdfInvoiceGenerator(
                new InvoicePresentationProperties(
                        InvoicePresentationProperties.Mode.MOR_RECORD_ONLY,
                        "BunnyCal",
                        "Dodo Payments",
                        "support@bunnycal.io"));
        SubscriptionInvoice receipt = SubscriptionInvoice.builder()
                .invoiceNumber("BCR-000002")
                .providerInvoiceId("pay_456")
                .status(InvoiceStatus.PAID)
                .subtotalMinor(500)
                .totalMinor(500)
                .currency("USD")
                .issuedAt(Instant.parse("2026-07-24T00:00:00Z"))
                .build();

        byte[] bytes = generator.generate(
                receipt,
                new PdfInvoiceGenerator.InvoiceContext(
                        "Ada Lovelace", "ada@example.com", "Professional", "pay_456",
                        Instant.parse("2026-07-24T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z")));
        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);

        assertThat(text)
                .contains("PAYMENT RECEIPT")
                .contains("BCR-000002")
                // Still attributed to the MoR; only the number is absent.
                .contains("Issued by")
                .contains("Dodo Payments")
                .doesNotContain("inv_");
        reader.close();
    }

    /**
     * The redesign has a tax row, but BunnyCal charges no tax under Merchant-of-Record billing
     * (taxMinor is written as 0). The row must appear only for a record that actually carries a
     * tax amount, so the receipt never implies a charge that was not made.
     */
    @Test
    void rendersTheTaxRowOnlyWhenTheRecordCarriesTax() throws Exception {
        PdfInvoiceGenerator generator = new PdfInvoiceGenerator(
                new InvoicePresentationProperties(
                        InvoicePresentationProperties.Mode.DIRECT_MERCHANT,
                        "BunnyCal",
                        "Dodo Payments",
                        "support@bunnycal.io"));
        SubscriptionInvoice taxed = SubscriptionInvoice.builder()
                .invoiceNumber("BCR-000003")
                .status(InvoiceStatus.PAID)
                .subtotalMinor(475353)
                .taxMinor(85564)
                .totalMinor(560917)
                .currency("INR")
                .issuedAt(Instant.parse("2026-08-08T00:00:00Z"))
                .build();

        byte[] bytes = generator.generate(
                taxed,
                new PdfInvoiceGenerator.InvoiceContext(
                        "Ada Lovelace", "ada@example.com", "Professional", "pay_789",
                        Instant.parse("2026-08-08T00:00:00Z"),
                        Instant.parse("2027-08-08T00:00:00Z")));
        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);

        assertThat(text)
                .contains("Tax")
                // The PDF base fonts cannot draw ₹, so INR falls back to its ISO code rather than
                // dropping the symbol and leaving a bare number.
                .contains("INR 855.64")
                .contains("INR 5,609.17")
                .contains("Total due");
        reader.close();
    }
}
