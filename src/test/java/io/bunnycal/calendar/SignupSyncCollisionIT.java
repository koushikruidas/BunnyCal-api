package io.bunnycal.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.domain.CalendarConnectionCalendar;
import io.bunnycal.calendar.repository.CalendarConnectionCalendarRepository;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.service.DefaultConferencingReconciler;
import io.bunnycal.calendar.service.CalendarConnectionNotVisibleException;
import io.bunnycal.calendar.service.CalendarEventIngestionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduces the signup/scheduler collision seen in production logs on 2026-08-19.
 *
 * <p>Sign-in creates the calendar connection and runs its initial sync inside ONE transaction
 * ({@code CalendarOAuthService.connectAuthorizedUser}). While that transaction is open the row is
 * invisible to every other transaction — but the sync scheduler's due-query already includes
 * SYNCING connections, so a sweep landing inside that window picks up a connection it cannot then
 * read. {@code CalendarEventIngestionService} does {@code findById(...).orElseThrow()} and the
 * scheduler misreads the resulting exception as "this calendar is broken", marking it FAILED.
 *
 * <p>That FAILED status is what breaks onboarding: the Google Meet default cannot be set on a
 * non-ACTIVE connection, so setup aborts and the host is told to reconnect — even though the
 * connection recovers on the next sweep about a minute later.
 *
 * <p>The window is only as long as a signup takes (~4s observed) against a 30s sweep interval,
 * which is why this fails roughly one signup in seven and looks random from the outside.
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
class SignupSyncCollisionIT {

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
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired CalendarEventIngestionService ingestionService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CalendarConnectionCalendarRepository calendarRepository;
    @Autowired DefaultConferencingReconciler reconciler;

    private User user;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, calendar_connections CASCADE");
        user = userRepository.save(User.builder()
                .email("host@example.com").name("Host").timezone("UTC").build());
    }

    private CalendarConnection newConnection() {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(user.getId());
        connection.setProvider(CalendarProviderType.GOOGLE);
        connection.setProviderUserId("google-user-" + UUID.randomUUID());
        connection.setRefreshTokenCiphertext("cipher-refresh");
        connection.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        connection.setStatus(CalendarConnectionStatus.SYNCING);
        connection.setScopes(List.of("https://www.googleapis.com/auth/calendar.events"));
        return connection;
    }

    /**
     * The collision itself: while signup's transaction is still open, a concurrent reader cannot
     * see the connection, and ingestion turns that into a hard exception.
     */
    @Test
    void anUncommittedConnectionIsReportedAsNotVisibleRatherThanAFault() throws Exception {
        TransactionTemplate signupTx = new TransactionTemplate(txManager);
        CountDownLatch connectionWritten = new CountDownLatch(1);
        CountDownLatch sweepFinished = new CountDownLatch(1);
        AtomicReference<UUID> connectionId = new AtomicReference<>();
        AtomicReference<Throwable> sweepFailure = new AtomicReference<>();

        // The scheduler sweep, on its own thread and therefore its own transaction — exactly the
        // "scheduling-1" thread in the logs.
        Thread sweep = new Thread(() -> {
            try {
                connectionWritten.await(10, TimeUnit.SECONDS);
                ingestionService.upsertEvents(connectionId.get(), List.of());
            } catch (Throwable t) {
                sweepFailure.set(t);
            } finally {
                sweepFinished.countDown();
            }
        });
        sweep.start();

        // Signup: write the connection, then hold the transaction open while the sweep runs —
        // which is precisely what the initial full sync does inside connectAuthorizedUser.
        signupTx.executeWithoutResult(status -> {
            CalendarConnection saved = connectionRepository.saveAndFlush(newConnection());
            connectionId.set(saved.getId());
            connectionWritten.countDown();
            try {
                sweepFinished.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        sweep.join(10_000);

        // The sweep still cannot read the row — that part is unavoidable, it genuinely is not
        // committed yet. What matters is WHICH signal comes back: a distinct "not visible"
        // exception the scheduler knows to skip, rather than the generic IllegalArgumentException
        // it used to interpret as a broken calendar and stamp FAILED.
        Throwable failure = sweepFailure.get();
        assertNotNull(failure, "the sweep should still report that it could not read the row");
        assertInstanceOf(CalendarConnectionNotVisibleException.class, failure,
                "an uncommitted row must be reported as not-yet-visible, not as a sync fault — "
                        + "otherwise the scheduler marks a healthy new connection FAILED");
        assertEquals(connectionId.get(),
                ((CalendarConnectionNotVisibleException) failure).getConnectionId());
    }

    /**
     * The control: the same sweep, run after signup commits, succeeds. This is the incognito
     * signup from the logs, which landed 17s after its commit rather than 2s before it — the
     * account was never the variable, only the timing.
     */
    @Test
    void ingestingAfterSignupCommitsSucceeds() {
        TransactionTemplate signupTx = new TransactionTemplate(txManager);
        UUID connectionId = signupTx.execute(status ->
                connectionRepository.saveAndFlush(newConnection()).getId());

        // No exception: the row is visible, so the sweep does its job.
        ingestionService.upsertEvents(connectionId, List.of());

        assertEquals(CalendarConnectionStatus.SYNCING,
                connectionRepository.findById(connectionId).orElseThrow().getStatus());
    }

    /**
     * The second half of the fix, exercised through the real {@code canServe} path rather than a
     * re-implementation of its filter: a transiently-FAILED connection must still be able to serve
     * Google Meet. Requiring ACTIVE here is what turned a one-minute sync hiccup into a permanent
     * "reconnect your calendar" during signup, because onboarding sets the conferencing default
     * immediately after configureCalendar — which deliberately tolerates FAILED.
     */
    @Test
    void googleMeetIsStillServableWhileTheConnectionIsTransientlyFailed() {
        UUID connectionId = writebackConnectionWithStatus(CalendarConnectionStatus.FAILED);
        assertNotNull(connectionId);

        assertTrue(reconciler.canServe(user.getId(), ConferencingProviderType.GOOGLE_MEET),
                "a briefly-FAILED calendar must still serve Meet — it recovers on the next sweep, "
                        + "and onboarding only runs once");
    }

    /** The tolerance is not blanket: a connection the user actually revoked stays unservable. */
    @Test
    void googleMeetIsNotServableOnceTheConnectionIsRevoked() {
        writebackConnectionWithStatus(CalendarConnectionStatus.REVOKED);

        assertFalse(reconciler.canServe(user.getId(), ConferencingProviderType.GOOGLE_MEET));
    }

    /** The healthy baseline, so the two assertions above are read against a known-good case. */
    @Test
    void googleMeetIsServableOnAnActiveConnection() {
        writebackConnectionWithStatus(CalendarConnectionStatus.ACTIVE);

        assertTrue(reconciler.canServe(user.getId(), ConferencingProviderType.GOOGLE_MEET));
    }

    /** A writeback connection plus the readable/writable primary calendar canServe requires. */
    private UUID writebackConnectionWithStatus(CalendarConnectionStatus status) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(txStatus -> {
            CalendarConnection connection = newConnection();
            connection.setStatus(status);
            connection.setDefaultWriteback(true);
            UUID id = connectionRepository.saveAndFlush(connection).getId();

            CalendarConnectionCalendar calendar = new CalendarConnectionCalendar();
            calendar.setConnectionId(id);
            calendar.setExternalCalendarId("primary");
            calendar.setName("Primary");
            calendar.setPrimary(true);
            // AvailabilityCalendarPolicy gates on the role, not the boolean flag.
            calendar.setCalendarRole(io.bunnycal.calendar.domain.CalendarRole.PRIMARY);
            calendar.setSelected(true);
            calendar.setChecksAvailability(true);
            calendar.setCanRead(true);
            calendar.setCanWrite(true);
            calendar.setHidden(false);
            calendarRepository.saveAndFlush(calendar);
            return id;
        });
    }
}
