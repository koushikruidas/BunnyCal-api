package io.bunnycal.booking.service;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.dto.PublicBookRequest;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.enums.UserStatus;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.session.AbstractSessionIT;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 hardening — Fix 1: INACTIVE users must not be schedulable.
 *
 * Covers:
 *   A. ACTIVE user → availability returns slots.
 *   B. INACTIVE user → availability throws HOST_NOT_SCHEDULABLE (HTTP 410).
 *   C. INACTIVE user → hold (booking creation) throws HOST_NOT_SCHEDULABLE.
 */
class HostSchedulingEligibilityIT extends AbstractSessionIT {

    @Autowired private PublicBookingService publicBookingService;
    @Autowired private EventTypeRepository eventTypeRepository;

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 15); // Monday

    @BeforeEach
    void cleanExtra() {
        jdbc.execute("TRUNCATE TABLE bookings, availability_rules, event_types CASCADE");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User createHostWithAvailabilityAndUsername(UserStatus status) {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        User host = userRepository.save(User.builder()
                .email("host-" + slug + "@test.com")
                .name("Test Host")
                .username("host-" + slug)
                .timezone("UTC")
                .status(status)
                .build());
        if (status == UserStatus.ACTIVE) {
            jdbc.update("""
                    INSERT INTO availability_rules
                        (id, user_id, day_of_week, start_time, end_time, created_at, updated_at)
                    VALUES (?, ?, 'MONDAY', '09:00', '17:00', NOW(), NOW())
                    """, UUID.randomUUID(), host.getId());
        }
        return host;
    }

    private static final String SLUG = "one-on-one-test";

    private EventType createOneOnOneType(UUID hostId) {
        return eventTypeRepository.save(EventType.builder()
                .userId(hostId)
                .name("1:1 Meeting")
                .slug(SLUG)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(365))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .build());
    }

    // ── A: ACTIVE user — availability works normally ─────────────────────────

    @Test
    void activeUser_availabilityReturnsSlots() {
        User host = createHostWithAvailabilityAndUsername(UserStatus.ACTIVE);
        createOneOnOneType(host.getId());

        SlotResponse response = publicBookingService.availability(host.getUsername(), SLUG, TEST_DATE);
        // Monday 09:00-17:00 with 30-min slots → 16 slots
        assertThat(response.slots()).isNotEmpty();
    }

    // ── B: INACTIVE user — availability is rejected ──────────────────────────

    @Test
    void inactiveUser_availability_throwsHostNotSchedulable() {
        User host = createHostWithAvailabilityAndUsername(UserStatus.INACTIVE);
        createOneOnOneType(host.getId());

        assertThatThrownBy(() ->
                publicBookingService.availability(host.getUsername(), SLUG, TEST_DATE))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.HOST_NOT_SCHEDULABLE));
    }

    // ── C: INACTIVE user — hold (booking creation) is rejected ───────────────

    @Test
    void inactiveUser_hold_throwsHostNotSchedulable() {
        User host = createHostWithAvailabilityAndUsername(UserStatus.INACTIVE);
        createOneOnOneType(host.getId());

        PublicBookRequest request = new PublicBookRequest(
                TEST_DATE.atTime(10, 0).toInstant(ZoneOffset.UTC),
                "guest@test.com",
                "Guest");

        assertThatThrownBy(() ->
                publicBookingService.hold(host.getUsername(), SLUG, request))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.HOST_NOT_SCHEDULABLE));
    }

    // ── D: DELETED user — availability is also rejected ──────────────────────

    @Test
    void deletedUser_availability_throwsHostNotSchedulable() {
        User host = createHostWithAvailabilityAndUsername(UserStatus.DELETED);
        createOneOnOneType(host.getId());

        assertThatThrownBy(() ->
                publicBookingService.availability(host.getUsername(), SLUG, TEST_DATE))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.HOST_NOT_SCHEDULABLE));
    }

    // ── E: status change from ACTIVE to INACTIVE blocks future bookings ──────

    @Test
    void userDeactivated_subsequentAvailabilityFails() {
        User host = createHostWithAvailabilityAndUsername(UserStatus.ACTIVE);
        createOneOnOneType(host.getId());

        // While ACTIVE: works fine.
        SlotResponse before = publicBookingService.availability(host.getUsername(), SLUG, TEST_DATE);
        assertThat(before.slots()).isNotEmpty();

        // Deactivate.
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", host.getId());

        // After deactivation: rejected.
        assertThatThrownBy(() ->
                publicBookingService.availability(host.getUsername(), SLUG, TEST_DATE))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.HOST_NOT_SCHEDULABLE));
    }
}
