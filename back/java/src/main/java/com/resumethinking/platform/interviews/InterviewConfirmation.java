package com.resumethinking.platform.interviews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "interview_confirmations")
public class InterviewConfirmation {
    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "session_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String sessionId;
    @Column(name = "feedback_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String feedbackId;
    @Column(name = "claim_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String claimId;
    @Column(nullable = false, length = 16)
    private String decision;
    @Column(name = "actor_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String actorId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InterviewConfirmation() {
    }

    public InterviewConfirmation(String id, String sessionId, String feedbackId, String claimId,
                                 String decision, String actorId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.feedbackId = Objects.requireNonNull(feedbackId);
        this.claimId = Objects.requireNonNull(claimId);
        this.decision = Objects.requireNonNull(decision);
        this.actorId = Objects.requireNonNull(actorId);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getFeedbackId() { return feedbackId; }
    public String getClaimId() { return claimId; }
    public String getDecision() { return decision; }
    public String getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}
