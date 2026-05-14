package com.daedalussystems.easySchedule.sync.domain;

import com.daedalussystems.easySchedule.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "sync_reconcile_input_snapshots")
public class SyncReconcileInputSnapshot extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "snapshot_version", insertable = false, updatable = false)
    private Long snapshotVersion;

    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;

    @Column(name = "sync_job_id", nullable = false)
    private UUID syncJobId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "external_event_id", length = 255)
    private String externalEventId;

    @Column(name = "booking_state", nullable = false, length = 16)
    private String bookingState;

    @Column(name = "sync_status", nullable = false, length = 16)
    private String syncStatus;

    @Column(name = "projection_lifecycle", nullable = false, length = 24)
    private String projectionLifecycle;

    @Column(name = "participation_lifecycle", nullable = false, length = 24)
    private String participationLifecycle;

    @Column(name = "invariant_classification", nullable = false, length = 32)
    private String invariantClassification;

    @Column(name = "desired_action", nullable = false, length = 16)
    private String desiredAction;

    @Column(name = "observed_status", nullable = false, length = 32)
    private String observedStatus;

    @Column(name = "observed_error_code", length = 64)
    private String observedErrorCode;

    @Column(name = "projection_version")
    private Long projectionVersion;

    @Column(name = "terminal_intent_epoch")
    private Long terminalIntentEpoch;

    @Column(name = "projection_connection_id")
    private UUID projectionConnectionId;

    @Column(name = "provider_updated_at")
    private Instant providerUpdatedAt;

    @Column(name = "provider_etag", length = 255)
    private String providerEtag;

    @Column(name = "provider_sequence")
    private Long providerSequence;

    @Column(name = "recurring_hint", nullable = false)
    private boolean recurringHint;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "causation_id", length = 128)
    private String causationId;

    @Column(name = "lineage_source", nullable = false, length = 64)
    private String lineageSource;

    public UUID getId() { return id; }
    public Long getSnapshotVersion() { return snapshotVersion; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    public UUID getSyncJobId() { return syncJobId; }
    public void setSyncJobId(UUID syncJobId) { this.syncJobId = syncJobId; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getBookingState() { return bookingState; }
    public void setBookingState(String bookingState) { this.bookingState = bookingState; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    public String getProjectionLifecycle() { return projectionLifecycle; }
    public void setProjectionLifecycle(String projectionLifecycle) { this.projectionLifecycle = projectionLifecycle; }
    public String getParticipationLifecycle() { return participationLifecycle; }
    public void setParticipationLifecycle(String participationLifecycle) { this.participationLifecycle = participationLifecycle; }
    public String getInvariantClassification() { return invariantClassification; }
    public void setInvariantClassification(String invariantClassification) { this.invariantClassification = invariantClassification; }
    public String getDesiredAction() { return desiredAction; }
    public void setDesiredAction(String desiredAction) { this.desiredAction = desiredAction; }
    public String getObservedStatus() { return observedStatus; }
    public void setObservedStatus(String observedStatus) { this.observedStatus = observedStatus; }
    public String getObservedErrorCode() { return observedErrorCode; }
    public void setObservedErrorCode(String observedErrorCode) { this.observedErrorCode = observedErrorCode; }
    public Long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(Long projectionVersion) { this.projectionVersion = projectionVersion; }
    public Long getTerminalIntentEpoch() { return terminalIntentEpoch; }
    public void setTerminalIntentEpoch(Long terminalIntentEpoch) { this.terminalIntentEpoch = terminalIntentEpoch; }
    public UUID getProjectionConnectionId() { return projectionConnectionId; }
    public void setProjectionConnectionId(UUID projectionConnectionId) { this.projectionConnectionId = projectionConnectionId; }
    public Instant getProviderUpdatedAt() { return providerUpdatedAt; }
    public void setProviderUpdatedAt(Instant providerUpdatedAt) { this.providerUpdatedAt = providerUpdatedAt; }
    public String getProviderEtag() { return providerEtag; }
    public void setProviderEtag(String providerEtag) { this.providerEtag = providerEtag; }
    public Long getProviderSequence() { return providerSequence; }
    public void setProviderSequence(Long providerSequence) { this.providerSequence = providerSequence; }
    public boolean isRecurringHint() { return recurringHint; }
    public void setRecurringHint(boolean recurringHint) { this.recurringHint = recurringHint; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }
    public String getLineageSource() { return lineageSource; }
    public void setLineageSource(String lineageSource) { this.lineageSource = lineageSource; }
}
