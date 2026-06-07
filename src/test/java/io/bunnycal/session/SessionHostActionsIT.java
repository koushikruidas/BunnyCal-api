package io.bunnycal.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.bunnycal.session.service.JoinSessionResult;
import io.bunnycal.session.service.SessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SessionHostActionsIT extends AbstractSessionIT {

    @Autowired private WebApplicationContext context;
    @Autowired private SessionService sessionService;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void initMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void cancelSession_endpointCancelsSessionAndCreatesDeleteSyncJob() throws Exception {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 3);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 3,
                "a@test.com", "A", Duration.ofMinutes(15));
        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 3,
                "b@test.com", "B", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());

        mockMvc.perform(post("/api/sessions/{sessionId}/cancel", joinA.sessionId())
                        .principal(new UsernamePasswordAuthenticationToken(host.getId(), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(joinA.sessionId().toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.confirmedCount").value(0));

        Map<String, Object> sessionRow = querySession(joinA.sessionId());
        assertThat(sessionRow.get("status")).isEqualTo("CANCELLED");
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isZero();
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CANCELLED")).isEqualTo(2);
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CONFIRMED")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Session' AND aggregate_id = ? AND event_type = 'SESSION_CANCELLED'",
                Integer.class, joinA.sessionId())).isEqualTo(1);
    }

    @Test
    void removeAttendee_endpointCancelsRegistrationAndUpdatesSyncJob() throws Exception {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 3);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult joinA = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 3,
                "a@test.com", "A", Duration.ofMinutes(15));
        JoinSessionResult joinB = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 3,
                "b@test.com", "B", Duration.ofMinutes(15));
        sessionService.confirmRegistration(joinA.sessionId(), joinA.registrationId(), host.getId());
        sessionService.confirmRegistration(joinB.sessionId(), joinB.registrationId(), host.getId());

        mockMvc.perform(delete("/api/sessions/{sessionId}/registrations/{registrationId}",
                        joinA.sessionId(), joinA.registrationId())
                        .principal(new UsernamePasswordAuthenticationToken(host.getId(), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.confirmedCount").value(1));

        Map<String, Object> sessionRow = querySession(joinA.sessionId());
        assertThat(sessionRow.get("status")).isEqualTo("OPEN");
        assertThat(((Number) sessionRow.get("confirmed_count")).intValue()).isEqualTo(1);
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CANCELLED")).isEqualTo(1);
        assertThat(countRegistrationsByStatus(joinA.sessionId(), "CONFIRMED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Session' AND aggregate_id = ? AND event_type = 'REGISTRATION_CANCELLED'",
                Integer.class, joinA.sessionId())).isEqualTo(1);
    }

    @Test
    void hostManagementEndpoints_rejectForeignHost() throws Exception {
        var host = createHost();
        var otherHost = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 2,
                "guest@test.com", "Guest", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        mockMvc.perform(post("/api/sessions/{sessionId}/cancel", join.sessionId())
                        .principal(new UsernamePasswordAuthenticationToken(otherHost.getId(), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/sessions/{sessionId}/registrations/{registrationId}",
                        join.sessionId(), join.registrationId())
                        .principal(new UsernamePasswordAuthenticationToken(otherHost.getId(), null))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelSession_isIdempotentAcrossRepeatedRequests() throws Exception {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 2,
                "guest@test.com", "Guest", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        UsernamePasswordAuthenticationToken principal =
                new UsernamePasswordAuthenticationToken(host.getId(), null);

        mockMvc.perform(post("/api/sessions/{sessionId}/cancel", join.sessionId())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/sessions/{sessionId}/cancel", join.sessionId())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(((Number) querySession(join.sessionId()).get("confirmed_count")).intValue()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Session' AND aggregate_id = ? AND event_type = 'SESSION_CANCELLED'",
                Integer.class, join.sessionId())).isEqualTo(1);
    }

    @Test
    void removeAttendee_isIdempotentAcrossRepeatedRequests() throws Exception {
        var host = createHost();
        var eventType = createGroupEventType(host.getId(), 2);
        Instant start = nextHour();
        Instant end = start.plus(Duration.ofHours(1));

        JoinSessionResult join = sessionService.joinSession(
                host.getId(), eventType.getId(), start, end, 2,
                "guest@test.com", "Guest", Duration.ofMinutes(15));
        sessionService.confirmRegistration(join.sessionId(), join.registrationId(), host.getId());

        UsernamePasswordAuthenticationToken principal =
                new UsernamePasswordAuthenticationToken(host.getId(), null);

        mockMvc.perform(delete("/api/sessions/{sessionId}/registrations/{registrationId}",
                        join.sessionId(), join.registrationId())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/sessions/{sessionId}/registrations/{registrationId}",
                        join.sessionId(), join.registrationId())
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(countRegistrationsByStatus(join.sessionId(), "CANCELLED")).isEqualTo(1);
        assertThat(((Number) querySession(join.sessionId()).get("confirmed_count")).intValue()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Session' AND aggregate_id = ? AND event_type = 'REGISTRATION_CANCELLED'",
                Integer.class, join.sessionId())).isEqualTo(1);
    }
}
