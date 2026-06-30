package io.bunnycal.admin.webhooks;

import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.webhooks.dto.AdminWebhookDto;
import io.bunnycal.common.api.ApiResponse;
import io.bunnycal.payments.webhook.WebhookEventStatus;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only webhook event viewer over {@code webhook_events}. OPERATIONS owns this surface.
 *
 * <p><b>Retry is intentionally not exposed yet.</b> The domain handler routes on
 * {@code ProviderWebhookEvent.data()} — pre-extracted fields that are NOT persisted (only the
 * raw payload is). A faithful reprocess therefore requires re-parsing the stored payload through
 * the provider's parser, which is currently coupled to signature verification inside
 * {@code DodoProvider.verifyWebhook}. Splitting parse from verify is a payments-core change that
 * should not ride along with a read-only admin module. Until then this viewer is observe-only;
 * provider redelivery remains the recovery path. (See docs/admin-portal-PROGRESS.md.)
 */
@RestController
@RequestMapping("/api/admin/webhooks")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OPERATIONS')")
public class AdminWebhookController {

    private final AdminWebhookService service;

    public AdminWebhookController(AdminWebhookService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminWebhookDto>> list(
            @RequestParam(value = "status", required = false) WebhookEventStatus status,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return ApiResponse.success(service.search(status, provider, type, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminWebhookDto> detail(@PathVariable UUID id) {
        return ApiResponse.success(service.detail(id));
    }
}
