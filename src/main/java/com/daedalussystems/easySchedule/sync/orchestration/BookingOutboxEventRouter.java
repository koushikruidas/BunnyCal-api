package com.daedalussystems.easySchedule.sync.orchestration;

import com.daedalussystems.easySchedule.booking.outbox.OutboxEvent;
import com.daedalussystems.easySchedule.sync.state.InternalRefType;
import com.daedalussystems.easySchedule.sync.state.SyncDesiredAction;
import org.springframework.stereotype.Component;

@Component
public class BookingOutboxEventRouter {

    public SyncJobPlan toPlan(OutboxEvent event, String provider) {
        return new SyncJobPlan(
                InternalRefType.BOOKING,
                event.getAggregateId(),
                toDesiredAction(event.getEventType()),
                provider
        );
    }

    private static SyncDesiredAction toDesiredAction(String eventType) {
        return switch (eventType) {
            case "BOOKING_CREATED" -> SyncDesiredAction.CREATE;
            case "BOOKING_UPDATED" -> SyncDesiredAction.UPDATE;
            case "BOOKING_CANCELLED" -> SyncDesiredAction.DELETE;
            default -> throw new IllegalArgumentException("Unsupported event type: " + eventType);
        };
    }
}
