package io.bunnycal.booking.notification;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliverabilityPolicy {
    private static final Pattern SIMPLE_EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public String normalize(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public boolean isDeliverable(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return false;
        }
        return SIMPLE_EMAIL.matcher(normalized).matches();
    }
}
