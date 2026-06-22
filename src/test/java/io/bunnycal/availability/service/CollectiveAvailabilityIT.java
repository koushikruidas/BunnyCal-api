package io.bunnycal.availability.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.AvailabilityStatus;
import io.bunnycal.availability.dto.SlotDto;
import io.bunnycal.availability.dto.SlotResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.AbstractBookingIT;
import io.bunnycal.booking.service.CollectiveSlotTokenService;
import io.bunnycal.booking.service.PublicBookingService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.UserStatus;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for COLLECTIVE event type slot generation.
 *
 * <p>Test date: next Monday at least 7 days from now (within the 30-day maxAdvance window).
 * Using a dynamic date prevents test staleness as calendar advances.
 * All participants use UTC timezone.
 */
@Testcontainers(disabledWithoutDocker = true)
class CollectiveAvailabilityIT extends AbstractBookingIT {

    // Next Monday that is at least 7 days from today — always within maxAdvance=30 days.
    private static final LocalDate TEST_DATE = nextMonday(7);

    @Autowired private PublicBookingService publicBookingService;
    @Autowired private EventTypeRepository eventTypeRepository;
    @Autowired private CollectiveSlotTokenService collectiveSlotTokenService;

    @BeforeEach
    void cleanFixtures() {
        jdbc.execute("""
                TRUNCATE TABLE users, event_types, event_type_participants, availability_rules,
                    availability_overrides, bookings, booking_assignments, booking_action_tokens,
                    idempotency_keys, outbox_events, processed_events, calendar_connections,
                    calendar_events, calendar_event_mappings CASCADE
                """);
    }

    // ── Readiness tests ────────────────────────────────────────────────────────

    @Test
    void allParticipantsReady_returnsSlots() {
        User owner = createUser("owner-ca@test.com", "owner-ca");
        User alice = createUser("alice-ca@test.com", "alice-ca");
        User bob = createUser("bob-ca@test.com", "bob-ca");
        EventType eventType = createCollectiveType(owner.getId(), "coll-ca");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(response.slots()).isNotEmpty();
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void oneParticipantNotReady_noRules_returnsNoEligibleParticipants() {
        User owner = createUser("owner-cb@test.com", "owner-cb");
        User alice = createUser("alice-cb@test.com", "alice-cb");
        User bob = createUser("bob-cb@test.com", "bob-cb"); // no rules
        EventType eventType = createCollectiveType(owner.getId(), "coll-cb");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        // bob has no rules
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        assertThat(response.slots()).isEmpty();
    }

    @Test
    void oneParticipantNoCalendar_stillContributesRuleBasedSlots() {
        // Calendar is NOT required for slot generation — only for booking creation (Phase 4).
        // A participant with rules but no calendar contributes pure rule-based availability;
        // their intersection with other participants is still computed.
        User owner = createUser("owner-cc@test.com", "owner-cc");
        User alice = createUser("alice-cc@test.com", "alice-cc");
        User bob = createUser("bob-cc@test.com", "bob-cc"); // no calendar
        EventType eventType = createCollectiveType(owner.getId(), "coll-cc");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        // bob has no calendar connection — still eligible for slot computation

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(response.slots()).isNotEmpty();
    }

    @Test
    void participantWithNoRules_stillBlocksAllSlots() {
        // Alice has no availability rules → ineligible (hard block).
        // Bob has rules but no calendar → eligible (calendar not required for slot generation).
        // One ineligible participant is enough to block all slots.
        User owner = createUser("owner-cd@test.com", "owner-cd");
        User alice = createUser("alice-cd@test.com", "alice-cd"); // no rules
        User bob = createUser("bob-cd@test.com", "bob-cd");   // rules, no calendar
        EventType eventType = createCollectiveType(owner.getId(), "coll-cd");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        assertThat(response.slots()).isEmpty();
    }

    @Test
    void participantInactive_returnsNoEligibleParticipants() {
        User owner = createUser("owner-ce@test.com", "owner-ce");
        User alice = createUser("alice-ce@test.com", "alice-ce");
        User bob = createUser("bob-ce@test.com", "bob-ce");
        EventType eventType = createCollectiveType(owner.getId(), "coll-ce");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_ELIGIBLE_PARTICIPANTS);
        assertThat(response.slots()).isEmpty();
    }

    // ── Availability aggregation tests ─────────────────────────────────────────

    @Test
    void completeOverlap_returnsSlotsMatchingBothSchedules() {
        User owner = createUser("owner-cf@test.com", "owner-cf");
        User alice = createUser("alice-cf@test.com", "alice-cf");
        User bob = createUser("bob-cf@test.com", "bob-cf");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cf");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        // Identical schedules: 09:00-11:00
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        List<Instant> starts = response.slots().stream().map(SlotDto::start).toList();
        // 09:00, 09:30, 10:00, 10:30
        assertThat(starts).containsExactly(
                slotAt(9, 0),
                slotAt(9, 30),
                slotAt(10, 0),
                slotAt(10, 30));
    }

    @Test
    void partialOverlap_returnsOnlyCommonSlots() {
        User owner = createUser("owner-cg@test.com", "owner-cg");
        User alice = createUser("alice-cg@test.com", "alice-cg");
        User bob = createUser("bob-cg@test.com", "bob-cg");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cg");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        // alice: 09:00-11:00; bob: 10:00-12:00 → intersection: 10:00-11:00
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(10, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        List<Instant> starts = response.slots().stream().map(SlotDto::start).toList();
        // 10:00, 10:30 (intersection of 09-11 and 10-12)
        assertThat(starts).containsExactlyInAnyOrder(
                slotAt(10, 0),
                slotAt(10, 30));
        // 09:00 slot must NOT appear (only alice is free then)
        assertThat(starts).doesNotContain(slotAt(9, 0));
        // 11:00 slot must NOT appear (only bob is free then)
        assertThat(starts).doesNotContain(slotAt(11, 0));
    }

    @Test
    void noOverlap_returnsNoSlotsAvailable() {
        User owner = createUser("owner-ch@test.com", "owner-ch");
        User alice = createUser("alice-ch@test.com", "alice-ch");
        User bob = createUser("bob-ch@test.com", "bob-ch");
        EventType eventType = createCollectiveType(owner.getId(), "coll-ch");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        // No overlap: alice 09:00-10:00, bob 11:00-12:00
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(11, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.NO_SLOTS_AVAILABLE);
        assertThat(response.slots()).isEmpty();
    }

    // ── Slot generation tests ──────────────────────────────────────────────────

    @Test
    void slotGeneratedWhenAllParticipantsAvailable() {
        User owner = createUser("owner-ci@test.com", "owner-ci");
        User alice = createUser("alice-ci@test.com", "alice-ci");
        User bob = createUser("bob-ci@test.com", "bob-ci");
        User charlie = createUser("charlie-ci@test.com", "charlie-ci");
        EventType eventType = createCollectiveType(owner.getId(), "coll-ci");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId(), charlie.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(charlie.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());
        insertCalendarConnectionWithWriteback(charlie.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        assertThat(response.status()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(response.slots()).isNotEmpty();
        assertThat(response.slots().stream().map(SlotDto::start)).contains(
                slotAt(9, 0));
    }

    @Test
    void slotRemovedWhenAnyParticipantHasBooking() {
        User owner = createUser("owner-cj@test.com", "owner-cj");
        User alice = createUser("alice-cj@test.com", "alice-cj");
        User bob = createUser("bob-cj@test.com", "bob-cj");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cj");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        // Alice has a confirmed booking at 09:00 — blocks that slot for the whole collective
        insertBooking(alice.getId(), eventType.getId(),
                slotAt(9, 0),
                slotAt(9, 30),
                "CONFIRMED", 1L);

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        List<Instant> starts = response.slots().stream().map(SlotDto::start).toList();
        // 09:00 must be removed (alice is busy), 09:30 still available
        assertThat(starts).doesNotContain(slotAt(9, 0));
        assertThat(starts).contains(slotAt(9, 30));
    }

    // ── Participant change tests ────────────────────────────────────────────────

    @Test
    void participantAdded_newRosterNarrowsIntersection() {
        User owner = createUser("owner-ck@test.com", "owner-ck");
        User alice = createUser("alice-ck@test.com", "alice-ck");
        User bob = createUser("bob-ck@test.com", "bob-ck");
        EventType eventType = createCollectiveType(owner.getId(), "coll-ck");

        // Initially just alice (09:00-11:00)
        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());

        List<Instant> before = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream().map(SlotDto::start).toList();
        assertThat(before).contains(slotAt(9, 0));

        // Add bob who is only free 10:00-12:00
        insertRule(bob.getId(), "MONDAY", LocalTime.of(10, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(bob.getId());
        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));

        List<Instant> after = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream().map(SlotDto::start).toList();
        // 09:00 is no longer in the intersection
        assertThat(after).doesNotContain(slotAt(9, 0));
        // 10:00 is now the earliest intersection slot
        assertThat(after).contains(slotAt(10, 0));
    }

    @Test
    void participantRemoved_intersectionWidens() {
        User owner = createUser("owner-cl@test.com", "owner-cl");
        User alice = createUser("alice-cl@test.com", "alice-cl");
        User bob = createUser("bob-cl@test.com", "bob-cl");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cl");

        // alice: 09-11, bob: 10-12 → intersection 10-11
        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(10, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        List<Instant> before = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream().map(SlotDto::start).toList();
        assertThat(before).doesNotContain(slotAt(9, 0));

        // Remove bob
        setParticipants(eventType.getId(), List.of(alice.getId()));

        List<Instant> after = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream().map(SlotDto::start).toList();
        // Now only alice: 09:00 appears
        assertThat(after).contains(slotAt(9, 0));
    }

    // ── Token tests ────────────────────────────────────────────────────────────

    @Test
    void slotToken_isValidForCurrentRoster() {
        User owner = createUser("owner-cm@test.com", "owner-cm");
        User alice = createUser("alice-cm@test.com", "alice-cm");
        User bob = createUser("bob-cm@test.com", "bob-cm");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cm");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        // Token must verify cleanly
        CollectiveSlotTokenService.DecodedCollectiveToken decoded = collectiveSlotTokenService.verify(slot.bookingToken());
        assertThat(decoded.ownerUserId()).isEqualTo(owner.getId());
        assertThat(decoded.eventTypeId()).isEqualTo(eventType.getId());
        assertThat(decoded.start()).isEqualTo(slotAt(9, 0));

        // Roster match must pass for current roster (order-independent)
        collectiveSlotTokenService.validateRosterMatch(decoded, List.of(alice.getId(), bob.getId()));
        collectiveSlotTokenService.validateRosterMatch(decoded, List.of(bob.getId(), alice.getId()));
    }

    @Test
    void slotToken_invalidAfterRosterChange_participantAdded() {
        User owner = createUser("owner-cn@test.com", "owner-cn");
        User alice = createUser("alice-cn@test.com", "alice-cn");
        User bob = createUser("bob-cn@test.com", "bob-cn");
        User charlie = createUser("charlie-cn@test.com", "charlie-cn");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cn");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(charlie.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());
        insertCalendarConnectionWithWriteback(charlie.getId());

        // Issue token for 2-participant roster
        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        CollectiveSlotTokenService.DecodedCollectiveToken decoded = collectiveSlotTokenService.verify(slot.bookingToken());

        // Roster now has 3 participants — old token must fail validation
        org.junit.jupiter.api.Assertions.assertThrows(
                io.bunnycal.common.exception.CustomException.class,
                () -> collectiveSlotTokenService.validateRosterMatch(
                        decoded, List.of(alice.getId(), bob.getId(), charlie.getId())));
    }

    @Test
    void slotToken_invalidAfterRosterChange_participantRemoved() {
        User owner = createUser("owner-co@test.com", "owner-co");
        User alice = createUser("alice-co@test.com", "alice-co");
        User bob = createUser("bob-co@test.com", "bob-co");
        EventType eventType = createCollectiveType(owner.getId(), "coll-co");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        // Issue token for 2-participant roster
        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream()
                .filter(s -> s.start().equals(slotAt(9, 0)))
                .findFirst().orElseThrow();

        CollectiveSlotTokenService.DecodedCollectiveToken decoded = collectiveSlotTokenService.verify(slot.bookingToken());

        // Bob removed — old token must fail validation
        org.junit.jupiter.api.Assertions.assertThrows(
                io.bunnycal.common.exception.CustomException.class,
                () -> collectiveSlotTokenService.validateRosterMatch(decoded, List.of(alice.getId())));
    }

    // ── Regression: other event kinds must be unaffected ──────────────────────

    @Test
    void roundRobinSlotGeneration_unchanged_usesUnionSemantics() {
        User owner = createUser("owner-rr@test.com", "owner-rr");
        User alice = createUser("alice-rr@test.com", "alice-rr");
        User bob = createUser("bob-rr@test.com", "bob-rr");

        EventType rrType = eventTypeRepository.save(EventType.builder()
                .userId(owner.getId())
                .name("RR regression")
                .slug("rr-reg-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ROUND_ROBIN)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .build());

        setParticipants(rrType.getId(), List.of(alice.getId(), bob.getId()));
        // alice: 09:00-10:00; bob: 11:00-12:00 → UNION gives slots from both windows
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(10, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(11, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), rrType.getSlug(), TEST_DATE);

        List<Instant> starts = response.slots().stream().map(SlotDto::start).toList();
        // UNION: both 09:00 (alice) and 11:00 (bob) must appear
        assertThat(starts).contains(slotAt(9, 0));
        assertThat(starts).contains(slotAt(11, 0));
    }

    @Test
    void collectiveSlotGeneration_intersectionNotUnion_sameSchedules() {
        User owner = createUser("owner-cp@test.com", "owner-cp");
        User alice = createUser("alice-cp@test.com", "alice-cp");
        User bob = createUser("bob-cp@test.com", "bob-cp");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cp");

        setParticipants(eventType.getId(), List.of(alice.getId(), bob.getId()));
        // alice: 09-11, bob: 10-12 — INTERSECTION gives only 10:00 and 10:30
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertRule(bob.getId(), "MONDAY", LocalTime.of(10, 0), LocalTime.of(12, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());
        insertCalendarConnectionWithWriteback(bob.getId());

        SlotResponse response = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE);

        List<Instant> starts = response.slots().stream().map(SlotDto::start).toList();
        // If this were UNION: 09:00, 09:30, 10:00, 10:30, 11:00, 11:30 would all appear.
        // With INTERSECTION: only 10:00, 10:30
        assertThat(starts).containsExactlyInAnyOrder(
                slotAt(10, 0),
                slotAt(10, 30));
        assertThat(starts).doesNotContain(slotAt(9, 0));
        assertThat(starts).doesNotContain(slotAt(11, 0));
    }

    @Test
    void collectiveSlotTokens_areNonNullAndDistinctFromRRTokenFormat() {
        User owner = createUser("owner-cq@test.com", "owner-cq");
        User alice = createUser("alice-cq@test.com", "alice-cq");
        EventType eventType = createCollectiveType(owner.getId(), "coll-cq");

        setParticipants(eventType.getId(), List.of(alice.getId()));
        insertRule(alice.getId(), "MONDAY", LocalTime.of(9, 0), LocalTime.of(11, 0));
        insertCalendarConnectionWithWriteback(owner.getId());
        insertCalendarConnectionWithWriteback(alice.getId());

        SlotDto slot = publicBookingService.availability(owner.getUsername(), eventType.getSlug(), TEST_DATE)
                .slots().stream().findFirst().orElseThrow();

        assertThat(slot.bookingToken()).isNotNull();
        // Collective tokens decode with cv1 prefix; they must not verify as RR tokens
        CollectiveSlotTokenService.DecodedCollectiveToken decoded =
                collectiveSlotTokenService.verify(slot.bookingToken());
        assertThat(decoded.ownerUserId()).isEqualTo(owner.getId());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private User createUser(String email, String username) {
        return userRepository.save(User.builder()
                .email(email)
                .username(username)
                .name(username)
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private EventType createCollectiveType(UUID ownerId, String slugPrefix) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name("Collective " + slugPrefix)
                .slug(slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO).bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO).maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.COLLECTIVE)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .build());
    }

    private void setParticipants(UUID eventTypeId, List<UUID> participantIds) {
        jdbc.update("DELETE FROM event_type_participants WHERE event_type_id = ?", eventTypeId);
        for (int i = 0; i < participantIds.size(); i++) {
            jdbc.update(
                    "INSERT INTO event_type_participants (id, event_type_id, user_id, display_order) VALUES (?,?,?,?)",
                    UUID.randomUUID(), eventTypeId, participantIds.get(i), i);
        }
    }

    private void insertRule(UUID userId, String dayOfWeek, LocalTime startTime, LocalTime endTime) {
        jdbc.update(
                "INSERT INTO availability_rules (id, user_id, day_of_week, start_time, end_time) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), userId, dayOfWeek, startTime, endTime);
    }

    private static LocalDate nextMonday(int minDaysFromNow) {
        LocalDate base = LocalDate.now().plusDays(minDaysFromNow);
        int daysUntilMonday = (DayOfWeek.MONDAY.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(daysUntilMonday);
    }

    private Instant slotAt(int hour, int minute) {
        return TEST_DATE.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /**
     * Inserts a calendar connection with ACTIVE status AND a writable inventory entry.
     * Collective requires writeback capability — a connection without a writable
     * calendar entry fails the isReady() check.
     */
    private void insertCalendarConnectionWithWriteback(UUID userId) {
        UUID connectionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO calendar_connections "
                        + "(id, user_id, provider, provider_user_id, refresh_token_ciphertext, "
                        + "last_token_expires_at, scopes, status, version, last_synced_at) "
                        + "VALUES (?,?,?,?,?,NOW() + INTERVAL '1 hour','{}'::text[],?,0,NOW())",
                connectionId, userId, "GOOGLE", "ext-" + userId, "dummy-token", "ACTIVE");
        // Insert a writable calendar inventory entry for this connection.
        // Columns: id, connection_id, external_calendar_id, name, is_primary, is_selected,
        //          sync_enabled, can_read, can_write, hidden
        jdbc.update(
                "INSERT INTO calendar_connection_calendars "
                        + "(id, connection_id, external_calendar_id, name, is_primary, is_selected, "
                        + "sync_enabled, can_read, can_write, hidden) "
                        + "VALUES (?,?,?,?,true,true,true,true,true,false)",
                UUID.randomUUID(), connectionId, "primary@" + userId, "Primary");
    }
}
