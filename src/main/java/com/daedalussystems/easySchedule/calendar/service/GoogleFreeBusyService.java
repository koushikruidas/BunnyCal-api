package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.auth.TokenRefresher;
import com.daedalussystems.easySchedule.calendar.client.CalendarClientException;
import com.daedalussystems.easySchedule.calendar.client.GoogleApiClient;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GoogleFreeBusyService {
    private final CalendarConnectionRepository connectionRepository;
    private final TokenRefresher tokenRefresher;
    private final GoogleApiClient googleApiClient;

    public GoogleFreeBusyService(CalendarConnectionRepository connectionRepository,
                                 TokenRefresher tokenRefresher,
                                 GoogleApiClient googleApiClient) {
        this.connectionRepository = connectionRepository;
        this.tokenRefresher = tokenRefresher;
        this.googleApiClient = googleApiClient;
    }

    public List<BusyInterval> busyIntervals(UUID userId, Instant start, Instant end) {
        List<CalendarConnection> active = connectionRepository.findByUserIdAndStatus(userId, CalendarConnectionStatus.ACTIVE);
        if (active.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> all = new ArrayList<>();
        for (CalendarConnection connection : active) {
            try {
                List<GoogleApiClient.BusyInterval> intervals = tokenRefresher.executeWithValidToken(
                        connection.getId(),
                        token -> googleApiClient.fetchBusyIntervals(token, start, end)
                );
                for (GoogleApiClient.BusyInterval interval : intervals) {
                    if (interval.start().isBefore(interval.end())) {
                        all.add(new BusyInterval(interval.start(), interval.end()));
                    }
                }
            } catch (CalendarClientException ex) {
                // Keep availability path read-only and degrade gracefully upstream.
                // Connection state updates are handled in TokenRefresher's own transaction.
                throw ex;
            }
        }
        return merge(all);
    }

    private static List<BusyInterval> merge(List<BusyInterval> intervals) {
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<BusyInterval> sorted = intervals.stream()
                .sorted(Comparator.comparing(BusyInterval::start).thenComparing(BusyInterval::end))
                .toList();
        List<BusyInterval> out = new ArrayList<>();
        BusyInterval cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            BusyInterval next = sorted.get(i);
            if (!next.start().isAfter(cur.end())) {
                Instant maxEnd = next.end().isAfter(cur.end()) ? next.end() : cur.end();
                cur = new BusyInterval(cur.start(), maxEnd);
            } else {
                out.add(cur);
                cur = next;
            }
        }
        out.add(cur);
        return List.copyOf(out);
    }

    public record BusyInterval(Instant start, Instant end) {}
}
