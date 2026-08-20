package io.bunnycal.calendar;

import io.bunnycal.testsupport.TestContainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.calendar.domain.CalendarConnection;
import io.bunnycal.calendar.domain.CalendarConnectionStatus;
import io.bunnycal.calendar.domain.CalendarProviderType;
import io.bunnycal.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the V118_0 identity change against a real Postgres + Flyway.
 *
 * <p>The bug this guards: {@code calendar_connections} used to carry UNIQUE (user_id, provider),
 * so a user could hold only one account per provider and the OAuth callbacks overwrote the
 * existing row on a second connect — silently destroying the first account's identity and
 * refresh token. These tests pin down the schema-level guarantees the new callbacks rely on.
 */
@SpringBootTest(classes = TestApplication.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
class MultiAccountCalendarIT {



    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestContainers.registerProperties(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired CalendarConnectionRepository connectionRepository;
    @Autowired PlatformTransactionManager txManager;

    private User user;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, calendar_connections CASCADE");
        user = userRepository.save(User.builder()
                .email("host@example.com").name("Host").timezone("UTC").build());
    }

    /** The old constraint is gone and the new account-scoped one is in place. */
    @Test
    void migrationReplacesTheOneAccountPerProviderRule() {
        Integer oldConstraint = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_calendar_connections_user_provider'",
                Integer.class);
        Integer oldIndex = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_calendar_user_provider'", Integer.class);
        Integer newIndex = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_calendar_user_provider_account'", Integer.class);

        assertThat(oldConstraint).isZero();
        assertThat(oldIndex).isZero();
        assertThat(newIndex).isOne();
    }

    /** The headline fix: two Google accounts for one user now coexist. */
    @Test
    void twoGoogleAccountsForTheSameUserCanCoexist() {
        CalendarConnection first = save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-A");
        CalendarConnection second = save(CalendarProviderType.GOOGLE, "google-sub-B", "b@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-B");

        assertThat(first.getId()).isNotEqualTo(second.getId());

        List<CalendarConnection> all =
                connectionRepository.findByUserIdAndProviderOrderByCreatedAtAsc(user.getId(), CalendarProviderType.GOOGLE);
        assertThat(all).hasSize(2);
        // Each account keeps its own credentials — this is precisely what the overwrite destroyed.
        assertThat(all).extracting(CalendarConnection::getRefreshTokenCiphertext)
                .containsExactlyInAnyOrder("cipher-A", "cipher-B");
        assertThat(all).extracting(CalendarConnection::getAccountEmail)
                .containsExactlyInAnyOrder("a@example.com", "b@example.com");
    }

    /** The same external account twice is still a duplicate; the index must reject it. */
    @Test
    void sameAccountTwiceIsRejected() {
        save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-A");

        assertThatThrownBy(() -> save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-dup"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Disconnect is a soft delete, so the REVOKED row keeps its slot in the unique index. The
     * identity finder therefore has to be status-agnostic — filtering to ACTIVE would make the
     * callback try to INSERT a duplicate and blow up on the index instead of reactivating.
     */
    @Test
    void identityFinderReturnsRevokedRowsSoReconnectCanReactivate() {
        CalendarConnection connection = save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.REVOKED, "cipher-A");

        Optional<CalendarConnection> found = connectionRepository.findByUserIdAndProviderAndProviderUserId(
                user.getId(), CalendarProviderType.GOOGLE, "google-sub-A");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(connection.getId());
        assertThat(found.get().getStatus()).isEqualTo(CalendarConnectionStatus.REVOKED);
    }

    /** A user may designate at most one write-back target, enforced by a partial unique index. */
    @Test
    void onlyOneDefaultWritebackPerUserIsAllowed() {
        CalendarConnection first = save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-A");
        first.setDefaultWriteback(true);
        connectionRepository.saveAndFlush(first);

        CalendarConnection second = save(CalendarProviderType.GOOGLE, "google-sub-B", "b@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-B");
        second.setDefaultWriteback(true);

        assertThatThrownBy(() -> connectionRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Clearing before setting is what lets the flag move without tripping the partial index. */
    @Test
    void defaultWritebackCanBeMovedBetweenAccounts() {
        CalendarConnection first = save(CalendarProviderType.GOOGLE, "google-sub-A", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-A");
        first.setDefaultWriteback(true);
        connectionRepository.saveAndFlush(first);
        CalendarConnection second = save(CalendarProviderType.GOOGLE, "google-sub-B", "b@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-B");

        // Mirrors CalendarConnectionManagementService.setDefaultWriteback: the clear and the set
        // are one transaction, which is what keeps the partial unique index satisfied throughout.
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            connectionRepository.clearDefaultWritebackForUser(user.getId());
            second.setDefaultWriteback(true);
            connectionRepository.saveAndFlush(second);
        });

        Optional<CalendarConnection> designated =
                connectionRepository.findByUserIdAndDefaultWritebackTrue(user.getId());
        assertThat(designated).isPresent();
        assertThat(designated.get().getId()).isEqualTo(second.getId());
    }

    /** A Google and a Microsoft account are different identities and must not collide. */
    @Test
    void differentProvidersDoNotCollide() {
        save(CalendarProviderType.GOOGLE, "shared-sub", "a@example.com",
                CalendarConnectionStatus.ACTIVE, "cipher-G");
        save(CalendarProviderType.MICROSOFT, "shared-sub", "a@outlook.com",
                CalendarConnectionStatus.ACTIVE, "cipher-M");

        assertThat(connectionRepository.findByUserIdAndStatus(user.getId(), CalendarConnectionStatus.ACTIVE))
                .hasSize(2);
    }

    private CalendarConnection save(CalendarProviderType provider,
                                    String providerUserId,
                                    String accountEmail,
                                    CalendarConnectionStatus status,
                                    String refreshTokenCiphertext) {
        CalendarConnection connection = new CalendarConnection();
        connection.setUserId(user.getId());
        connection.setProvider(provider);
        connection.setProviderUserId(providerUserId);
        connection.setAccountEmail(accountEmail);
        connection.setStatus(status);
        connection.setRefreshTokenCiphertext(refreshTokenCiphertext);
        connection.setLastTokenExpiresAt(Instant.now().plusSeconds(3600));
        connection.setScopes(List.of("https://www.googleapis.com/auth/calendar.readonly"));
        return connectionRepository.saveAndFlush(connection);
    }
}
