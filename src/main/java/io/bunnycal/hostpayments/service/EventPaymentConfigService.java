package io.bunnycal.hostpayments.service;

import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.hostpayments.domain.EventPaymentConfig;
import io.bunnycal.hostpayments.dto.EventPaymentRequest;
import io.bunnycal.hostpayments.dto.EventPaymentResponse;
import io.bunnycal.hostpayments.repository.EventPaymentConfigRepository;
import io.bunnycal.hostpayments.repository.HostPaymentConnectionRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.bunnycal.hostpayments.config.HostCommerceProperties;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class EventPaymentConfigService {
    private static final long MIN_USD_AMOUNT_MINOR = 50L;
    private final EventPaymentConfigRepository configRepository;
    private final HostPaymentConnectionRepository connectionRepository;
    private final HostCommerceProperties properties;

    @Autowired
    public EventPaymentConfigService(EventPaymentConfigRepository configRepository,
                                     HostPaymentConnectionRepository connectionRepository,
                                     HostCommerceProperties properties) {
        this.configRepository = configRepository;
        this.connectionRepository = connectionRepository;
        this.properties = properties;
    }

    /** Test constructor: focused domain tests exercise an enabled commerce environment. */
    public EventPaymentConfigService(EventPaymentConfigRepository configRepository,
                                     HostPaymentConnectionRepository connectionRepository) {
        this(configRepository, connectionRepository, new HostCommerceProperties(true, null, null));
    }

    @Transactional
    public void apply(UUID ownerId, UUID eventTypeId, EventPaymentRequest request) {
        if (request == null) return;
        if (!Boolean.TRUE.equals(request.enabled())) {
            configRepository.findById(eventTypeId).ifPresent(configRepository::delete);
            return;
        }
        if (!properties.enabled()) {
            throw new CustomException(ErrorCode.BILLING_DISABLED, "Paid event bookings are not enabled.");
        }
        if (request.connectionId() == null || request.amountMinor() == null || request.currency() == null) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR,
                    "Paid events require connectionId, amountMinor, and currency.");
        }
        String currency = request.currency().trim().toUpperCase(Locale.ROOT);
        if (!"USD".equals(currency)) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Only USD is supported for paid events.");
        }
        if (request.amountMinor() < MIN_USD_AMOUNT_MINOR) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Paid event amount must be at least 50 cents.");
        }
        var connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Payment connection not found."));
        if (!ownerId.equals(connection.getUserId())) throw new CustomException(ErrorCode.FORBIDDEN);
        if (!connection.ready()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Payment connection is not ready to accept charges.");
        }
        EventPaymentConfig config = configRepository.findById(eventTypeId)
                .orElseGet(() -> EventPaymentConfig.builder().eventTypeId(eventTypeId).build());
        config.setEnabled(true);
        config.setConnectionId(connection.getId());
        config.setAmountMinor(request.amountMinor());
        config.setCurrency(currency);
        configRepository.save(config);
    }

    @Transactional(readOnly = true)
    public EventPaymentResponse response(UUID eventTypeId) {
        return configRepository.findByEventTypeIdAndEnabledTrue(eventTypeId)
                .flatMap(config -> connectionRepository.findById(config.getConnectionId())
                        .map(connection -> new EventPaymentResponse(true, config.getConnectionId(),
                                config.getAmountMinor(), config.getCurrency(), connection.getProvider().name())))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public EventPaymentConfig requireBookable(UUID eventTypeId, UUID ownerId) {
        EventPaymentConfig config = configRepository.findByEventTypeIdAndEnabledTrue(eventTypeId)
                .orElse(null);
        if (config == null) return null;
        if (!properties.enabled()) throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        var connection = connectionRepository.findById(config.getConnectionId())
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED));
        if (!ownerId.equals(connection.getUserId()) || !connection.ready()) {
            throw new CustomException(ErrorCode.EVENT_TYPE_NOT_PUBLISHED);
        }
        return config;
    }
}
