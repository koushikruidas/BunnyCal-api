package io.bunnycal.availability.domain;

public enum AvailabilityMode {
    /** Check all active calendar connections for busy/free. Legacy behavior and default for all existing rows. */
    ALL_CONNECTED,
    /** Check only the connections listed in availability_calendars_json. Empty list = no calendar blocking. */
    SELECTED
}
