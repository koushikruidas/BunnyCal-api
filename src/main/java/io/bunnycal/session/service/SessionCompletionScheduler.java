package io.bunnycal.session.service;

import io.bunnycal.common.time.TimeSource;
import io.bunnycal.session.domain.EventSession;
import io.bunnycal.session.repository.EventSessionRepository;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transitions finished sessions to {@code COMPLETED}.
 *
 * <p>Nothing previously assigned that status, which left it unreachable and made the
 * terminal-state guards in reschedule and cancel effectively dead code — a session from
 * last year still looked reschedulable. It is also the precondition for anything that
 * needs to know a session actually ran: attendance, recordings, follow-ups, analytics.
 *
 * <p>Work is capped per tick and driven by a CAS that re-checks {@code end_time}, so a
 * large backlog drains across several runs and a concurrent host action cannot be
 * overwritten.
 */
@Component
public class SessionCompletionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletionScheduler.class);

    private final EventSessionRepository sessionRepository;
    private final TimeSource timeSource;
    private final int batchSize;

    public SessionCompletionScheduler(EventSessionRepository sessionRepository,
                                      TimeSource timeSource,
                                      @Value("${session.completion.batch-size:200}") int batchSize) {
        this.sessionRepository = sessionRepository;
        this.timeSource = timeSource;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${session.completion.poll-ms:900000}")
    @SchedulerLock(name = "session_completion_sweep", lockAtMostFor = "PT10M")
    @Transactional
    public void completeFinishedSessions() {
        List<EventSession> due = sessionRepository.findSessionsDueForCompletion(
                timeSource.now(), batchSize);
        if (due.isEmpty()) {
            return;
        }
        int completed = 0;
        for (EventSession session : due) {
            // The CAS re-checks status and end_time: a session cancelled between the
            // read and this update stays cancelled.
            if (sessionRepository.completeSession(session.getId(), timeSource.now()) > 0) {
                completed++;
            }
        }
        log.info("session_completion_sweep candidates={} completed={}", due.size(), completed);
    }
}
