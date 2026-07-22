package io.bunnycal.session.service;

import io.bunnycal.session.repository.SessionRegistrationRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import io.bunnycal.hostpayments.service.HostPaymentLifecycleService;

@Component
public class RegistrationExpiryScheduler {

    private final SessionRegistrationRepository registrationRepository;
    private final SessionService sessionService;
    private final ObjectProvider<HostPaymentLifecycleService> hostPaymentLifecycleService;

    public RegistrationExpiryScheduler(SessionRegistrationRepository registrationRepository,
                                        SessionService sessionService,
                                        ObjectProvider<HostPaymentLifecycleService> hostPaymentLifecycleService) {
        this.registrationRepository = registrationRepository;
        this.sessionService = sessionService;
        this.hostPaymentLifecycleService = hostPaymentLifecycleService;
    }

    @Scheduled(fixedDelayString = "${session.registration.expiry.fixed-delay-ms:15000}")
    @Transactional
    public void expireOverdueHolds() {
        var overdue = registrationRepository.findPendingExpired(Instant.now(), 200);
        for (var row : overdue) {
            sessionService.expireRegistration(row.getId(), row.getVersion());
            HostPaymentLifecycleService payments = hostPaymentLifecycleService.getIfAvailable();
            if (payments != null) payments.markReservationExpired(row.getId());
        }
    }
}
