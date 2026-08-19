package io.bunnycal.booking.notification;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.booking.domain.Booking;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NotificationRecipientResolver {
    private final EmailDeliverabilityPolicy policy;

    public NotificationRecipientResolver(EmailDeliverabilityPolicy policy) {
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
        return Optional.empty();
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

    /**
     * The invite-only extra guests the booker attached, filtered to deliverable addresses.
     *
     * <p>Deliberately separate from {@link #resolveAttendeeRecipient}: the primary guest is the
     * only one who gets a manage link, so the two must stay distinguishable all the way through
     * the send loop.
     */
    public List<String> resolveExtraGuestRecipients(List<String> guestEmails) {
        if (guestEmails == null || guestEmails.isEmpty()) {
            return List.of();
        }
        Set<String> dedup = new LinkedHashSet<>();
        for (String raw : guestEmails) {
            String normalized = policy.normalize(raw);
            if (policy.isDeliverable(normalized)) {
                dedup.add(normalized);
            }
        }
        return new ArrayList<>(dedup);
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
