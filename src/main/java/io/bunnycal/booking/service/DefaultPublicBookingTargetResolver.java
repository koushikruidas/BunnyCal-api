package io.bunnycal.booking.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.draft.domain.DraftLifecycleState;
import io.bunnycal.booking.draft.domain.HostDraft;
import io.bunnycal.booking.draft.repository.HostDraftRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class DefaultPublicBookingTargetResolver implements PublicBookingTargetResolver {

    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final HostDraftRepository hostDraftRepository;
    private final ProfileAvatarService profileAvatarService;

    public DefaultPublicBookingTargetResolver(UserRepository userRepository,
                                              EventTypeRepository eventTypeRepository,
                                              HostDraftRepository hostDraftRepository,
                                              ProfileAvatarService profileAvatarService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.hostDraftRepository = hostDraftRepository;
        this.profileAvatarService = profileAvatarService;
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
                    eventType.getHoldDuration(),
                    eventType.getKind() != null ? eventType.getKind() : EventKind.ONE_ON_ONE,
                    eventType.getCapacity()
            );
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "User not found."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new CustomException(ErrorCode.HOST_NOT_SCHEDULABLE, "This host is not available for scheduling.");
        }
        EventType eventType = eventTypeRepository.findByUserIdAndSlugAndDeletedAtIsNull(user.getId(), eventTypeSlug)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
        return new ResolvedTarget(
                user.getId(),
                eventType.getId(),
                user.getName(),
                user.getUsername(),
                user.getTimezone(),
                user.getEmail(),
                profileAvatarService.resolveProfileImageUrl(user),
                eventType.getName(),
                eventType.getDescription(),
                eventType.getLocation(),
                eventType.getDuration(),
                eventType.getHoldDuration(),
                eventType.getKind() != null ? eventType.getKind() : EventKind.ONE_ON_ONE,
                eventType.getCapacity()
        );
    }
}
