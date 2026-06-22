package io.bunnycal.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.dto.CreateEventTypeRequest;
import io.bunnycal.availability.dto.EventTypeSummaryResponse;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.service.EventTypeService;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
class EventTypeSoftDeleteIT {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired EventTypeRepository eventTypeRepository;
    @Autowired EventTypeService eventTypeService;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, event_types, event_type_participants, bookings CASCADE");
    }

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email).name("U " + email).username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("UTC").build());
    }

    /** Persists an event type directly with a known slug (bypasses calendar orchestration). */
    private EventType persistEventType(UUID ownerId, String slug) {
        return eventTypeRepository.save(EventType.builder()
                .userId(ownerId)
                .name("Demo Call")
                .slug(slug)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .conferencingProvider(io.bunnycal.common.enums.ConferencingProviderType.NONE)
                .build());
    }

    @Test
    void delete_removesFromListAndGetReturnsNotFound() {
        User owner = createUser("owner@test.com");
        EventType et = persistEventType(owner.getId(), "demo-call");

        eventTypeService.delete(owner.getId(), et.getId());

        // Disappears from the user's listing.
        List<EventTypeSummaryResponse> listed = eventTypeService.list(owner.getId());
        assertThat(listed).isEmpty();

        // get() now behaves as not-found.
        assertThatThrownBy(() -> eventTypeService.get(owner.getId(), et.getId()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        // The row is still present with deleted_at set (no physical delete).
        EventType reloaded = eventTypeRepository.findById(et.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_publicSlugLookupReturnsEmpty() {
        User owner = createUser("owner@test.com");
        EventType et = persistEventType(owner.getId(), "demo-call");

        eventTypeService.delete(owner.getId(), et.getId());

        assertThat(eventTypeRepository.findByUserIdAndSlugAndDeletedAtIsNull(owner.getId(), "demo-call"))
                .isEmpty();
    }

    @Test
    void delete_allowsSlugReuse() {
        User owner = createUser("owner@test.com");
        EventType first = persistEventType(owner.getId(), "demo-call");

        eventTypeService.delete(owner.getId(), first.getId());

        // The active-slug check must ignore the deleted row.
        assertThat(eventTypeRepository.existsByUserIdAndSlugAndDeletedAtIsNull(owner.getId(), "demo-call"))
                .isFalse();

        // Creating again with the same slug succeeds and yields a distinct row.
        EventType second = persistEventType(owner.getId(), "demo-call");
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(eventTypeService.list(owner.getId())).hasSize(1);
    }

    @Test
    void delete_forbiddenForNonOwner() {
        User owner = createUser("owner@test.com");
        User intruder = createUser("intruder@test.com");
        EventType et = persistEventType(owner.getId(), "demo-call");

        assertThatThrownBy(() -> eventTypeService.delete(intruder.getId(), et.getId()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // Still active for the real owner.
        assertThat(eventTypeService.list(owner.getId())).hasSize(1);
    }

    @Test
    void delete_doesNotTouchExistingBookings() {
        User owner = createUser("owner@test.com");
        EventType et = persistEventType(owner.getId(), "demo-call");

        // Insert a booking referencing this event type (host_id is the partition key).
        // Columns not listed (version, calendar_sequence, terminal_intent_epoch, timestamps)
        // all carry NOT NULL DEFAULTs.
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, host_id, event_type_id, start_time, end_time, status)
                VALUES (?, ?, ?, now() + interval '1 day', now() + interval '1 day' + interval '30 minutes',
                        'CONFIRMED')
                """, bookingId, owner.getId(), et.getId());

        eventTypeService.delete(owner.getId(), et.getId());

        Long bookingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE id = ?", Long.class, bookingId);
        assertThat(bookingCount).isEqualTo(1L);
    }
}
