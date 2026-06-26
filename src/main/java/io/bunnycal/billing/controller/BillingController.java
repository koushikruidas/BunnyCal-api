package io.bunnycal.billing.controller;

import io.bunnycal.billing.dto.BillingOverviewDto;
import io.bunnycal.billing.dto.InvoiceDto;
import io.bunnycal.billing.service.BillingService;
import io.bunnycal.billing.service.InvoiceService;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.provider.ProviderRequests.CheckoutSession;
import io.bunnycal.payments.provider.ProviderRequests.PortalSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated billing endpoints for the Settings → Billing page. Mutating, financial
 * truth still lives in webhooks; these endpoints only read state or initiate provider
 * sessions (checkout/portal) and request a cancellation.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billingService;
    private final SubscriptionService subscriptionService;
    private final InvoiceService invoiceService;

    public BillingController(BillingService billingService,
                            SubscriptionService subscriptionService,
                            InvoiceService invoiceService) {
        this.billingService = billingService;
        this.subscriptionService = subscriptionService;
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<BillingOverviewDto>> overview(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(billingService.getOverview(userId(auth))));
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Map<String, String>>> checkout(Authentication auth) {
        CheckoutSession session = subscriptionService.startCheckout(userId(auth));
        return ResponseEntity.ok(ApiResponse.success(Map.of("redirectUrl", session.redirectUrl())));
    }

    @PostMapping("/portal")
    public ResponseEntity<ApiResponse<Map<String, String>>> portal(Authentication auth) {
        PortalSession session = subscriptionService.openPortal(userId(auth));
        return ResponseEntity.ok(ApiResponse.success(Map.of("redirectUrl", session.redirectUrl())));
    }

    @GetMapping("/invoices")
    public ResponseEntity<ApiResponse<List<InvoiceDto>>> invoices(Authentication auth) {
        List<InvoiceDto> invoices = invoiceService.listForUser(userId(auth)).stream()
                .map(InvoiceDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(invoices));
    }

    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> invoicePdf(Authentication auth, @PathVariable UUID invoiceId) {
        UUID uid = userId(auth);
        byte[] pdf = invoiceService.renderPdf(invoiceId, uid);
        String filename = invoiceService.requireForUser(invoiceId, uid).getInvoiceNumber() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(Authentication auth, @RequestBody CancelRequest request) {
        boolean atPeriodEnd = request == null || request.atPeriodEnd() == null || request.atPeriodEnd();
        subscriptionService.cancel(userId(auth), atPeriodEnd);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record CancelRequest(Boolean atPeriodEnd) {
    }

    private UUID userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(principal.toString());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }
}
