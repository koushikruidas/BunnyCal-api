package com.daedalussystems.easySchedule.booking.sync;

import java.util.concurrent.atomic.AtomicLong;

public class AtomicFencingTokenGenerator implements FencingTokenGenerator {
    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis());

    @Override
    public long nextToken() {
        return counter.incrementAndGet();
    }
}
