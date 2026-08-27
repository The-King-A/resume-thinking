package com.resumethinking.platform.resumes;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resume_recovery_audit")
public class ResumeAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="resume_id", nullable=false, columnDefinition="BINARY(16)") private UUID resumeId;
    @Column(name="actor_id", columnDefinition="BINARY(16)") private UUID actorId;
    @Column(nullable=false, length=32) private String action;
    @Enumerated(EnumType.STRING) @Column(name="prior_visibility_state", nullable=false, length=32) private VisibilityState priorVisibilityState;
    @Enumerated(EnumType.STRING) @Column(name="new_visibility_state", nullable=false, length=32) private VisibilityState newVisibilityState;
    @Column(name="occurred_at", nullable=false) private Instant occurredAt;
    @Column(name="correlation_id", nullable=false, columnDefinition="BINARY(16)") private UUID correlationId;

    protected ResumeAudit() {}
    public ResumeAudit(ResumeAuditRepository.ResumeLifecycleAudit audit) {
        this.resumeId=audit.resumeId(); this.actorId=audit.actorId(); this.action=audit.action();
        this.priorVisibilityState=audit.priorVisibilityState(); this.newVisibilityState=audit.newVisibilityState();
        this.occurredAt=audit.occurredAt(); this.correlationId=audit.correlationId();
    }
    public UUID getResumeId(){return resumeId;} public UUID getActorId(){return actorId;} public String getAction(){return action;}
    public VisibilityState getPriorVisibilityState(){return priorVisibilityState;} public VisibilityState getNewVisibilityState(){return newVisibilityState;}
    public Instant getOccurredAt(){return occurredAt;} public UUID getCorrelationId(){return correlationId;}
}
