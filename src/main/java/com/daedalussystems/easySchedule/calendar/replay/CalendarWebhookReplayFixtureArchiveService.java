package com.daedalussystems.easySchedule.calendar.replay;

import com.daedalussystems.easySchedule.calendar.repository.CalendarWebhookReplayFixtureRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CalendarWebhookReplayFixtureArchiveService {

    private final CalendarWebhookReplayFixtureRepository repository;
    private final ReplayPayloadRedactor payloadRedactor;
    private final ObjectMapper mapper;

    public CalendarWebhookReplayFixtureArchiveService(CalendarWebhookReplayFixtureRepository repository,
                                                      ReplayPayloadRedactor payloadRedactor) {
        this.repository = repository;
        this.payloadRedactor = payloadRedactor;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public List<WebhookReplayFixture> loadByConnection(UUID connectionId) {
        return repository.findByConnectionIdOrderByArrivalIndexAsc(connectionId).stream()
                .map(row -> new WebhookReplayFixture(
                        row.getArrivalIndex() == null ? 0L : row.getArrivalIndex(),
                        row.getDeliveryId(),
                        row.getProviderEventId(),
                        row.getDedupResult(),
                        row.getPayloadHash(),
                        row.getProviderUpdatedAt(),
                        row.getProviderEtag(),
                        row.getProviderSequence(),
                        row.isRecurringHint(),
                        row.getSourceAttribution(),
                        row.getRawPayload()))
                .toList();
    }

    public List<WebhookReplayFixture> loadByProviderWindow(String provider, Instant fromInclusive, Instant toExclusive) {
        return repository.findByProviderAndCapturedAtBetweenOrderByArrivalIndexAsc(provider, fromInclusive, toExclusive).stream()
                .map(row -> new WebhookReplayFixture(
                        row.getArrivalIndex() == null ? 0L : row.getArrivalIndex(),
                        row.getDeliveryId(),
                        row.getProviderEventId(),
                        row.getDedupResult(),
                        row.getPayloadHash(),
                        row.getProviderUpdatedAt(),
                        row.getProviderEtag(),
                        row.getProviderSequence(),
                        row.isRecurringHint(),
                        row.getSourceAttribution(),
                        row.getRawPayload()))
                .toList();
    }

    public String archiveAsJson(List<WebhookReplayFixture> fixtures) {
        try {
            List<WebhookReplayFixture> redacted = fixtures.stream()
                    .map(f -> new WebhookReplayFixture(
                            f.arrivalIndex(),
                            f.deliveryId(),
                            f.providerEventId(),
                            f.dedupResult(),
                            f.payloadHash(),
                            f.providerUpdatedAt(),
                            f.providerEtag(),
                            f.providerSequence(),
                            f.recurringHint(),
                            f.sourceAttribution(),
                            payloadRedactor.redact(f.rawPayload())))
                    .toList();
            return mapper.writeValueAsString(redacted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to archive webhook replay fixtures", e);
        }
    }
}
