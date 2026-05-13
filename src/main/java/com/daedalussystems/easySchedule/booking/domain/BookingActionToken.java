package com.daedalussystems.easySchedule.booking.domain;

import com.daedalussystems.easySchedule.booking.service.BookingActionType;
import com.daedalussystems.easySchedule.booking.service.TokenCreatorType;
import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "booking_action_tokens")
public class BookingActionToken extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "booking_host_id", nullable = false)
    private UUID bookingHostId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private BookingActionType actionType;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by", nullable = false, length = 32)
    private TokenCreatorType createdBy;
}
