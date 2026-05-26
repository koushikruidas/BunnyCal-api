package io.bunnycal.sync.domain;

import io.bunnycal.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "sync_reconcile_decision_log")
public class SyncReconcileDecisionLog extends BaseEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "sync_job_id", nullable = false)
    private UUID syncJobId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "external_event_id", length = 255)
    private String externalEventId;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "decision", nullable = false, length = 32)
    private String decision;

    @Column(name = "rationale_code", nullable = false, length = 64)
    private String rationaleCode;

    @Column(name = "rationale_detail", columnDefinition = "TEXT")
    private String rationaleDetail;

    @Column(name = "observed_status", nullable = false, length = 32)
    private String observedStatus;

    @Column(name = "observed_error_code", length = 64)
    private String observedErrorCode;

    @Column(name = "sync_job_status", nullable = false, length = 16)
    private String syncJobStatus;

    @Column(name = "desired_action", nullable = false, length = 16)
    private String desiredAction;

    @Column(name = "projection_version")
    private Long projectionVersion;

    @Column(name = "terminal_intent_epoch")
    private Long terminalIntentEpoch;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "causation_id", length = 128)
    private String causationId;

    public UUID getId() { return id; }
    public UUID getSyncJobId() { return syncJobId; }
    public void setSyncJobId(UUID syncJobId) { this.syncJobId = syncJobId; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String inputHash) { this.inputHash = inputHash; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getRationaleCode() { return rationaleCode; }
    public void setRationaleCode(String rationaleCode) { this.rationaleCode = rationaleCode; }
    public String getRationaleDetail() { return rationaleDetail; }
    public void setRationaleDetail(String rationaleDetail) { this.rationaleDetail = rationaleDetail; }
    public String getObservedStatus() { return observedStatus; }
    public void setObservedStatus(String observedStatus) { this.observedStatus = observedStatus; }
    public String getObservedErrorCode() { return observedErrorCode; }
    public void setObservedErrorCode(String observedErrorCode) { this.observedErrorCode = observedErrorCode; }
    public String getSyncJobStatus() { return syncJobStatus; }
    public void setSyncJobStatus(String syncJobStatus) { this.syncJobStatus = syncJobStatus; }
    public String getDesiredAction() { return desiredAction; }
    public void setDesiredAction(String desiredAction) { this.desiredAction = desiredAction; }
    public Long getProjectionVersion() { return projectionVersion; }
    public void setProjectionVersion(Long projectionVersion) { this.projectionVersion = projectionVersion; }
    public Long getTerminalIntentEpoch() { return terminalIntentEpoch; }
    public void setTerminalIntentEpoch(Long terminalIntentEpoch) { this.terminalIntentEpoch = terminalIntentEpoch; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }
}
