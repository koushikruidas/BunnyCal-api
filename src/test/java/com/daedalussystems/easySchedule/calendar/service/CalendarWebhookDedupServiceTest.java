package com.daedalussystems.easySchedule.calendar.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.daedalussystems.easySchedule.calendar.repository.CalendarWebhookEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarWebhookDedupServiceTest {

    @Mock private CalendarWebhookEventRepository repository;
    private CalendarWebhookDedupService service;

    @BeforeEach
    void setUp() {
        service = new CalendarWebhookDedupService(repository, new SimpleMeterRegistry());
    }

    @Test
    void firstSeen_trueWhenInsertSucceeds() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(service.firstSeen("google", UUID.randomUUID(), "evt-1", "{\"id\":\"evt-1\"}"));
    }

    @Test
    void firstSeen_falseWhenDuplicate() {
        when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(service.firstSeen("google", UUID.randomUUID(), "evt-1", "{\"id\":\"evt-1\"}"));
    }
}
