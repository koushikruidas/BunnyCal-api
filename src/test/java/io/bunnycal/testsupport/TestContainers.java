package io.bunnycal.testsupport;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one Postgres and the one Redis the whole suite shares.
 *
 * <p>Every integration test used to declare its own pair in a static block, so a full run started
 * roughly 25 Postgres containers and replayed all ~148 migrations into each of them. That is about
 * 3,700 migration executions per run, and on a two-core CI runner it was the single largest cost in
 * the pipeline -- the test step took over five minutes there against about two locally, because the
 * containers all boot at once and starve each other of CPU.
 *
 * <p>Started once, on first touch, and never stopped: Ryuk removes it when the JVM exits. The
 * containers are deliberately NOT {@code withReuse(true)} -- reuse helps repeated local runs but
 * does nothing on an ephemeral CI runner, and it makes leftover state from a previous run a
 * possible cause of a failure, which is a bad trade for a suite this size.
 *
 * <p><strong>Tests now share a database.</strong> Each class no longer gets a pristine schema, so a
 * test that asserts on "all rows of X" must scope that query to data it created. Anything that
 * genuinely needs isolation -- lock contention, races on a shared table -- should keep its own
 * container rather than being forced onto this one.
 */
public final class TestContainers {

    private static final PostgreSQLContainer<?> POSTGRES;
    private static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                // One Postgres now backs every Spring context in the run, and each context
                // brings its own Hikari pool. The default max_connections of 100 is not
                // enough for that many pools and the suite fails with "too many clients
                // already" rather than on anything a test asserted.
                .withCommand("postgres", "-c", "max_connections=500");
        REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        POSTGRES.start();
        REDIS.start();
        // Migrate once here rather than letting Spring do it per context: the schema is identical
        // for every test class, and this is the work that was being repeated 25 times.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private TestContainers() {
    }

    /** Points a Spring context at the shared containers. Mirrors what each class declared inline. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Each context keeps a small pool: tests are single-threaded against the DB, so a
        // large pool only reserves connections that other contexts then cannot open.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
