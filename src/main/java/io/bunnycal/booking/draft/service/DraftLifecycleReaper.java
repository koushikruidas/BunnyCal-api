package io.bunnycal.booking.draft.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DraftLifecycleReaper {
    private final DraftOrganizerService draftOrganizerService;
    private final boolean enabled;

    public DraftLifecycleReaper(DraftOrganizerService draftOrganizerService,
                                @Value("${draft.reaper.enabled:false}") boolean enabled) {
        this.draftOrganizerService = draftOrganizerService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${draft.reaper.interval-ms:60000}")
    public void reap() {
        if (!enabled) {
            return;
        }
        int touched = draftOrganizerService.runReaper();
        if (touched > 0) {
            log.info("draft_reaper_touched count={}", touched);
        }
    }
}
