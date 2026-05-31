package io.bunnycal.sync.orchestration;

import io.bunnycal.booking.outbox.OutboxEvent;
import io.bunnycal.sync.state.InternalRefType;
import io.bunnycal.sync.state.SyncDesiredAction;
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
            case "BOOKING_CONFIRMED" -> SyncDesiredAction.CREATE;
            case "BOOKING_UPDATED" -> SyncDesiredAction.UPDATE;
            case "BOOKING_CANCELLED" -> SyncDesiredAction.DELETE;
            default -> throw new IllegalArgumentException("Unsupported event type: " + eventType);
        };
    }
}
