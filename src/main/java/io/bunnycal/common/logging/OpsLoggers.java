package io.bunnycal.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpsLoggers {

    public static final Logger BOOKING = LoggerFactory.getLogger("io.bunnycal.ops.booking");
    public static final Logger NOTIFICATION = LoggerFactory.getLogger("io.bunnycal.ops.notification");
    public static final Logger CONFERENCE = LoggerFactory.getLogger("io.bunnycal.ops.conference");
    public static final Logger HOST = LoggerFactory.getLogger("io.bunnycal.ops.host");
    public static final Logger SYNC_SCHEDULER = LoggerFactory.getLogger("io.bunnycal.sync.scheduler");
    public static final Logger SYNC_RECONCILE = LoggerFactory.getLogger("io.bunnycal.sync.reconcile");
    public static final Logger CALENDAR_HTTP = LoggerFactory.getLogger("io.bunnycal.calendar.http");
    public static final Logger AVAILABILITY_TRACE = LoggerFactory.getLogger("io.bunnycal.availability.trace");

    private OpsLoggers() {
    }
}
