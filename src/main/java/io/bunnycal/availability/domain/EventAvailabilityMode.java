package io.bunnycal.availability.domain;

/**
 * Controls where a demand-driven event type gets its recurring operating hours.
 *
 * <p>{@link #INHERIT} follows the relevant participant availability directly.
 * {@link #CUSTOM} uses the event's own recurring windows. For ONE_ON_ONE those
 * windows replace the owner's weekly defaults; for ROUND_ROBIN and COLLECTIVE
 * they remain an event-level operating window and never override another
 * participant's personal availability.
 */
public enum EventAvailabilityMode {
    INHERIT,
    CUSTOM
}
