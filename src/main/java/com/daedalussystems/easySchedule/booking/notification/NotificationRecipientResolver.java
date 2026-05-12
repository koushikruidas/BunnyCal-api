package com.daedalussystems.easySchedule.booking.notification;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import com.daedalussystems.easySchedule.booking.domain.Booking;
import com.daedalussystems.easySchedule.booking.draft.repository.HostDraftRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NotificationRecipientResolver {
    private final HostDraftRepository hostDraftRepository;
    private final EmailDeliverabilityPolicy policy;

    public NotificationRecipientResolver(HostDraftRepository hostDraftRepository,
                                         EmailDeliverabilityPolicy policy) {
        this.hostDraftRepository = hostDraftRepository;
        this.policy = policy;
    }

    public Optional<String> resolveHostRecipient(User host) {
        if (host == null) {
            return Optional.empty();
        }
        String hostEmail = policy.normalize(host.getEmail());
        if (policy.isDeliverable(hostEmail)) {
            return Optional.of(hostEmail);
        }
        return hostDraftRepository.findFirstByShadowUserIdOrderByCreatedAtDesc(host.getId())
                .map(d -> policy.normalize(d.getEmail()))
                .filter(policy::isDeliverable);
    }

    public Optional<String> resolveAttendeeRecipient(Booking booking) {
        if (booking == null) {
            return Optional.empty();
        }
        String attendee = policy.normalize(booking.getGuestEmail());
        if (!policy.isDeliverable(attendee)) {
            return Optional.empty();
        }
        return Optional.of(attendee);
    }

    public List<String> deduplicate(List<String> recipients) {
        Set<String> dedup = new LinkedHashSet<>();
        for (String r : recipients) {
            String normalized = policy.normalize(r);
            if (normalized != null) {
                dedup.add(normalized);
            }
        }
        return new ArrayList<>(dedup);
    }
}

