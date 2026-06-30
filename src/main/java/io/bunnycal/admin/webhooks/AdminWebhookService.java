package io.bunnycal.admin.webhooks;

import io.bunnycal.admin.common.PageResponse;
import io.bunnycal.admin.webhooks.dto.AdminWebhookDto;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.webhook.WebhookEvent;
import io.bunnycal.payments.webhook.WebhookEventRepository;
import io.bunnycal.payments.webhook.WebhookEventStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only browser over {@code webhook_events}: list (filter by status/provider/type) and
 * detail (with raw payload). Retry is intentionally not offered here — see
 * {@link io.bunnycal.admin.webhooks.AdminWebhookController} for the rationale.
 */
@Service
public class AdminWebhookService {

    private static final int MAX_SIZE = 100;

    private final WebhookEventRepository repository;

    public AdminWebhookService(WebhookEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminWebhookDto> search(
            WebhookEventStatus status, String provider, String type, int page, int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);

        Specification<WebhookEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (provider != null && !provider.isBlank()) {
                predicates.add(cb.equal(root.get("provider"), provider.trim()));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("type"), type.trim()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return PageResponse.of(repository.findAll(spec, pageable), AdminWebhookDto::summary);
    }

    @Transactional(readOnly = true)
    public AdminWebhookDto detail(UUID id) {
        WebhookEvent event = repository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Webhook event not found."));
        return AdminWebhookDto.detail(event);
    }
}
