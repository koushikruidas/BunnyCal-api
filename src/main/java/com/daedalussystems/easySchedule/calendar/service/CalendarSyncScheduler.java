package com.daedalussystems.easySchedule.calendar.service;

import com.daedalussystems.easySchedule.calendar.domain.CalendarConnection;
import com.daedalussystems.easySchedule.calendar.domain.CalendarConnectionStatus;
import com.daedalussystems.easySchedule.calendar.repository.CalendarConnectionRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CalendarSyncScheduler {
    private final CalendarConnectionRepository connectionRepository;
    private final CalendarEventIngestionService ingestionService;
    private final ExternalCalendarSyncClient syncClient;

    public CalendarSyncScheduler(CalendarConnectionRepository connectionRepository,
                                 CalendarEventIngestionService ingestionService,
                                 ExternalCalendarSyncClient syncClient) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.syncClient = syncClient;
    }

    @Scheduled(fixedDelayString = "${calendar.sync.fixed-delay-ms:30000}")
    @Transactional
    public void sync() {
        List<CalendarConnection> active = connectionRepository.findByStatus(CalendarConnectionStatus.ACTIVE);
        for (CalendarConnection connection : active) {
            try {
                ingestionService.upsertEvents(connection.getId(), syncClient.fetchIncremental(connection));
            } catch (ExternalCalendarSyncClient.SyncTokenInvalidException invalid) {
                ingestionService.upsertEvents(connection.getId(), syncClient.fetchFull(connection));
            } catch (RuntimeException ex) {
                connection.setLastErrorCode("SYNC_FAILED");
                connection.setLastErrorAt(Instant.now());
                connectionRepository.save(connection);
            }
        }
    }
}
