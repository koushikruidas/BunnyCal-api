package io.bunnycal.availability.domain;

/** Controls which registration activity is emailed to a GROUP event host. */
public enum GroupHostNotificationMode {
    /** First/full/reopened/empty updates immediately; ordinary activity goes to a digest. */
    SMART_SUMMARY,
    /** Send a plain host email for every confirmed registration and cancellation. */
    EVERY_REGISTRATION,
    /** Queue every registration and cancellation for a rolling daily digest. */
    DAILY_DIGEST,
    /** Send only first/full/reopened/empty updates; discard ordinary activity. */
    IMPORTANT_ONLY,
    /** Do not email the host about registration activity. */
    NONE
}
