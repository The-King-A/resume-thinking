package com.resumethinking.platform.resumes;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resume_recovery_audit")
public class ResumeAudit {
    @Id @Column(nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name="resume_id", nullable=false, length=64, columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String resumeId;
    @Column(name="actor_id", length=64, columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String actorId;
    @Column(nullable=false, length=32) private String action;
    @Enumerated(EnumType.STRING) @Column(name="prior_visibility_state", nullable=false, length=32) private VisibilityState priorVisibilityState;
    @Enumerated(EnumType.STRING) @Column(name="new_visibility_state", nullable=false, length=32) private VisibilityState newVisibilityState;
    @Column(name="occurred_at", nullable=false) private Instant occurredAt;
    @Column(name="correlation_id", nullable=false, columnDefinition="BINARY(16)") private UUID correlationId;

    protected ResumeAudit() {}
    public ResumeAudit(String id, ResumeAuditRepository.ResumeLifecycleAudit audit) {
        this.id=id;
        this.resumeId=audit.resumeId(); this.actorId=audit.actorId(); this.action=audit.action();
        this.priorVisibilityState=audit.priorVisibilityState(); this.newVisibilityState=audit.newVisibilityState();
        this.occurredAt=audit.occurredAt(); this.correlationId=audit.correlationId();
    }
    public String getResumeId(){return resumeId;} public String getActorId(){return actorId;} public String getAction(){return action;}
    public VisibilityState getPriorVisibilityState(){return priorVisibilityState;} public VisibilityState getNewVisibilityState(){return newVisibilityState;}
    public Instant getOccurredAt(){return occurredAt;} public UUID getCorrelationId(){return correlationId;}
}
