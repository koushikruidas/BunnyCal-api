package io.bunnycal.sync.reconcile;

import io.bunnycal.sync.domain.SyncReconcileInputSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class PersistedSnapshotCanonicalizer {
    private static final int SCHEMA_VERSION = 1;
    private static final ObjectWriter WRITER = buildWriter();

    public String hash(SyncReconcileInputSnapshot snapshot) {
        String json = canonicalJson(snapshot);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String canonicalJson(SyncReconcileInputSnapshot s) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("syncJobId", norm(s.getSyncJobId() == null ? "" : s.getSyncJobId().toString()));
        map.put("bookingId", norm(s.getBookingId() == null ? "" : s.getBookingId().toString()));
        map.put("provider", norm(empty(s.getProvider())));
        map.put("externalEventId", norm(empty(s.getExternalEventId())));
        map.put("bookingState", empty(s.getBookingState()));
        map.put("syncStatus", empty(s.getSyncStatus()));
        map.put("projectionLifecycle", empty(s.getProjectionLifecycle()));
        map.put("participationLifecycle", empty(s.getParticipationLifecycle()));
        map.put("invariantClassification", empty(s.getInvariantClassification()));
        map.put("desiredAction", empty(s.getDesiredAction()));
        map.put("observedStatus", empty(s.getObservedStatus()));
        map.put("observedErrorCode", norm(empty(s.getObservedErrorCode())));
        map.put("projectionVersion", s.getProjectionVersion());
        map.put("terminalIntentEpoch", s.getTerminalIntentEpoch());
        map.put("projectionConnectionId", s.getProjectionConnectionId() == null ? "" : s.getProjectionConnectionId().toString());
        map.put("providerUpdatedAt", s.getProviderUpdatedAt() == null ? "" : s.getProviderUpdatedAt().toString());
        map.put("providerEtag", norm(empty(s.getProviderEtag())));
        map.put("providerSequence", s.getProviderSequence());
        map.put("recurringHint", s.isRecurringHint());
        map.put("correlationId", norm(empty(s.getCorrelationId())));
        map.put("causationId", norm(empty(s.getCausationId())));
        map.put("lineageSource", empty(s.getLineageSource()));
        try {
            return WRITER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize persisted snapshot", e);
        }
    }

    private static String empty(String v) { return v == null ? "" : v; }
    private static String norm(String v) { return Normalizer.normalize(v, Normalizer.Form.NFC); }

    private static ObjectWriter buildWriter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper.writer();
    }
}
