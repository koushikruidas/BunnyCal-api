package io.bunnycal.admin.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bunnycal.admin.audit.AdminAuditService;
import io.bunnycal.admin.settings.dto.AppSettingDtos.AppSettingDto;
import io.bunnycal.admin.settings.dto.AppSettingDtos.UpdateSettingRequest;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.payments.config.BillingProperties;
import io.bunnycal.payments.config.DodoProperties;
import io.bunnycal.payments.config.InvoicePresentationProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-visible dynamic settings registry. First pass is deliberately explicit: only
 * non-secret operational keys are writable. Secret rows are displayed as masked metadata
 * so operators can see that the setting exists without moving secrets into the database.
 */
@Service
public class AppSettingsService {

    private static final String TARGET_TYPE = "APP_SETTING";
    private static final JsonNode MASKED = new ObjectMapper().getNodeFactory().textNode("********");

    private final AppSettingRepository repository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;
    private final Map<String, SettingDefinition> definitions;

    public AppSettingsService(AppSettingRepository repository,
                              UserRepository userRepository,
                              AdminAuditService auditService,
                              ObjectMapper objectMapper,
                              BillingProperties billingProperties,
                              DodoProperties dodoProperties,
                              InvoicePresentationProperties invoicePresentationProperties) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.definitions = definitions(billingProperties, dodoProperties, invoicePresentationProperties);
    }

    @Transactional(readOnly = true)
    public List<AppSettingDto> list(SettingCategory category) {
        Map<String, AppSetting> persisted = new LinkedHashMap<>();
        List<AppSetting> rows = category == null
                ? repository.findAllByOrderByCategoryAscKeyAsc()
                : repository.findByCategoryOrderByKeyAsc(category);
        rows.forEach(row -> persisted.put(row.getKey(), row));

        return definitions.values().stream()
                .filter(def -> category == null || def.category() == category)
                .map(def -> toDto(def, persisted.get(def.key())))
                .sorted(Comparator.comparing(AppSettingDto::category).thenComparing(AppSettingDto::key))
                .toList();
    }

    @Transactional(readOnly = true)
    public AppSettingDto get(String key) {
        SettingDefinition definition = requireDefinition(key);
        return toDto(definition, repository.findById(key).orElse(null));
    }

    @Transactional
    public AppSettingDto update(UUID adminId, String key, UpdateSettingRequest request) {
        SettingDefinition definition = requireDefinition(key);
        if (definition.secret()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Secret settings are read-only and must be changed in the secret manager.");
        }
        requireReason(request.reason());
        if (request.value() == null || request.value().isMissingNode()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "value is required.");
        }
        validateType(definition, request.value());

        AppSetting row = repository.findById(key).orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(key);
            created.setCategory(definition.category());
            created.setDescription(definition.description());
            created.setSecret(false);
            return created;
        });
        AppSettingDto before = toDto(definition, row.getValueJson() == null ? null : row);

        row.setValueJson(toJson(request.value()));
        row.setCategory(definition.category());
        row.setDescription(definition.description());
        row.setSecret(false);
        row.setUpdatedBy(adminId);
        AppSetting saved = repository.save(row);
        AppSettingDto after = toDto(definition, saved);
        audit(adminId, "APP_SETTING_UPDATE", key, request.reason(), before, after);
        return after;
    }

    private AppSettingDto toDto(SettingDefinition definition, AppSetting row) {
        boolean persisted = row != null && row.getValueJson() != null;
        JsonNode value = definition.secret()
                ? MASKED
                : persisted ? parse(row.getValueJson()) : definition.fallbackValue();
        return new AppSettingDto(
                definition.key(),
                value,
                definition.category(),
                row != null && row.getDescription() != null ? row.getDescription() : definition.description(),
                definition.secret(),
                !definition.secret(),
                persisted,
                persisted ? "DATABASE" : "FALLBACK",
                row == null ? null : row.getUpdatedBy(),
                row == null ? null : row.getUpdatedAt());
    }

    private SettingDefinition requireDefinition(String key) {
        SettingDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Setting not found.");
        }
        return definition;
    }

    private void validateType(SettingDefinition definition, JsonNode value) {
        boolean valid = switch (definition.type()) {
            case BOOLEAN -> value.isBoolean();
            case NUMBER -> value.isNumber();
            case STRING -> value.isTextual() || value.isNull();
            case OBJECT -> value.isObject();
        };
        if (!valid) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "value must be a " + definition.type().name().toLowerCase() + ".");
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid app setting JSON", e);
        }
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Setting value is not serializable", e);
        }
    }

    private void audit(UUID adminId, String action, String key, String reason, Object before, Object after) {
        String email = userRepository.findById(adminId).map(User::getEmail).orElse(null);
        auditService.record(adminId, email, action, TARGET_TYPE, null, reason, before, after);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "reason is required.");
        }
    }

    private Map<String, SettingDefinition> definitions(BillingProperties billing,
                                                       DodoProperties dodo,
                                                       InvoicePresentationProperties invoice) {
        Map<String, SettingDefinition> map = new LinkedHashMap<>();
        add(map, "billing.enabled", SettingCategory.BILLING, ValueType.BOOLEAN, false,
                "Whether subscription billing is enabled.", objectMapper.getNodeFactory().booleanNode(billing.enabled()));
        add(map, "billing.provider", SettingCategory.BILLING, ValueType.STRING, false,
                "Active payment provider identifier.", text(billing.provider()));
        add(map, "billing.grace_days", SettingCategory.BILLING, ValueType.NUMBER, false,
                "Payment failure grace period in days.", objectMapper.getNodeFactory().numberNode(billing.graceDays()));
        add(map, "billing.fees.processor_percent_bps", SettingCategory.BILLING, ValueType.NUMBER, false,
                "Estimated processor or MoR fee in basis points for revenue reporting.",
                objectMapper.getNodeFactory().numberNode(billing.fees().processorPercentBps()));
        add(map, "billing.notifications.enabled", SettingCategory.EMAILS, ValueType.BOOLEAN, false,
                "Whether billing notification emails are enabled.", objectMapper.getNodeFactory().booleanNode(billing.notifications().enabled()));
        add(map, "billing.notifications.from", SettingCategory.EMAILS, ValueType.STRING, false,
                "From address for billing notification emails.", text(billing.notifications().from()));
        add(map, "billing.invoice_presentation.mode", SettingCategory.BILLING, ValueType.STRING, false,
                "Invoice presentation mode: DIRECT_MERCHANT or MOR_RECORD_ONLY.", text(invoice.mode().name()));
        add(map, "billing.invoice_presentation.seller_name", SettingCategory.BILLING, ValueType.STRING, false,
                "Seller display name shown on billing documents.", text(invoice.sellerName()));
        add(map, "billing.invoice_presentation.merchant_of_record_name", SettingCategory.BILLING, ValueType.STRING, false,
                "Merchant of Record display name when MoR mode is active.", text(invoice.merchantOfRecordName()));
        add(map, "billing.dodo.test_mode", SettingCategory.DODO, ValueType.BOOLEAN, false,
                "Whether Dodo Payments uses the test environment.", objectMapper.getNodeFactory().booleanNode(dodo.testMode()));
        add(map, "billing.dodo.api_key", SettingCategory.DODO, ValueType.STRING, true,
                "Dodo API key. Stored in environment or secret manager; masked and read-only here.", MASKED);
        add(map, "billing.dodo.webhook_secret", SettingCategory.DODO, ValueType.STRING, true,
                "Dodo webhook signing secret. Stored in environment or secret manager; masked and read-only here.", MASKED);
        return map;
    }

    private void add(Map<String, SettingDefinition> map, String key, SettingCategory category, ValueType type,
                     boolean secret, String description, JsonNode fallbackValue) {
        map.put(key, new SettingDefinition(key, category, type, secret, description, fallbackValue));
    }

    private JsonNode text(String value) {
        return value == null ? objectMapper.nullNode() : objectMapper.getNodeFactory().textNode(value);
    }

    private record SettingDefinition(
            String key,
            SettingCategory category,
            ValueType type,
            boolean secret,
            String description,
            JsonNode fallbackValue) {
    }

    private enum ValueType {
        BOOLEAN,
        NUMBER,
        STRING,
        OBJECT
    }
}
