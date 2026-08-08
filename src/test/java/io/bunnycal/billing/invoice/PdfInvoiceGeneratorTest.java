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
                        "Dodo Payments"));
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
                .contains("Payment Receipt")
                .contains("Receipt number")
                .contains("BCR-000001")
                .contains("BunnyCal Professional")
                .contains("24 Jul 2026 – 23 Aug 2026")
                .contains("Next renewal")
                .contains("24 Aug 2026")
                .contains("Payment ID: pay_123")
                // The MoR's own invoice number is what the customer is asked for when chasing a
                // payment with them, so it sits in the meta block, labelled by issuer.
                .contains("Dodo Payments invoice")
                .contains("inv_123")
                .contains("Issued by: Dodo Payments")
                .doesNotContain("Payment Summary")
                .doesNotContain("Official receipt")
                .doesNotContain("GST")
                .doesNotContain("VAT");
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
                        "Dodo Payments"));
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
                .contains("Payment Receipt")
                .contains("BCR-000002")
                // Still attributed to the MoR; only the number is absent.
                .contains("Issued by: Dodo Payments")
                .doesNotContain("Dodo Payments invoice");
        reader.close();
    }
}
