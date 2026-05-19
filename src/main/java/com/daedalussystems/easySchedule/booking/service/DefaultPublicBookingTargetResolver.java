package com.daedalussystems.easySchedule.booking.service;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.auth.repository.UserRepository;
import com.daedalussystems.easySchedule.availability.domain.EventType;
import com.daedalussystems.easySchedule.availability.repository.EventTypeRepository;
import com.daedalussystems.easySchedule.booking.draft.domain.DraftLifecycleState;
import com.daedalussystems.easySchedule.booking.draft.domain.HostDraft;
import com.daedalussystems.easySchedule.booking.draft.repository.HostDraftRepository;
import com.daedalussystems.easySchedule.common.enums.ErrorCode;
import com.daedalussystems.easySchedule.common.exception.CustomException;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class DefaultPublicBookingTargetResolver implements PublicBookingTargetResolver {

    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final HostDraftRepository hostDraftRepository;

    public DefaultPublicBookingTargetResolver(UserRepository userRepository,
                                              EventTypeRepository eventTypeRepository,
                                              HostDraftRepository hostDraftRepository) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.hostDraftRepository = hostDraftRepository;
    }

    @Override
    public ResolvedTarget resolve(String username, String eventTypeSlug) {
        if ("d".equals(username)) {
            HostDraft draft = hostDraftRepository.findByPublicSlug(eventTypeSlug)
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft not found."));
            if (draft.getState() != DraftLifecycleState.ACTIVE || draft.getExpiresAt().isBefore(Instant.now())) {
                throw new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft link is inactive.");
            }
            EventType eventType = eventTypeRepository.findByIdAndUserId(draft.getShadowEventTypeId(), draft.getShadowUserId())
                    .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Draft schedule missing."));
            return new ResolvedTarget(
                    draft.getShadowUserId(),
                    draft.getShadowEventTypeId(),
                    draft.getDisplayName(),
                    "d",
                    draft.getTimezone(),
                    draft.getEmail(),
                    null,
                    eventType.getName(),
                    eventType.getDescription(),
                    eventType.getLocation(),
                    eventType.getDuration(),
                    eventType.getHoldDuration()
            );
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        EventType eventType = eventTypeRepository.findByUserIdAndSlug(user.getId(), eventTypeSlug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        return new ResolvedTarget(
                user.getId(),
                eventType.getId(),
                user.getName(),
                user.getUsername(),
                user.getTimezone(),
                user.getEmail(),
                user.getProfileImageUrl(),
                eventType.getName(),
                eventType.getDescription(),
                eventType.getLocation(),
                eventType.getDuration(),
                eventType.getHoldDuration()
        );
    }
}
