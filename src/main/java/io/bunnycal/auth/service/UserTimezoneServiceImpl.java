package io.bunnycal.auth.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.common.time.TimezoneService;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserTimezoneServiceImpl implements TimeZoneService {

    public static final String DEFAULT_TIMEZONE = "UTC";
    private final TimezoneService timezoneService;
    private final UserRepository userRepository;

    public UserTimezoneServiceImpl(TimezoneService timezoneService, UserRepository userRepository) {
        this.timezoneService = timezoneService;
        this.userRepository = userRepository;
    }

    public String timezoneForCreate(String timezone) {
        return timezoneService.normalizeOrDefault(timezone, DEFAULT_TIMEZONE);
    }

    public void applyTimezoneUpdate(User user, String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return;
        }
        user.setTimezone(timezoneService.normalizeRequired(timezone));
        // The host picked this one, so stop inferring it for them from now on.
        user.setTimezoneAuto(false);
    }

    @Override
    @Transactional
    public User adoptDetectedTimezone(User user, String detectedTimezone) {
        if (user == null || !user.isTimezoneAuto()) {
            return user;
        }
        String normalized = normalizeQuietly(detectedTimezone);
        if (normalized == null || normalized.equals(user.getTimezone())) {
            return user;
        }
        String previous = user.getTimezone();
        user.setTimezone(normalized);
        // Stays auto: the host still has not chosen a zone, so if they move we should follow.
        User saved = userRepository.save(user);
        log.info("user_timezone_auto_adopted userId={} from={} to={}", saved.getId(), previous, normalized);
        return saved;
    }

    /**
     * The header is client-supplied and reaches GET /api/me, which the whole app depends on, so a
     * malformed value must be ignored rather than allowed to fail the request.
     */
    private String normalizeQuietly(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return null;
        }
        String trimmed = timezone.trim();
        try {
            ZoneId.of(trimmed);
            return trimmed;
        } catch (RuntimeException ex) {
            log.debug("user_timezone_header_ignored value={}", trimmed);
            return null;
        }
    }
}
