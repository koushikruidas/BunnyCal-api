package io.bunnycal.session.service;

import io.bunnycal.session.repository.SessionRegistrationRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RegistrationExpiryScheduler {

    private final SessionRegistrationRepository registrationRepository;
    private final SessionService sessionService;

    public RegistrationExpiryScheduler(SessionRegistrationRepository registrationRepository,
                                        SessionService sessionService) {
        this.registrationRepository = registrationRepository;
        this.sessionService = sessionService;
    }

    @Scheduled(fixedDelayString = "${session.registration.expiry.fixed-delay-ms:15000}")
    @Transactional
    public void expireOverdueHolds() {
        var overdue = registrationRepository.findPendingExpired(Instant.now(), 200);
        for (var row : overdue) {
            sessionService.expireRegistration(row.getId(), row.getVersion());
        }
    }
}
