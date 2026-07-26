package io.bunnycal.billing.controller;

import io.bunnycal.billing.dto.BillingOverviewDto;
import io.bunnycal.billing.dto.InvoiceDto;
import io.bunnycal.billing.dto.PurchasablePlanDto;
import io.bunnycal.billing.dto.ReceiptDto;
import io.bunnycal.billing.service.BillingService;
import io.bunnycal.billing.service.InvoiceService;
import io.bunnycal.billing.service.SubscriptionService;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
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
    private final io.bunnycal.billing.service.PlanService planService;
    private final io.bunnycal.billing.service.PromotionService promotionService;
    private final io.bunnycal.billing.service.CheckoutAttemptService checkoutAttemptService;

    public BillingController(BillingService billingService,
                            SubscriptionService subscriptionService,
                            InvoiceService invoiceService,
                            io.bunnycal.billing.service.PlanService planService,
                            io.bunnycal.billing.service.PromotionService promotionService,
                            io.bunnycal.billing.service.CheckoutAttemptService checkoutAttemptService) {
        this.billingService = billingService;
        this.subscriptionService = subscriptionService;
        this.invoiceService = invoiceService;
        this.planService = planService;
        this.promotionService = promotionService;
        this.checkoutAttemptService = checkoutAttemptService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<BillingOverviewDto>> overview(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(billingService.getOverview(userId(auth))));
    }

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<List<PurchasablePlanDto>>> plans() {
        return ResponseEntity.ok(ApiResponse.success(
                planService.purchasablePlans().stream().map(PurchasablePlanDto::from).toList()));
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Map<String, String>>> checkout(
            Authentication auth, @RequestBody(required = false) CheckoutRequest request) {
        UUID planId = request == null ? null : request.planId();
        String promoCode = request == null ? null : request.promoCode();
        // Creates a durable checkout attempt then the provider session, so the payment can be
        // verified/recovered on return regardless of whether a webhook arrives. Keeps the
        // redirectUrl key for existing clients and adds the attempt id for the confirm-then-poll flow.
        var started = checkoutAttemptService.startCheckout(userId(auth), planId, promoCode);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "redirectUrl", started.redirectUrl(),
                "checkoutAttemptId", started.checkoutAttemptId().toString())));
    }

    /**
     * Called by the frontend after the provider redirect: performs a real provider read and applies
     * the result, returning the verified attempt status. This — not the redirect query params — is
     * what confirms a successful payment. Safe to poll.
     */
    @PostMapping("/checkout-attempts/{id}/reconcile")
    public ResponseEntity<ApiResponse<CheckoutAttemptStatusDto>> reconcileAttempt(
            Authentication auth, @PathVariable UUID id) {
        checkoutAttemptService.requireForUser(id, userId(auth));
        var attempt = checkoutAttemptService.reconcile(
                id, io.bunnycal.billing.service.ReconciliationSource.REDIRECT);
        return ResponseEntity.ok(ApiResponse.success(CheckoutAttemptStatusDto.from(attempt)));
    }

    @GetMapping("/checkout-attempts/{id}")
    public ResponseEntity<ApiResponse<CheckoutAttemptStatusDto>> getAttempt(
            Authentication auth, @PathVariable UUID id) {
        var attempt = checkoutAttemptService.requireForUser(id, userId(auth));
        return ResponseEntity.ok(ApiResponse.success(CheckoutAttemptStatusDto.from(attempt)));
    }

    /** Minimal status view of a checkout attempt for the confirm-then-poll UI. */
    public record CheckoutAttemptStatusDto(UUID id, String status) {
        static CheckoutAttemptStatusDto from(io.bunnycal.billing.domain.CheckoutAttempt a) {
            return new CheckoutAttemptStatusDto(a.getId(), a.getStatus().name());
        }
    }

    @PostMapping("/promo/validate")
    public ResponseEntity<ApiResponse<io.bunnycal.billing.dto.DiscountBreakdownDto>> validatePromo(
            Authentication auth, @RequestBody PromoValidateRequest request) {
        UUID uid = userId(auth);
        var plan = request == null || request.planId() == null
                ? planService.requireDefaultPlan()
                : planService.requirePurchasablePlan(request.planId());
        var subscription = subscriptionService.findLive(uid).orElse(null);
        return ResponseEntity.ok(ApiResponse.success(
                promotionService.preview(plan, request == null ? null : request.code(), subscription)));
    }

    public record CheckoutRequest(UUID planId, String promoCode) {
    }

    public record PromoValidateRequest(UUID planId, String code) {
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

    @GetMapping("/receipts")
    public ResponseEntity<ApiResponse<List<ReceiptDto>>> receipts(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success(
                invoiceService.listReceiptsForUser(userId(auth))));
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

    @GetMapping("/receipts/{receiptId}/pdf")
    public ResponseEntity<byte[]> receiptPdf(Authentication auth, @PathVariable UUID receiptId) {
        UUID uid = userId(auth);
        byte[] pdf = invoiceService.renderPdf(receiptId, uid);
        String filename = invoiceService.requireForUser(receiptId, uid).getInvoiceNumber() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    @GetMapping("/receipts/{receiptId}/official-invoice")
    public ResponseEntity<ApiResponse<Map<String, String>>> officialInvoice(
            Authentication auth,
            @PathVariable UUID receiptId) {
        var invoice = invoiceService.officialInvoice(receiptId, userId(auth));
        Map<String, String> result = new java.util.HashMap<>();
        result.put("downloadUrl", invoice.downloadUrl());
        if (invoice.invoiceNumber() != null) {
            result.put("invoiceNumber", invoice.invoiceNumber());
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/cancel")
    public ResponseEntity<ApiResponse<Void>> cancel(Authentication auth, @RequestBody CancelRequest request) {
        boolean atPeriodEnd = request == null || request.atPeriodEnd() == null || request.atPeriodEnd();
        String reason = request == null ? null : request.reason();
        subscriptionService.cancel(userId(auth), atPeriodEnd, reason);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    public record CancelRequest(Boolean atPeriodEnd, String reason) {
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
