package io.bunnycal.booking.service;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.booking.domain.Booking;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BookingEventTypeResolver {

    private final EventTypeRepository eventTypeRepository;

    public BookingEventTypeResolver(EventTypeRepository eventTypeRepository) {
        this.eventTypeRepository = eventTypeRepository;
    }

    public EventType requireForBooking(Booking booking) {
        return requireByEventTypeId(booking.getEventTypeId());
    }

    public EventType requireByEventTypeId(UUID eventTypeId) {
        return eventTypeRepository.findById(eventTypeId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESOURCE_NOT_FOUND, "Event type not found."));
    }
}
