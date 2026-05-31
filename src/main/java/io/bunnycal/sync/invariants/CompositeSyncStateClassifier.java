package io.bunnycal.sync.invariants;

import io.bunnycal.booking.contract.BookingState;
import io.bunnycal.sync.state.SyncJobStatus;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class CompositeSyncStateClassifier {

    private static final String MATRIX_RESOURCE = "/sync/composite_state_matrix.csv";
    private final Map<CompositeKey, ReviewedClassification> matrix = loadMatrix();

    public Classification classify(BookingState bookingState,
                                   SyncJobStatus syncStatus,
                                   ProjectionLifecycle projectionLifecycle,
                                   ParticipationLifecycle participationLifecycle) {
        return reviewedClassification(new CompositeKey(bookingState, syncStatus, projectionLifecycle, participationLifecycle))
                .classification();
    }

    public ReviewedClassification reviewedClassification(CompositeKey key) {
        ReviewedClassification classification = matrix.get(key);
        if (classification == null) {
            throw new IllegalStateException("Unclassified composite sync state: " + key);
        }
        return classification;
    }

    public int coveredCombinationCount() {
        return matrix.size();
    }

    public int expectedCombinationCount() {
        return BookingState.values().length
                * SyncJobStatus.values().length
                * ProjectionLifecycle.values().length
                * ParticipationLifecycle.values().length;
    }

    Map<CompositeKey, ReviewedClassification> reviewedMatrix() {
        return matrix;
    }

    private static Map<CompositeKey, ReviewedClassification> loadMatrix() {
        try (var stream = CompositeSyncStateClassifier.class.getResourceAsStream(MATRIX_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing matrix resource: " + MATRIX_RESOURCE);
            }
            Map<CompositeKey, ReviewedClassification> loaded = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String header = reader.readLine();
                if (header == null || !header.startsWith("booking_state")) {
                    throw new IllegalStateException("Invalid matrix header");
                }
                String line;
                int lineNo = 1;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    String[] p = line.split(",", -1);
                    if (p.length != 8) {
                        throw new IllegalStateException("Invalid matrix row at line " + lineNo + ": " + line);
                    }
                    CompositeKey key = new CompositeKey(
                            BookingState.valueOf(p[0]),
                            SyncJobStatus.valueOf(p[1]),
                            ProjectionLifecycle.valueOf(p[2]),
                            ParticipationLifecycle.valueOf(p[3]));
                    ReviewedClassification value = new ReviewedClassification(
                            Classification.valueOf(p[4]),
                            p[5],
                            p[6],
                            p[7]
                    );
                    validateReviewedSemantics(value, lineNo, key);
                    if (loaded.putIfAbsent(key, value) != null) {
                        throw new IllegalStateException("Duplicate matrix key at line " + lineNo + ": " + key);
                    }
                }
            }
            validateCoverage(loaded);
            return Map.copyOf(loaded);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load composite state matrix", ex);
        }
    }

    private static void validateReviewedSemantics(ReviewedClassification value, int lineNo, CompositeKey key) {
        if (isBlank(value.owner()) || isBlank(value.rationale()) || isBlank(value.policyRef())) {
            throw new IllegalStateException("Missing reviewed metadata at line " + lineNo + ": " + key);
        }
        String rationale = value.rationale().toLowerCase(Locale.ROOT);
        if (rationale.contains("default") || rationale.contains("unmodeled")) {
            throw new IllegalStateException("Placeholder rationale is forbidden at line " + lineNo + ": " + key);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateCoverage(Map<CompositeKey, ReviewedClassification> loaded) {
        int expected = BookingState.values().length
                * SyncJobStatus.values().length
                * ProjectionLifecycle.values().length
                * ParticipationLifecycle.values().length;
        if (loaded.size() != expected) {
            throw new IllegalStateException("Incomplete matrix coverage: expected=" + expected + " actual=" + loaded.size());
        }
        for (BookingState b : BookingState.values()) {
            for (SyncJobStatus s : SyncJobStatus.values()) {
                for (ProjectionLifecycle p : ProjectionLifecycle.values()) {
                    for (ParticipationLifecycle r : ParticipationLifecycle.values()) {
                        CompositeKey key = new CompositeKey(b, s, p, r);
                        if (!loaded.containsKey(key)) {
                            throw new IllegalStateException("Missing matrix key: " + key);
                        }
                    }
                }
            }
        }
    }

    public enum Classification {
        LEGAL_STEADY,
        LEGAL_TRANSIENT,
        REPAIR_REQUIRED,
        ILLEGAL_ALERT
    }

    public enum ProjectionLifecycle {
        ACTIVE,
        TOMBSTONED_SOFT,
        TOMBSTONED_HARD
    }

    public enum ParticipationLifecycle {
        NEEDS_ACTION,
        ACCEPTED,
        TENTATIVE,
        DECLINED
    }

    public record CompositeKey(
            BookingState bookingState,
            SyncJobStatus syncStatus,
            ProjectionLifecycle projectionLifecycle,
            ParticipationLifecycle participationLifecycle
    ) {
    }

    public record ReviewedClassification(
            Classification classification,
            String owner,
            String rationale,
            String policyRef
    ) {
    }
}
