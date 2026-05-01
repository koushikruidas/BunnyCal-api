package com.daedalussystems.easySchedule.common.time;

import java.time.Instant;

/**
 * Project-wide abstraction for the only sanctioned time source: the database.
 *
 * <p>Any code that performs state transitions, expiry checks, overlap windows,
 * or any other time-sensitive decision MUST resolve its notion of "now" through
 * this interface. JVM wall-clock time ({@link Instant#now()},
 * {@link System#currentTimeMillis()}, {@code LocalDateTime.now()}, etc.) is
 * forbidden in those code paths.
 *
 * <p>See {@code booking/system_contracts.md} (Time Source Policy) for the full
 * rationale.
 */
public interface TimeSource {

    /**
     * Returns the current time as observed by the database.
     */
    Instant now();
}
