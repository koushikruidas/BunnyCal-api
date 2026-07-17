package io.bunnycal.availability.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.auth.repository.UserRepository;
import io.bunnycal.auth.service.SessionUserResolver;
import io.bunnycal.availability.domain.EventKind;
import io.bunnycal.availability.domain.EventType;
import io.bunnycal.availability.repository.EventTypeRepository;
import io.bunnycal.availability.repository.GroupEventReservationWindowRepository;
import io.bunnycal.booking.outbox.OutboxPublisher;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.common.enums.ErrorCode;
import io.bunnycal.common.exception.CustomException;
import io.bunnycal.common.time.TimeSource;
import io.bunnycal.experience.repository.BookingExperienceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventTypeDeleteGuardTest {

    @Mock private EventTypeRepository eventTypeRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionUserResolver sessionUserResolver;
    @Mock private EventTypeOrchestrationNormalizer orchestrationNormalizer;
    @Mock private PublishReadinessService publishReadinessService;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private BookingExperienceRepository experienceRepository;
    @Mock private io.bunnycal.billing.entitlement.EntitlementService entitlementService;
    @Mock private GroupEventReservationWindowRepository reservationWindowRepository;
    @Mock private io.bunnycal.availability.repository.EventAvailabilityWindowRepository eventAvailabilityWindowRepository;

    private final TimeSource timeSource = () -> Instant.parse("2026-06-24T12:00:00Z");

    private EventTypeService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID eventTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EventTypeService(
                eventTypeRepository,
                userRepository,
                sessionUserResolver,
                orchestrationNormalizer,
                publishReadinessService,
                outboxPublisher,
                timeSource,
                experienceRepository,
                entitlementService,
                reservationWindowRepository,
                eventAvailabilityWindowRepository);
    }

    private EventType ownedEventType() {
        return EventType.builder()
                .id(eventTypeId)
                .userId(ownerId)
                .name("Sales Demo")
                .slug("sales-demo")
                .duration(Duration.ofMinutes(30))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofMinutes(30))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .kind(EventKind.ONE_ON_ONE)
                .capacity(1)
                .conferencingProvider(ConferencingProviderType.NONE)
                .build();
    }

    @Test
    void delete_softDeletesWhenNoExperienceReferencesIt() {
        EventType eventType = ownedEventType();
        when(eventTypeRepository.findByIdAndDeletedAtIsNull(eventTypeId)).thenReturn(Optional.of(eventType));
        when(experienceRepository.existsByEventTypeIdAndDeletedAtIsNull(eventTypeId)).thenReturn(false);

        service.delete(ownerId, eventTypeId);

        ArgumentCaptor<EventType> saved = ArgumentCaptor.forClass(EventType.class);
        verify(eventTypeRepository).save(saved.capture());
        assertNotNull(saved.getValue().getDeletedAt());
        assertEquals(timeSource.now(), saved.getValue().getDeletedAt());
    }

    @Test
    void delete_blockedWhenReferencedByAnyNonDeletedExperience() {
        EventType eventType = ownedEventType();
        when(eventTypeRepository.findByIdAndDeletedAtIsNull(eventTypeId)).thenReturn(Optional.of(eventType));
        when(experienceRepository.existsByEventTypeIdAndDeletedAtIsNull(eventTypeId)).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> service.delete(ownerId, eventTypeId));

        assertEquals(ErrorCode.EVENT_TYPE_ATTACHED_TO_EXPERIENCE, ex.getErrorCode());
        assertNull(eventType.getDeletedAt());
        verify(eventTypeRepository, never()).save(any());
    }

    @Test
    void delete_notFoundDoesNotCheckExperienceReferences() {
        when(eventTypeRepository.findByIdAndDeletedAtIsNull(eventTypeId)).thenReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class, () -> service.delete(ownerId, eventTypeId));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(experienceRepository, never()).existsByEventTypeIdAndDeletedAtIsNull(any());
    }
}
