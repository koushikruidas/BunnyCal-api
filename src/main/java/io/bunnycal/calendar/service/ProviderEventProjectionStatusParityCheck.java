package io.bunnycal.calendar.service;

import io.bunnycal.calendar.domain.ProviderEventProjectionStatus;
import io.bunnycal.sync.invariants.CompositeSyncStateClassifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Asserts at boot that the projection_status enum (Java), the
 * {@link CompositeSyncStateClassifier.ProjectionLifecycle} enum, the DB
 * check constraint, and any rows currently persisted all agree on the same
 * set of allowed values. Drift between writer, schema and reader is what
 * produced the V40-era bug where the writer emitted CANCELLED/DELETED but
 * the constraint only allowed TOMBSTONED_SOFT/TOMBSTONED_HARD — fail loud
 * at startup instead of at the first Google tombstone.
 */
@Component
public class ProviderEventProjectionStatusParityCheck {
    private static final Logger log = LoggerFactory.getLogger(ProviderEventProjectionStatusParityCheck.class);
    private static final Pattern IN_CLAUSE = Pattern.compile("'([A-Z_]+)'");

    private final JdbcTemplate jdbcTemplate;

    public ProviderEventProjectionStatusParityCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyParity() {
        Set<String> enumNames = ProviderEventProjectionStatus.ALLOWED_NAMES;
        Set<String> lifecycleNames = Arrays.stream(CompositeSyncStateClassifier.ProjectionLifecycle.values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> constraintNames = readConstraintAllowedNames();
        Set<String> persistedNames = readDistinctPersistedNames();

        if (!enumNames.equals(lifecycleNames)) {
            throw new IllegalStateException(
                    "ProviderEventProjectionStatus enum (" + enumNames
                            + ") does not match CompositeSyncStateClassifier.ProjectionLifecycle (" + lifecycleNames + ")");
        }
        if (!constraintNames.isEmpty() && !enumNames.equals(constraintNames)) {
            throw new IllegalStateException(
                    "ProviderEventProjectionStatus enum (" + enumNames
                            + ") does not match ck_provider_event_projection_status (" + constraintNames + ")");
        }
        Set<String> illegalPersisted = new HashSet<>(persistedNames);
        illegalPersisted.removeAll(enumNames);
        if (!illegalPersisted.isEmpty()) {
            throw new IllegalStateException(
                    "provider_event_projections contains rows with projection_status outside the allowed enum: "
                            + illegalPersisted);
        }
        log.info("provider_event_projection_status_parity_ok values={} persistedDistinct={}",
                enumNames, persistedNames);
    }

    private Set<String> readConstraintAllowedNames() {
        try {
            List<String> defs = jdbcTemplate.queryForList(
                    "SELECT pg_get_constraintdef(c.oid) "
                            + "FROM pg_constraint c "
                            + "JOIN pg_class t ON t.oid = c.conrelid "
                            + "WHERE t.relname = 'provider_event_projections' "
                            + "AND c.conname = 'ck_provider_event_projection_status'",
                    String.class);
            if (defs.isEmpty()) {
                log.warn("provider_event_projection_status_parity_no_constraint table=provider_event_projections constraint=ck_provider_event_projection_status");
                return Set.of();
            }
            Matcher m = IN_CLAUSE.matcher(defs.get(0));
            Set<String> values = new HashSet<>();
            while (m.find()) {
                values.add(m.group(1));
            }
            return values;
        } catch (RuntimeException ex) {
            // Non-Postgres dialect (e.g. H2 in some legacy tests) — skip constraint check rather than blocking startup.
            log.info("provider_event_projection_status_parity_constraint_skipped reason={}", ex.getClass().getSimpleName());
            return Set.of();
        }
    }

    private Set<String> readDistinctPersistedNames() {
        try {
            List<String> values = jdbcTemplate.queryForList(
                    "SELECT DISTINCT projection_status FROM provider_event_projections", String.class);
            return values.stream().filter(v -> v != null).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException ex) {
            log.info("provider_event_projection_status_parity_distinct_skipped reason={}", ex.getClass().getSimpleName());
            return Set.of();
        }
    }
}
