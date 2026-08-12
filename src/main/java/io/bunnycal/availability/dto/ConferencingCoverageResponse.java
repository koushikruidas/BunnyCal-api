package io.bunnycal.availability.dto;

/**
 * How many of a proposed round-robin roster could actually mint the meeting link for each
 * selectable conferencing option, so the host can choose one that excludes nobody.
 *
 * <p><b>Deliberately aggregate.</b> Counts only — never which member lacks what. The host is
 * choosing a provider for an event, not auditing colleagues, so a total is all the decision needs
 * and it keeps one user's integrations from being enumerated to another. (In a two-person team a
 * count still narrows things by arithmetic; that is a mitigation, not a guarantee.)
 *
 * <p>Only ZOOM and DEFAULT appear. NONE and CUSTOM_URL need no per-host minting and would score a
 * perfect count, which would rank "no meeting link at all" as the best option. Google Meet and
 * Teams are never selectable on an event type — they are only ever what DEFAULT resolves to,
 * per host (see {@code EventConferencingResolver}).
 *
 * @param totalParticipants roster size the counts are out of
 * @param zoomCapableCount  members with a live Zoom connection
 * @param defaultCapableCount members whose own default meeting link resolves to something they can
 *                            actually mint — usually the highest, since it adapts per person
 */
public record ConferencingCoverageResponse(
        int totalParticipants,
        int zoomCapableCount,
        int defaultCapableCount
) {
}
