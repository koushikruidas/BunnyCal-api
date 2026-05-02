package com.daedalussystems.easySchedule.booking.outbox;

/**
 * Downstream delivery contract for outbox events.
 *
 * <p>Implementations MUST be idempotent: the outbox guarantees at-least-once
 * delivery, so the same event may be dispatched more than once in crash
 * recovery scenarios (the {@code processed_events} guard eliminates most
 * duplicates, but external systems outside the transaction boundary may still
 * receive duplicates if a commit is lost between dispatch and DB write).
 *
 * <p>Implementations MUST throw {@link RuntimeException} to signal a
 * transient failure. The outbox worker will roll back the current TX,
 * increment the attempt counter, schedule a backoff retry, and try again.
 * Permanent failures (e.g. invalid payload) should be logged and suppressed
 * or wrapped in a domain-specific unchecked exception so the event is not
 * retried forever — alternatively, exhaust {@code OUTBOX_MAX_ATTEMPTS} to let
 * the system mark it FAILED automatically.
 */
@FunctionalInterface
public interface OutboxEventDispatcher {

    void dispatch(OutboxEvent event);
}
