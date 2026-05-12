package com.daedalussystems.easySchedule.booking.notification;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailDeliverabilityPolicy {
    private static final Pattern SIMPLE_EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Set<String> syntheticDomains;

    public EmailDeliverabilityPolicy(
            @Value("${booking.notifications.synthetic-domains:draft.local}") String syntheticDomainsRaw) {
        this.syntheticDomains = Arrays.stream(syntheticDomainsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public String normalize(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public boolean isSynthetic(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return false;
        }
        int at = normalized.lastIndexOf('@');
        if (at < 0 || at == normalized.length() - 1) {
            return false;
        }
        String domain = normalized.substring(at + 1);
        return syntheticDomains.contains(domain);
    }

    public boolean isDeliverable(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return false;
        }
        if (!SIMPLE_EMAIL.matcher(normalized).matches()) {
            return false;
        }
        return !isSynthetic(normalized);
    }
}

