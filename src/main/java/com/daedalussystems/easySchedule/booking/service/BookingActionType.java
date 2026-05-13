package com.daedalussystems.easySchedule.booking.service;

public enum BookingActionType {
    CANCEL,
    RESCHEDULE,
    MANAGE_BOOKING;

    public boolean allows(BookingActionType requestedAction) {
        if (this == MANAGE_BOOKING) {
            return true;
        }
        return this == requestedAction;
    }
}
