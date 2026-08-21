package io.bunnycal.conferencing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.bunnycal.availability.domain.EventType;
import io.bunnycal.common.enums.ConferencingProviderType;
import io.bunnycal.conferencing.domain.ConferencingEventMapping;
import io.bunnycal.conferencing.repository.ConferencingEventMappingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Group sessions used to drop every provider that was not Meet/Teams/custom-URL onto "no
 * conferencing". A Zoom group event therefore synced a calendar event with no join link, the outbox
 * readiness guard correctly withheld the confirmation email, and the booking stalled with nothing in
 * the logs naming Zoom. These tests pin each branch.
 */
class SessionConferencingCoordinatorTest {

    private static final Instant START = Instant.parse("2026-09-11T09:30:00Z");
    private static final Instant END = Instant.parse("2026-09-11T10:30:00Z");

    @Test
    void prepare_zoomSession_createsMeetingAndEmbedsJoinUrl() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        ConferencingProvider zoom = mock(ConferencingProvider.class);

        when(zoom.providerType()).thenReturn(ConferencingProviderType.ZOOM);
        when(resolver.resolve(eq(hostId), any(EventType.class))).thenReturn(ConferencingProviderType.ZOOM);
        when(mappingRepository.findByBookingIdAndProvider(sessionId, ConferencingProviderType.ZOOM))
                .thenReturn(Optional.empty());
        when(zoom.createMeeting(eq(sessionId), eq(hostId), eq("Math Class"), eq(START), eq(END)))
                .thenReturn(new ConferencingProvider.MeetingDetails(
                        "8123456789", "https://zoom.us/j/8123456789", "https://zoom.us/s/8123456789"));

        SessionConferencingCoordinator coordinator = coordinator(resolver, mappingRepository, zoom);

        ConferencingInstruction instruction =
                coordinator.prepare(sessionId, hostId, eventType("Math Class"), START, END);

        assertEquals(ConferencingProviderType.ZOOM, instruction.providerType());
        assertEquals(ConferencingInstruction.Mode.URL_EMBEDDED, instruction.mode());
        assertEquals("https://zoom.us/j/8123456789", instruction.joinUrl());
        assertEquals("8123456789", instruction.meetingId());
        // The regression: a Zoom session must carry a join link the calendar event can embed.
        assertTrue(instruction.embedsExternalUrl());

        ArgumentCaptor<ConferencingEventMapping> saved = ArgumentCaptor.forClass(ConferencingEventMapping.class);
        verify(mappingRepository).save(saved.capture());
        assertEquals(sessionId, saved.getValue().getBookingId());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        assertEquals("https://zoom.us/j/8123456789", saved.getValue().getJoinUrl());
    }

    @Test
    void prepare_zoomSessionWithExistingMeeting_updatesRatherThanCreatingASecondOne() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        ConferencingProvider zoom = mock(ConferencingProvider.class);

        ConferencingEventMapping existing = new ConferencingEventMapping();
        existing.setBookingId(sessionId);
        existing.setProvider(ConferencingProviderType.ZOOM);
        existing.setMeetingId("8123456789");
        existing.setJoinUrl("https://zoom.us/j/8123456789");
        existing.setStatus("ACTIVE");

        when(zoom.providerType()).thenReturn(ConferencingProviderType.ZOOM);
        when(resolver.resolve(eq(hostId), any(EventType.class))).thenReturn(ConferencingProviderType.ZOOM);
        when(mappingRepository.findByBookingIdAndProvider(sessionId, ConferencingProviderType.ZOOM))
                .thenReturn(Optional.of(existing));
        when(zoom.updateMeeting(eq(sessionId), eq(hostId), eq("8123456789"), eq("Math Class"), eq(START), eq(END)))
                .thenReturn(new ConferencingProvider.MeetingDetails(
                        "8123456789", "https://zoom.us/j/8123456789", null));

        SessionConferencingCoordinator coordinator = coordinator(resolver, mappingRepository, zoom);
        ConferencingInstruction instruction =
                coordinator.prepare(sessionId, hostId, eventType("Math Class"), START, END);

        assertEquals("8123456789", instruction.meetingId());
        // Guests already hold this link; rescheduling must move the meeting, not mint a new one.
        verify(zoom, never()).createMeeting(any(), any(), any(), any(), any());
        verify(zoom).updateMeeting(sessionId, hostId, "8123456789", "Math Class", START, END);
    }

    @Test
    void prepare_zoomFailure_propagatesSoTheSyncRetriesAndTheEmailStaysWithheld() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        ConferencingProvider zoom = mock(ConferencingProvider.class);

        when(zoom.providerType()).thenReturn(ConferencingProviderType.ZOOM);
        when(resolver.resolve(eq(hostId), any(EventType.class))).thenReturn(ConferencingProviderType.ZOOM);
        when(mappingRepository.findByBookingIdAndProvider(sessionId, ConferencingProviderType.ZOOM))
                .thenReturn(Optional.empty());
        when(zoom.createMeeting(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("zoom conferencing revoked"));

        SessionConferencingCoordinator coordinator = coordinator(resolver, mappingRepository, zoom);

        // Loud, not silent: degrading to "no conferencing" here is what produced a confirmed session
        // with no way to join it.
        assertThrows(IllegalStateException.class,
                () -> coordinator.prepare(sessionId, hostId, eventType("Math Class"), START, END));

        ArgumentCaptor<ConferencingEventMapping> saved = ArgumentCaptor.forClass(ConferencingEventMapping.class);
        verify(mappingRepository).save(saved.capture());
        assertEquals("FAILED", saved.getValue().getStatus());
        assertEquals("zoom conferencing revoked", saved.getValue().getLastError());
    }

    @Test
    void prepare_customUrlSession_embedsTheHostsOwnLink() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        when(resolver.resolve(eq(hostId), any(EventType.class))).thenReturn(ConferencingProviderType.CUSTOM_URL);

        EventType withLink = eventType("Math Class");
        withLink.setCustomConferenceUrl("  https://meet.example.test/math  ");

        ConferencingInstruction instruction = coordinator(resolver, mappingRepository, null)
                .prepare(sessionId, hostId, withLink, START, END);

        assertEquals(ConferencingProviderType.CUSTOM_URL, instruction.providerType());
        assertTrue(instruction.embedsExternalUrl());
        assertEquals("https://meet.example.test/math", instruction.joinUrl());
        // A pasted link is not ours to create or cancel, so nothing is recorded against the provider.
        org.mockito.Mockito.verifyNoInteractions(mappingRepository);
    }

    @Test
    void prepare_googleMeetSession_stillRequestsNativeMeet() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        when(resolver.resolve(eq(hostId), any(EventType.class))).thenReturn(ConferencingProviderType.GOOGLE_MEET);

        ConferencingInstruction instruction = coordinator(resolver, mappingRepository, null)
                .prepare(sessionId, hostId, eventType("Math Class"), START, END);

        assertTrue(instruction.requestsNativeMeet());
        assertEquals(ConferencingProviderType.GOOGLE_MEET, instruction.providerType());
        assertNull(instruction.joinUrl());
    }

    @Test
    void cancelForSession_releasesTheZoomMeeting() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        EventConferencingResolver resolver = mock(EventConferencingResolver.class);
        ConferencingEventMappingRepository mappingRepository = mock(ConferencingEventMappingRepository.class);
        ConferencingProvider zoom = mock(ConferencingProvider.class);
        when(zoom.providerType()).thenReturn(ConferencingProviderType.ZOOM);

        ConferencingEventMapping existing = new ConferencingEventMapping();
        existing.setBookingId(sessionId);
        existing.setProvider(ConferencingProviderType.ZOOM);
        existing.setMeetingId("8123456789");
        existing.setJoinUrl("https://zoom.us/j/8123456789");
        existing.setStatus("ACTIVE");
        when(mappingRepository.findByBookingIdAndProvider(sessionId, ConferencingProviderType.ZOOM))
                .thenReturn(Optional.of(existing));

        coordinator(resolver, mappingRepository, zoom).cancelForSession(sessionId, hostId);

        verify(zoom).cancelMeeting(sessionId, hostId, "8123456789");
        assertEquals("CANCELLED", existing.getStatus());
    }

    private static SessionConferencingCoordinator coordinator(EventConferencingResolver resolver,
                                                              ConferencingEventMappingRepository mappingRepository,
                                                              ConferencingProvider zoom) {
        ConferencingProviderRegistry registry =
                new ConferencingProviderRegistry(zoom == null ? List.of() : List.of(zoom));
        return new SessionConferencingCoordinator(registry, mappingRepository, resolver, txManager());
    }

    private static EventType eventType(String name) {
        return EventType.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name(name)
                .slug("math-class")
                .duration(Duration.ofHours(1))
                .bufferBefore(Duration.ZERO)
                .bufferAfter(Duration.ZERO)
                .slotInterval(Duration.ofHours(1))
                .minNotice(Duration.ZERO)
                .maxAdvance(Duration.ofDays(30))
                .holdDuration(Duration.ofMinutes(15))
                .build();
    }

    private static PlatformTransactionManager txManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
