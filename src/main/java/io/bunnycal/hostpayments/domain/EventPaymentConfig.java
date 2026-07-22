package io.bunnycal.hostpayments.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "event_payment_configs")
public class EventPaymentConfig extends BaseEntity {
    @Id
    @Column(name = "event_type_id")
    private UUID eventTypeId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;
}
