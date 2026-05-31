package io.bunnycal.calendar.service;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ProviderEventVersionComparator {

    public ComparisonResult compare(VersionVector incoming, VersionVector persisted) {
        if (incoming == null) {
            return ComparisonResult.OLDER_OR_EQUAL;
        }
        if (persisted == null) {
            return ComparisonResult.NEWER;
        }

        if (incoming.providerSequence() != null && persisted.providerSequence() != null
                && !incoming.providerSequence().equals(persisted.providerSequence())) {
            return incoming.providerSequence() > persisted.providerSequence()
                    ? ComparisonResult.NEWER : ComparisonResult.OLDER_OR_EQUAL;
        }

        if (incoming.providerUpdatedAt() != null && persisted.providerUpdatedAt() != null
                && !incoming.providerUpdatedAt().equals(persisted.providerUpdatedAt())) {
            return incoming.providerUpdatedAt().isAfter(persisted.providerUpdatedAt())
                    ? ComparisonResult.NEWER : ComparisonResult.OLDER_OR_EQUAL;
        }

        if (incoming.providerEtag() != null && persisted.providerEtag() != null
                && !incoming.providerEtag().equals(persisted.providerEtag())) {
            return ComparisonResult.AMBIGUOUS_NEWER_HINT;
        }

        if (incoming.payloadHash() != null && persisted.payloadHash() != null
                && !incoming.payloadHash().equals(persisted.payloadHash())) {
            return ComparisonResult.AMBIGUOUS_NEWER_HINT;
        }

        return ComparisonResult.OLDER_OR_EQUAL;
    }

    public record VersionVector(
            Long providerSequence,
            Instant providerUpdatedAt,
            String providerEtag,
            String payloadHash
    ) {}

    public enum ComparisonResult {
        NEWER,
        AMBIGUOUS_NEWER_HINT,
        OLDER_OR_EQUAL
    }
}
