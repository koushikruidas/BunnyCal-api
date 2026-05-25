package com.daedalussystems.easySchedule.booking.draft.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityOverride;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityRule;
import com.daedalussystems.easySchedule.availability.domain.AvailabilityMode;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.service.EventTypeOrchestrationJsonCodec;
import com.daedalussystems.easySchedule.availability.service.EventTypeOrchestrationNormalizer;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityOverrideCreateRequest;
import com.daedalussystems.easySchedule.availability.dto.AvailabilityRuleRequest;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityOverrideRepository;
import com.daedalussystems.easySchedule.availability.repository.AvailabilityRuleRepository;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.draft.domain.DraftLifecycleState;
import com.daedalussystems.easySchedule.booking.draft.domain.HostDraft;
import com.daedalussystems.easySchedule.booking.draft.dto.DraftCreateRequest;
import com.daedalussystems.easySchedule.booking.draft.dto.DraftResponse;
import com.daedalussystems.easySchedule.booking.draft.dto.DraftUpdateRequest;
import com.daedalussystems.easySchedule.booking.draft.repository.HostDraftRepository;
import com.daedalussystems.easySchedule.booking.repository.BookingRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.enums.UserStatus;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftOrganizerService {
    private final HostDraftRepository hostDraftRepository;
    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final AvailabilityRuleRepository availabilityRuleRepository;
    private final AvailabilityOverrideRepository availabilityOverrideRepository;
    private final BookingRepository bookingRepository;
    private final EventTypeOrchestrationNormalizer orchestrationNormalizer;
    private final EventTypeOrchestrationJsonCodec orchestrationJsonCodec;
    private final Duration ttl;

    public DraftOrganizerService(HostDraftRepository hostDraftRepository,
                                 UserRepository userRepository,
                                 EventTypeRepository eventTypeRepository,
                                 AvailabilityRuleRepository availabilityRuleRepository,
                                 AvailabilityOverrideRepository availabilityOverrideRepository,
                                 BookingRepository bookingRepository,
                                 EventTypeOrchestrationNormalizer orchestrationNormalizer,
                                 EventTypeOrchestrationJsonCodec orchestrationJsonCodec,
                                 @Value("${draft.ttl.days:21}") long ttlDays) {
        this.hostDraftRepository = hostDraftRepository;
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.availabilityRuleRepository = availabilityRuleRepository;
        this.availabilityOverrideRepository = availabilityOverrideRepository;
        this.bookingRepository = bookingRepository;
        this.orchestrationNormalizer = orchestrationNormalizer;
        this.orchestrationJsonCodec = orchestrationJsonCodec;
        this.ttl = Duration.ofDays(Math.max(1, ttlDays));
    }

    @Transactional
    public DraftCreated create(DraftCreateRequest request) {
        validateCreate(request);
        Instant now = Instant.now();
        String slug = uniqueSlug(request.eventName());
        String token = UUID.randomUUID().toString() + UUID.randomUUID();

        User shadowUser = userRepository.save(User.builder()
                .email("draft-" + UUID.randomUUID() + "@draft.local")
                .username("draft-" + UUID.randomUUID().toString().substring(0, 8))
                .name(blankToDefault(request.displayName(), "Draft Host"))
                .timezone(request.timezone())
                .status(UserStatus.ACTIVE)
                .build());

        EventTypeOrchestrationNormalizer.NormalizedOrchestration orchestration =
                orchestrationNormalizer.normalizeForDraftMutation(
                        shadowUser.getId(),
                        null,
                        request.availabilityCalendars(),
                        request.conference(),
                        List.of());

        AvailabilityMode availabilityMode = (request.availabilityCalendars() != null && !request.availabilityCalendars().isEmpty())
                ? AvailabilityMode.SELECTED
                : AvailabilityMode.ALL_CONNECTED;

        EventType eventType = eventTypeRepository.save(EventType.builder()
                .userId(shadowUser.getId())
                .name(request.eventName().trim())
                .description(trimToNull(request.description()))
                .location(trimToNull(request.location()))
                .organizerCalendarConnectionId(orchestration.syncConnectionId())
                .availabilityCalendarsJson(orchestrationJsonCodec.serializeAvailabilityBindings(orchestration.availabilityBindings()))
                .availabilityMode(availabilityMode)
                .conferencingProvider(orchestration.conferencing().provider())
                .customConferenceUrl(orchestration.conferencing().customUrl())
                .slug(slug)
                .duration(Duration.ofMinutes(request.durationMinutes()))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(request.slotIntervalMinutes() == null ? request.durationMinutes() : request.slotIntervalMinutes()))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(90))
                .holdDuration(Duration.ofMinutes(request.holdDurationMinutes() == null ? 10 : request.holdDurationMinutes()))
                .build());

        replaceRulesAndOverrides(shadowUser.getId(), request.rules(), request.overrides());

        HostDraft draft = new HostDraft();
        draft.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        draft.setDisplayName(blankToDefault(request.displayName(), "Draft Host"));
        draft.setTimezone(request.timezone());
        draft.setPublicSlug(slug);
        draft.setEventName(request.eventName().trim());
        draft.setDurationMinutes(request.durationMinutes());
        draft.setConfigJson("{}");
        draft.setExpiresAt(now.plus(ttl));
        draft.setState(DraftLifecycleState.ACTIVE);
        draft.setLastActivityAt(now);
        draft.setShadowUserId(shadowUser.getId());
        draft.setShadowEventTypeId(eventType.getId());
        draft.setManagementTokenHash(hash(token));
        hostDraftRepository.save(draft);

        return new DraftCreated(toResponse(draft), token);
    }

    @Transactional(readOnly = true)
    public DraftResponse getForManage(String slug, String token) {
        HostDraft draft = requireBySlugToken(slug, token);
        return toResponse(draft);
    }

    @Transactional
    public DraftResponse update(String slug, String token, DraftUpdateRequest request) {
        HostDraft draft = requireBySlugToken(slug, token);
        if (draft.getState() != DraftLifecycleState.ACTIVE) {
            throw new CustomException(ErrorCode.INVALID_STATE_TRANSITION, "Draft is not editable.");
        }
        if (request.displayName() != null && !request.displayName().isBlank()) {
            draft.setDisplayName(request.displayName().trim());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            draft.setTimezone(request.timezone().trim());
        }
        if (request.eventName() != null && !request.eventName().isBlank()) {
            draft.setEventName(request.eventName().trim());
        }
        if (request.durationMinutes() != null && request.durationMinutes() > 0) {
            draft.setDurationMinutes(request.durationMinutes());
        }
        draft.setLastActivityAt(Instant.now());
        draft.setExpiresAt(Instant.now().plus(ttl));

        User shadow = userRepository.findById(draft.getShadowUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft shadow user missing."));
        shadow.setName(draft.getDisplayName());
        shadow.setTimezone(draft.getTimezone());
        userRepository.save(shadow);

        EventType eventType = eventTypeRepository.findByIdAndUserId(draft.getShadowEventTypeId(), draft.getShadowUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft shadow event type missing."));
        EventTypeOrchestrationNormalizer.NormalizedOrchestration orchestration =
                orchestrationNormalizer.normalizeForDraftMutation(
                        draft.getShadowUserId(),
                        eventType,
                        request.availabilityCalendars(),
                        request.conference(),
                        orchestrationJsonCodec.deserializeAvailabilityBindings(eventType.getAvailabilityCalendarsJson()));
        eventType.setName(draft.getEventName());
        if (request.description() != null) {
            eventType.setDescription(trimToNull(request.description()));
        }
        if (request.location() != null) {
            eventType.setLocation(trimToNull(request.location()));
        }
        eventType.setDuration(Duration.ofMinutes(draft.getDurationMinutes()));
        if (request.slotIntervalMinutes() != null && request.slotIntervalMinutes() > 0) {
            eventType.setSlotInterval(Duration.ofMinutes(request.slotIntervalMinutes()));
        }
        if (request.holdDurationMinutes() != null && request.holdDurationMinutes() > 0) {
            eventType.setHoldDuration(Duration.ofMinutes(request.holdDurationMinutes()));
        }
        eventType.setOrganizerCalendarConnectionId(orchestration.syncConnectionId());
        eventType.setAvailabilityCalendarsJson(orchestrationJsonCodec.serializeAvailabilityBindings(orchestration.availabilityBindings()));
        eventType.setAvailabilityMode(orchestration.availabilityBindings().isEmpty() ? AvailabilityMode.ALL_CONNECTED : AvailabilityMode.SELECTED);
        eventType.setConferencingProvider(orchestration.conferencing().provider());
        eventType.setCustomConferenceUrl(orchestration.conferencing().customUrl());
        eventTypeRepository.save(eventType);
        replaceRulesAndOverrides(draft.getShadowUserId(), request.rules(), request.overrides());

        hostDraftRepository.save(draft);
        return toResponse(draft);
    }

    @Transactional
    public DraftResponse claim(String slug, UUID userId) {
        HostDraft draft = hostDraftRepository.findByPublicSlug(slug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft not found."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        if (!user.getEmail().equalsIgnoreCase(draft.getEmail())) {
            throw new CustomException(ErrorCode.FORBIDDEN, "Claim email does not match draft email.");
        }
        draft.setClaimedUserId(userId);
        draft.setClaimedAt(Instant.now());
        draft.setState(DraftLifecycleState.CLAIMED);
        draft.setLastActivityAt(Instant.now());
        hostDraftRepository.save(draft);
        return toResponse(draft);
    }

    @Transactional
    public int runReaper() {
        List<HostDraft> activeExpired = hostDraftRepository.findTop200ByStateAndExpiresAtBeforeOrderByExpiresAtAsc(
                DraftLifecycleState.ACTIVE, Instant.now());
        int touched = 0;
        for (HostDraft draft : activeExpired) {
            long bookingCount = bookingRepository.countByHostId(draft.getShadowUserId());
            if (bookingCount > 0) {
                draft.setState(DraftLifecycleState.ARCHIVED_BOOKED);
                draft.setDeactivatedAt(Instant.now());
            } else {
                draft.setState(DraftLifecycleState.EXPIRED_UNBOOKED);
                draft.setDeactivatedAt(Instant.now());
            }
            draft.setLastActivityAt(Instant.now());
            hostDraftRepository.save(draft);
            touched++;
        }
        return touched;
    }

    private HostDraft requireBySlugToken(String slug, String token) {
        if (token == null || token.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "Draft token is required.");
        }
        return hostDraftRepository.findByPublicSlugAndManagementTokenHash(slug, hash(token))
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft not found."));
    }

    private void replaceRulesAndOverrides(UUID userId,
                                          List<AvailabilityRuleRequest> rules,
                                          List<AvailabilityOverrideCreateRequest> overrides) {
        availabilityRuleRepository.deleteByUserId(userId);
        availabilityOverrideRepository.deleteByUserId(userId);

        if (rules != null && !rules.isEmpty()) {
            List<AvailabilityRule> toRules = rules.stream()
                    .map(rule -> AvailabilityRule.builder()
                            .userId(userId)
                            .dayOfWeek(rule.getDayOfWeek())
                            .startTime(rule.getStartTime())
                            .endTime(rule.getEndTime())
                            .build())
                    .toList();
            availabilityRuleRepository.saveAll(toRules);
        }
        if (overrides != null && !overrides.isEmpty()) {
            List<AvailabilityOverride> toOverrides = overrides.stream()
                    .map(ov -> AvailabilityOverride.builder()
                            .userId(userId)
                            .date(ov.getDate())
                            .isAvailable(ov.isAvailable())
                            .startTime(ov.getStartTime())
                            .endTime(ov.getEndTime())
                            .build())
                    .toList();
            availabilityOverrideRepository.saveAll(toOverrides);
        }
    }

    private void validateCreate(DraftCreateRequest request) {
        if (request == null
                || request.email() == null || request.email().isBlank()
                || request.timezone() == null || request.timezone().isBlank()
                || request.eventName() == null || request.eventName().isBlank()
                || request.durationMinutes() == null || request.durationMinutes() <= 0) {
            throw new CustomException(ErrorCode.VALIDATION_ERROR, "Invalid draft create request.");
        }
    }

    private String uniqueSlug(String eventName) {
        String base = normalizeSlug(eventName);
        String candidate = base;
        int i = 1;
        while (hostDraftRepository.findByPublicSlug(candidate).isPresent()) {
            i++;
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private static String normalizeSlug(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+", "").replaceAll("-+$", "");
        return s.isBlank() ? "draft" : s;
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static DraftResponse toResponse(HostDraft draft) {
        return new DraftResponse(
                draft.getPublicSlug(),
                "/public/d/" + draft.getPublicSlug(),
                draft.getEmail(),
                draft.getDisplayName(),
                draft.getTimezone(),
                draft.getEventName(),
                draft.getDurationMinutes(),
                draft.getState(),
                draft.getExpiresAt()
        );
    }

    public record DraftCreated(DraftResponse draft, String managementToken) {}
}
