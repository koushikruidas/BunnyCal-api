package io.bunnycal.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.calendar.service.CalendarConnectionManagementService;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.conferencing.service.EventConferencingResolver;
import java.time.Duration;
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

/**
 * The bug this whole model exists to kill:
 *
 * <blockquote>My bookings go to my Google calendar and my events use Google Meet. I switch my
 * bookings to Outlook. Weeks later a guest books me and gets a meeting with no join link.</blockquote>
 *
 * A Meet link can only be created on a Google calendar and a Teams link only on a Microsoft
 * work/school one. So an event type that <em>stores</em> {@code GOOGLE_MEET} is a landmine: it stays
 * pointing at a provider its owner no longer writes to, and nothing says so until a guest is holding
 * the broken booking.
 *
 * <p>The fix is that an event type stores a <b>pointer</b> ({@code DEFAULT}), not a value. These
 * tests pin that down from both directions: the pointer follows a calendar change, and the two
 * provider-coupled values cannot be pinned in the first place.
 */
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
class GlobalCalendarSettingsIT {

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

    // A consumer MSA's /me/id is a 16-hex-char puid; an Entra (work/school) oid is a UUID.
    private static final String ENTRA_OID = "12345678-1234-1234-1234-123456789012";
    private static final String CONSUMER_PUID = "ed9adb1ac97c0819";

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired EventTypeRepository eventTypeRepository;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired CalendarConnectionCalendarRepository inventoryRepository;
    @Autowired CalendarConnectionManagementService managementService;
    @Autowired EventConferencingResolver conferencingResolver;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, event_types, calendar_connections, "
                + "calendar_connection_calendars CASCADE");
    }

    // ── The pointer follows the calendar ────────────────────────────────────────────────────────

    /**
     * The whole point. An event bound to the default resolves to Meet while the user writes to
     * Google; after they move their bookings to a Microsoft work account it resolves to Teams. The
     * event type was never touched.
     */
    @Test
    void defaultBoundEvent_followsTheWritebackCalendarWhenItChanges() {
        User owner = createUser("owner@test.com");
        CalendarConnection google = connection(owner, CalendarProviderType.GOOGLE, "g-sub", true);
        CalendarConnection microsoft = connection(owner, CalendarProviderType.MICROSOFT, ENTRA_OID, false);
        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.GOOGLE_MEET);

        EventType event = eventBoundToDefault(owner);

        assertThat(conferencingResolver.resolve(owner.getId(), event))
                .isEqualTo(ConferencingProviderType.GOOGLE_MEET);

        // The user moves house. Teams first (Meet cannot survive the move), then the calendar.
        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.ZOOM);
        managementService.setDefaultWriteback(owner.getId(), microsoft.getId());
        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.MICROSOFT_TEAMS);

        assertThat(conferencingResolver.resolve(owner.getId(), event))
                .isEqualTo(ConferencingProviderType.MICROSOFT_TEAMS);
        assertThat(eventTypeRepository.findById(event.getId()).orElseThrow().getConferencingProvider())
                .as("the event type itself never changed — it holds a pointer, not a provider")
                .isEqualTo(ConferencingProviderType.DEFAULT);
    }

    /** An override is a value, not a pointer: it is immune to the calendar moving under it. */
    @Test
    void overriddenEvent_isUnaffectedByTheWritebackCalendarChanging() {
        User owner = createUser("owner@test.com");
        connection(owner, CalendarProviderType.GOOGLE, "g-sub", true);
        CalendarConnection microsoft = connection(owner, CalendarProviderType.MICROSOFT, ENTRA_OID, false);

        EventType zoomEvent = event(owner, ConferencingProviderType.ZOOM);

        managementService.setDefaultWriteback(owner.getId(), microsoft.getId());

        assertThat(conferencingResolver.resolve(owner.getId(), zoomEvent))
                .isEqualTo(ConferencingProviderType.ZOOM);
    }

    // ── The settings page refuses to create the trap ────────────────────────────────────────────

    /** Moving write-back across providers must not deadlock with the native conferencing selector. */
    @Test
    void movingBookingsAcrossProviders_standsDownIncompatibleLinkAndAllowsNewNativeDefault() {
        User owner = createUser("owner@test.com");
        connection(owner, CalendarProviderType.GOOGLE, "g-sub", true);
        CalendarConnection microsoft = connection(owner, CalendarProviderType.MICROSOFT, ENTRA_OID, false);
        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.GOOGLE_MEET);

        managementService.setDefaultWriteback(owner.getId(), microsoft.getId());

        assertThat(connectionRepository.findByUserIdAndDefaultWritebackTrue(owner.getId()).orElseThrow().getProvider())
                .isEqualTo(CalendarProviderType.MICROSOFT);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getDefaultConferencingProvider())
                .as("Meet is cleared instead of blocking the write-back move")
                .isEqualTo(ConferencingProviderType.NONE);

        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.MICROSOFT_TEAMS);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getDefaultConferencingProvider())
                .isEqualTo(ConferencingProviderType.MICROSOFT_TEAMS);
    }

    /** And the mirror image: choosing a link your calendar cannot mint is refused too. */
    @Test
    void choosingALinkTheWritebackCalendarCannotMint_isRefused() {
        User owner = createUser("owner@test.com");
        connection(owner, CalendarProviderType.GOOGLE, "g-sub", true);

        assertThatThrownBy(() ->
                managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.MICROSOFT_TEAMS))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("Microsoft Teams");
    }

    /**
     * A personal Outlook.com account has no Teams-for-Business licence: Graph accepts
     * {@code isOnlineMeeting=true} and returns an event with no join URL. So it is Zoom or nothing.
     */
    @Test
    void consumerMicrosoftAccount_cannotOfferTeams() {
        User owner = createUser("owner@test.com");
        connection(owner, CalendarProviderType.MICROSOFT, CONSUMER_PUID, true);

        assertThatThrownBy(() ->
                managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.MICROSOFT_TEAMS))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("work or school");

        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.ZOOM);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getDefaultConferencingProvider())
                .isEqualTo(ConferencingProviderType.ZOOM);
    }

    /**
     * Disconnecting the calendar that served the default link leaves nothing that can mint it. The
     * default stands down to NONE rather than staying pointed at something unbuildable — bookings
     * then carry no link, which is visible, instead of failing at confirmation for a guest.
     */
    @Test
    void disconnectingTheServingCalendar_standsTheDefaultLinkDown() {
        User owner = createUser("owner@test.com");
        CalendarConnection google = connection(owner, CalendarProviderType.GOOGLE, "g-sub", true);
        connection(owner, CalendarProviderType.MICROSOFT, ENTRA_OID, false);
        managementService.setDefaultConferencing(owner.getId(), ConferencingProviderType.GOOGLE_MEET);

        managementService.disconnect(owner.getId(), google.getId());

        assertThat(userRepository.findById(owner.getId()).orElseThrow().getDefaultConferencingProvider())
                .isEqualTo(ConferencingProviderType.NONE);
    }

    /** A default that resolves to nothing is NONE, not a broken Meet link. */
    @Test
    void userWithNoWritebackCalendar_resolvesToNoLink() {
        User owner = createUser("owner@test.com");
        EventType event = eventBoundToDefault(owner);

        assertThat(conferencingResolver.resolve(owner.getId(), event))
                .isEqualTo(ConferencingProviderType.NONE);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("Test User")
                .username("u" + UUID.randomUUID().toString().substring(0, 8))
                .timezone("UTC")
                .build());
    }

    private CalendarConnection connection(User owner,
                                          CalendarProviderType provider,
                                          String providerUserId,
                                          boolean defaultWriteback) {
        CalendarConnection c = new CalendarConnection();
        c.setUserId(owner.getId());
        c.setProvider(provider);
        c.setProviderUserId(providerUserId);
        c.setRefreshTokenCiphertext("cipher");
        c.setStatus(CalendarConnectionStatus.ACTIVE);
        c.setScopes(java.util.List.of("scope"));
        c.setLastTokenExpiresAt(java.time.Instant.now().plusSeconds(3600));
        c.setDefaultWriteback(defaultWriteback);
        CalendarConnection saved = connectionRepository.save(c);

        CalendarConnectionCalendar calendar = new CalendarConnectionCalendar();
        calendar.setConnectionId(saved.getId());
        calendar.setExternalCalendarId(provider == CalendarProviderType.GOOGLE ? "primary" : "AAMkAG");
        calendar.setName("Calendar");
        calendar.setPrimary(true);
        calendar.setSelected(true);
        calendar.setCanRead(true);
        calendar.setCanWrite(true);
        calendar.setChecksAvailability(true);
        if (provider == CalendarProviderType.MICROSOFT) {
            calendar.setSupportsNativeTeams(ENTRA_OID.equals(providerUserId));
        }
        inventoryRepository.save(calendar);

        return saved;
    }

    private EventType eventBoundToDefault(User owner) {
        return event(owner, ConferencingProviderType.DEFAULT);
    }

    private EventType event(User owner, ConferencingProviderType conferencing) {
        return eventTypeRepository.save(EventType.builder()
                .userId(owner.getId())
                .name("Intro call")
                .slug("intro-" + UUID.randomUUID().toString().substring(0, 8))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .conferencingProvider(conferencing)
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(10))
                .published(true)
                .build());
    }
}
