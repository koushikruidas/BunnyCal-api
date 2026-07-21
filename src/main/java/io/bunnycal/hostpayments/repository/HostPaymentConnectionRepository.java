package io.bunnycal.hostpayments.repository;

import io.bunnycal.hostpayments.domain.HostPaymentConnection;
import io.bunnycal.hostpayments.domain.PaymentProviderType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HostPaymentConnectionRepository extends JpaRepository<HostPaymentConnection, UUID> {
    List<HostPaymentConnection> findByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<HostPaymentConnection> findByUserIdAndProvider(UUID userId, PaymentProviderType provider);
    Optional<HostPaymentConnection> findByProviderAndProviderAccountId(PaymentProviderType provider, String accountId);
}
