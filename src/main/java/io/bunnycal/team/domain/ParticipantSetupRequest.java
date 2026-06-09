package io.bunnycal.team.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
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
@Table(
        name = "participant_setup_requests",
        indexes = {
            @Index(name = "idx_psr_target_user",  columnList = "target_user_id"),
            @Index(name = "idx_psr_team_member",  columnList = "team_member_id")
        })
public class ParticipantSetupRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id",  nullable = false)
    private UUID ownerUserId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "team_id",        nullable = false)
    private UUID teamId;

    @Column(name = "team_member_id", nullable = false)
    private UUID teamMemberId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "last_reminded_at")
    private Instant lastRemindedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
