package com.daedalussystems.easySchedule.sync.invariants;

import com.daedalussystems.easySchedule.booking.contract.BookingState;
import com.daedalussystems.easySchedule.sync.state.SyncJobStatus;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SemanticGovernanceTest {

    @Test
    void governanceLock_matchesReviewedArtifacts_andEnumCardinality() throws Exception {
        Map<String, String> lock = readLock();
        assertEquals(lock.get("matrix_sha256"), sha256("src/main/resources/sync/composite_state_matrix.csv"),
                "Matrix hash lock mismatch. Explicit governance update required.");
        assertEquals(lock.get("rationale_catalog_sha256"), sha256("src/main/resources/sync/composite_state_rationale_catalog.csv"),
                "Rationale catalog hash lock mismatch. Explicit governance update required.");

        assertEquals(Integer.parseInt(lock.get("booking_state_count")), BookingState.values().length,
                "booking_state_count changed; add explicit matrix rows + rationale review + lock update.");
        assertEquals(Integer.parseInt(lock.get("sync_status_count")), SyncJobStatus.values().length,
                "sync_status_count changed; add explicit matrix rows + rationale review + lock update.");
        assertEquals(Integer.parseInt(lock.get("projection_lifecycle_count")), CompositeSyncStateClassifier.ProjectionLifecycle.values().length,
                "projection_lifecycle_count changed; add explicit matrix rows + rationale review + lock update.");
        assertEquals(Integer.parseInt(lock.get("participation_lifecycle_count")), CompositeSyncStateClassifier.ParticipationLifecycle.values().length,
                "participation_lifecycle_count changed; add explicit matrix rows + rationale review + lock update.");
    }

    @Test
    void exhaustiveCompositeCoverage_noDuplicates_noDeadRationales_noSilentExpansion() throws Exception {
        MatrixAudit audit = parseMatrix();
        int expected = BookingState.values().length
                * SyncJobStatus.values().length
                * CompositeSyncStateClassifier.ProjectionLifecycle.values().length
                * CompositeSyncStateClassifier.ParticipationLifecycle.values().length;

        assertEquals(expected, audit.rows.size(),
                "Composite matrix row count mismatch. Every tuple must be explicitly classified exactly once.");
        assertEquals(expected, audit.uniqueKeys.size(),
                "Duplicate composite tuple rows detected. Remove overlap to preserve deterministic semantics.");

        Set<CompositeSyncStateClassifier.CompositeKey> expectedKeys = allKeys();
        Set<CompositeSyncStateClassifier.CompositeKey> missing = new LinkedHashSet<>(expectedKeys);
        missing.removeAll(audit.uniqueKeys);
        assertTrue(missing.isEmpty(), "Missing composite tuples: " + missing);

        Set<String> referencedRationales = new LinkedHashSet<>();
        for (MatrixRow row : audit.rows) {
            referencedRationales.add(row.rationale);
            assertNotNull(row.classification, diagnostic("classification null", row));
        }

        Map<String, CatalogRow> catalog = readCatalog();
        assertEquals(referencedRationales, catalog.keySet(),
                "Dead or missing rationale entries detected between matrix and catalog.");
    }

    @Test
    void rationaleCatalog_entriesMustIncludeOwnerAndPolicyReference() throws Exception {
        Map<String, CatalogRow> catalog = readCatalog();
        assertFalse(catalog.isEmpty(), "Rationale catalog must not be empty.");

        for (Map.Entry<String, CatalogRow> e : catalog.entrySet()) {
            String rationale = e.getKey();
            CatalogRow row = e.getValue();
            assertFalse(isBlank(rationale), "Rationale key missing.");
            assertFalse(isBlank(row.owner), "Rationale owner missing for: " + rationale);
            assertFalse(isBlank(row.policyRef), "Rationale policy reference missing for: " + rationale);
        }
    }

    @Test
    void matrixRows_requireRationaleOwnerPolicy_andDisallowWildcardSemantics() throws Exception {
        MatrixAudit audit = parseMatrix();
        for (MatrixRow row : audit.rows) {
            assertFalse(isBlank(row.rationale), diagnostic("missing rationale", row));
            assertFalse(isBlank(row.owner), diagnostic("missing owner", row));
            assertFalse(isBlank(row.policyRef), diagnostic("missing policy_ref", row));
            assertFalse(containsWildcard(row), diagnostic("wildcard semantics forbidden", row));
        }
    }

    @Test
    void matrixOrder_matchesDeterministicTupleEnumeration() throws Exception {
        MatrixAudit audit = parseMatrix();
        List<CompositeSyncStateClassifier.CompositeKey> actualOrder = audit.rows.stream().map(MatrixRow::key).toList();
        List<CompositeSyncStateClassifier.CompositeKey> expectedOrder = new ArrayList<>(allKeys());
        assertEquals(expectedOrder, actualOrder,
                "Matrix row ordering drift detected. Keep deterministic tuple enumeration order for stable review diffs.");
    }

    @Test
    void snapshotStyle_reviewVisibility_listsClassificationOwnershipAndRationale() throws Exception {
        MatrixAudit audit = parseMatrix();
        long legalSteady = audit.rows.stream().filter(r -> r.classification == CompositeSyncStateClassifier.Classification.LEGAL_STEADY).count();
        long legalTransient = audit.rows.stream().filter(r -> r.classification == CompositeSyncStateClassifier.Classification.LEGAL_TRANSIENT).count();
        long repairRequired = audit.rows.stream().filter(r -> r.classification == CompositeSyncStateClassifier.Classification.REPAIR_REQUIRED).count();
        long illegalAlert = audit.rows.stream().filter(r -> r.classification == CompositeSyncStateClassifier.Classification.ILLEGAL_ALERT).count();

        assertTrue(legalSteady > 0 && legalTransient > 0 && repairRequired > 0 && illegalAlert > 0,
                "Classification distribution unexpectedly empty for one or more categories.");

        Map<String, Long> owners = new TreeMap<>();
        for (MatrixRow row : audit.rows) {
            owners.merge(row.owner, 1L, Long::sum);
        }
        assertFalse(owners.isEmpty(), "Owner snapshot cannot be empty.");
    }

    private static MatrixAudit parseMatrix() throws Exception {
        List<MatrixRow> rows = new ArrayList<>();
        Set<CompositeSyncStateClassifier.CompositeKey> uniqueKeys = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(SemanticGovernanceTest.class.getResourceAsStream("/sync/composite_state_matrix.csv")),
                StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            assertNotNull(header, "Matrix header missing");
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String[] p = line.split(",", -1);
                assertEquals(8, p.length, "Invalid matrix column count at line " + lineNo);
                MatrixRow row = new MatrixRow(lineNo, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
                rows.add(row);

                CompositeSyncStateClassifier.CompositeKey key = row.key();
                assertTrue(uniqueKeys.add(key), "Duplicate composite tuple row at line " + lineNo + ": " + key);
            }
        }
        return new MatrixAudit(rows, uniqueKeys);
    }

    private static Map<String, String> readLock() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/sync/semantic_governance.lock"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || !line.contains("=")) continue;
            String[] p = line.split("=", 2);
            out.put(p[0].trim().toLowerCase(Locale.ROOT), p[1].trim());
        }
        return out;
    }

    private static Map<String, CatalogRow> readCatalog() throws Exception {
        Map<String, CatalogRow> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(
                Path.of("src/main/resources/sync/composite_state_rationale_catalog.csv"), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            assertNotNull(header);
            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String[] p = line.split(",", -1);
                assertEquals(4, p.length, "Invalid rationale catalog row at line " + lineNo);
                String rationale = p[0];
                assertFalse(out.containsKey(rationale), "Duplicate rationale catalog key at line " + lineNo + ": " + rationale);
                out.put(rationale, new CatalogRow(p[1], p[2], p[3]));
            }
        }
        return out;
    }

    private static Set<CompositeSyncStateClassifier.CompositeKey> allKeys() {
        Set<CompositeSyncStateClassifier.CompositeKey> out = new LinkedHashSet<>();
        for (BookingState b : BookingState.values()) {
            for (SyncJobStatus s : SyncJobStatus.values()) {
                for (CompositeSyncStateClassifier.ProjectionLifecycle p : CompositeSyncStateClassifier.ProjectionLifecycle.values()) {
                    for (CompositeSyncStateClassifier.ParticipationLifecycle r : CompositeSyncStateClassifier.ParticipationLifecycle.values()) {
                        out.add(new CompositeSyncStateClassifier.CompositeKey(b, s, p, r));
                    }
                }
            }
        }
        return out;
    }

    private static String sha256(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(path));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean containsWildcard(MatrixRow row) {
        return row.bookingState.contains("*")
                || row.syncStatus.contains("*")
                || row.projectionLifecycle.contains("*")
                || row.participationLifecycle.contains("*")
                || row.classificationRaw.contains("*");
    }

    private static String diagnostic(String msg, MatrixRow row) {
        return msg + " line=" + row.lineNo + " row=" + row;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record MatrixAudit(List<MatrixRow> rows, Set<CompositeSyncStateClassifier.CompositeKey> uniqueKeys) {}

    private static final class MatrixRow {
        private final int lineNo;
        private final String bookingState;
        private final String syncStatus;
        private final String projectionLifecycle;
        private final String participationLifecycle;
        private final String classificationRaw;
        private final String owner;
        private final String rationale;
        private final String policyRef;
        private final CompositeSyncStateClassifier.Classification classification;

        private MatrixRow(int lineNo,
                          String bookingState,
                          String syncStatus,
                          String projectionLifecycle,
                          String participationLifecycle,
                          String classificationRaw,
                          String owner,
                          String rationale,
                          String policyRef) {
            this.lineNo = lineNo;
            this.bookingState = bookingState;
            this.syncStatus = syncStatus;
            this.projectionLifecycle = projectionLifecycle;
            this.participationLifecycle = participationLifecycle;
            this.classificationRaw = classificationRaw;
            this.owner = owner;
            this.rationale = rationale;
            this.policyRef = policyRef;
            this.classification = CompositeSyncStateClassifier.Classification.valueOf(classificationRaw);
        }

        private CompositeSyncStateClassifier.CompositeKey key() {
            return new CompositeSyncStateClassifier.CompositeKey(
                    BookingState.valueOf(bookingState),
                    SyncJobStatus.valueOf(syncStatus),
                    CompositeSyncStateClassifier.ProjectionLifecycle.valueOf(projectionLifecycle),
                    CompositeSyncStateClassifier.ParticipationLifecycle.valueOf(participationLifecycle));
        }

        @Override
        public String toString() {
            return bookingState + "," + syncStatus + "," + projectionLifecycle + "," + participationLifecycle + ","
                    + classificationRaw + "," + owner + "," + rationale + "," + policyRef;
        }
    }

    private record CatalogRow(String owner, String policyRef, String description) {}
}
