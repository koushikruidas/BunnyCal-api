package io.bunnycal.booking.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.avatar.ProfileAvatarService;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import org.springframework.stereotype.Component;

@Component
public class DefaultPublicBookingTargetResolver implements PublicBookingTargetResolver {

    private final UserRepository userRepository;
    private final EventTypeRepository eventTypeRepository;
    private final ProfileAvatarService profileAvatarService;

    public DefaultPublicBookingTargetResolver(UserRepository userRepository,
                                              EventTypeRepository eventTypeRepository,
                                              ProfileAvatarService profileAvatarService) {
        this.userRepository = userRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.profileAvatarService = profileAvatarService;
    }

    @Override
    public ResolvedTarget resolve(String username, String eventTypeSlug) {
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
