package io.bunnycal.hostpayments.service;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.domain.HostPaymentConnection;
import io.bunnycal.hostpayments.domain.PaymentConnectionStatus;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import io.bunnycal.hostpayments.dto.PaymentConnectionResponse;
import io.bunnycal.hostpayments.provider.HostPaymentProvider;
import io.bunnycal.hostpayments.provider.HostPaymentProviderRegistry;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import io.bunnycal.payments.audit.PaymentAuditService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class PaymentConnectionService {
    private final HostPaymentConnectionRepository repository;
    private final UserRepository userRepository;
    private final HostPaymentProviderRegistry providers;
    private final PaymentAuditService auditService;

    public PaymentConnectionService(HostPaymentConnectionRepository repository, UserRepository userRepository,
                                    HostPaymentProviderRegistry providers, PaymentAuditService auditService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.providers = providers;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<PaymentConnectionResponse> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(this::response).toList();
    }

    @Transactional
    public String startOnboarding(UUID userId, PaymentProviderType providerType) {
        HostPaymentProvider provider = providers.require(providerType);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        HostPaymentConnection connection = repository.findByUserIdAndProvider(userId, providerType)
                .orElseGet(() -> {
                    var created = provider.createConnectedAccount(userId, user.getEmail(), user.getName());
                    return repository.save(HostPaymentConnection.builder()
                            .userId(userId).provider(providerType)
                            .providerAccountId(created.accountId()).status(PaymentConnectionStatus.ONBOARDING)
                            .chargesEnabled(created.chargesEnabled()).payoutsEnabled(created.payoutsEnabled())
                            .detailsSubmitted(created.detailsSubmitted()).restrictionReason(created.restrictionReason())
                            .build());
                });
        if (connection.getStatus() == PaymentConnectionStatus.DISCONNECTED) {
            connection.setStatus(PaymentConnectionStatus.ONBOARDING);
            repository.save(connection);
        }
        auditService.recordHostCommerce(PaymentAuditService.userActor(userId), "HostPaymentConnection",
                connection.getId(), "ONBOARDING_LINK_CREATED", null,
                java.util.Map.of("provider", connection.getProvider().name(), "status", connection.getStatus().name()));
        return provider.createOnboardingLink(connection.getProviderAccountId());
    }

    /** Retained for callers created during the Stripe-only phase. */
    public String startStripeOnboarding(UUID userId) {
        return startOnboarding(userId, PaymentProviderType.STRIPE);
    }

    @Transactional
    public PaymentConnectionResponse refresh(UUID userId, UUID connectionId) {
        HostPaymentConnection connection = requireOwned(userId, connectionId);
        HostPaymentProvider provider = providers.require(connection.getProvider());
        var current = provider.retrieveConnectedAccount(connection.getProviderAccountId());
        connection.setProviderAccountId(current.accountId());
        connection.setChargesEnabled(current.chargesEnabled());
        connection.setPayoutsEnabled(current.payoutsEnabled());
        connection.setDetailsSubmitted(current.detailsSubmitted());
        connection.setRestrictionReason(current.restrictionReason());
        connection.setStatus(current.ready() ? PaymentConnectionStatus.READY
                : current.detailsSubmitted() ? PaymentConnectionStatus.RESTRICTED : PaymentConnectionStatus.ONBOARDING);
        PaymentConnectionResponse response = response(repository.save(connection));
        auditService.recordHostCommerce(PaymentAuditService.userActor(userId), "HostPaymentConnection",
                connection.getId(), "CONNECTION_REFRESHED", null,
                java.util.Map.of("provider", connection.getProvider().name(), "status", connection.getStatus().name()));
        return response;
    }

    @Transactional
    public void disconnect(UUID userId, UUID connectionId) {
        HostPaymentConnection connection = requireOwned(userId, connectionId);
        connection.setStatus(PaymentConnectionStatus.DISCONNECTED);
        connection.setChargesEnabled(false);
        connection.setPayoutsEnabled(false);
        repository.save(connection);
        auditService.recordHostCommerce(PaymentAuditService.userActor(userId), "HostPaymentConnection",
                connection.getId(), "CONNECTION_DISCONNECTED", null,
                java.util.Map.of("provider", connection.getProvider().name(), "status", connection.getStatus().name()));
    }

    public HostPaymentConnection requireOwnedReady(UUID userId, UUID connectionId) {
        HostPaymentConnection connection = requireOwned(userId, connectionId);
        if (!connection.ready()) throw new CustomException(ErrorCode.VALIDATION_ERROR, "Payment connection is not ready.");
        return connection;
    }

    private HostPaymentConnection requireOwned(UUID userId, UUID connectionId) {
        HostPaymentConnection connection = repository.findById(connectionId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Payment connection not found."));
        if (!connection.getUserId().equals(userId)) throw new CustomException(ErrorCode.FORBIDDEN);
        return connection;
    }

    private PaymentConnectionResponse response(HostPaymentConnection connection) {
        return new PaymentConnectionResponse(connection.getId(), connection.getProvider().name(),
                connection.getStatus().name(), connection.isChargesEnabled(), connection.isPayoutsEnabled(),
                connection.isDetailsSubmitted(), connection.getRestrictionReason());
    }
}
