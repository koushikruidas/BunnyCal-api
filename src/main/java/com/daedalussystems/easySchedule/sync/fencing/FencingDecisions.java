package com.daedalussystems.easySchedule.sync.fencing;

public final class FencingDecisions {
    private FencingDecisions() {
    }

    public static boolean isStaleVersion(long expectedVersion, int rowsUpdated) {
        return rowsUpdated == 0 && expectedVersion >= 0;
    }
}
