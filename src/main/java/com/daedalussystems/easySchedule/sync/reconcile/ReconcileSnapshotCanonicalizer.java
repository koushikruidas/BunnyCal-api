package com.daedalussystems.easySchedule.sync.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ReconcileSnapshotCanonicalizer {
    private static final int SCHEMA_VERSION = 1;
    private static final ObjectWriter CANONICAL_WRITER = buildCanonicalWriter();

    public ReconcileSnapshotCanonicalizer() {
    }

    public String canonicalJson(ReconcileInputSnapshot snapshot) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", SCHEMA_VERSION);
        canonical.put("syncJobId", normalizeString(string(snapshot.syncJobId())));
        canonical.put("bookingId", normalizeString(string(snapshot.bookingId())));
        canonical.put("provider", normalizeString(emptyIfNull(snapshot.provider())));
        canonical.put("externalEventId", normalizeString(emptyIfNull(snapshot.externalEventId())));
        canonical.put("syncJobStatus", snapshot.syncJobStatus().name());
        canonical.put("desiredAction", snapshot.desiredAction().name());
        canonical.put("observedStatus", snapshot.observedStatus().name());
        canonical.put("observedErrorCode", normalizeString(emptyIfNull(snapshot.observedErrorCode())));
        canonical.put("projectionVersion", snapshot.projectionVersion());
        canonical.put("terminalIntentEpoch", snapshot.terminalIntentEpoch());
        try {
            return CANONICAL_WRITER.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical reconcile snapshot", e);
        }
    }

    public String hash(ReconcileInputSnapshot snapshot) {
        String canonical = canonicalJson(snapshot);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeString(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFC);
    }

    private static ObjectWriter buildCanonicalWriter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        return mapper.writer();
    }
}
