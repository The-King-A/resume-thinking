package com.resumethinking.platform.interviews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "interview_feedback")
public class InterviewFeedback {
    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "session_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String sessionId;
    @Column(name = "answer_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String answerId;
    @Column(name = "feedback_payload_json", nullable = false, columnDefinition = "JSON")
    private String payloadJson;
    @Column(name = "feedback_version", nullable = false)
    private long feedbackVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InterviewFeedback() {
    }

    public InterviewFeedback(String id, String sessionId, String answerId, String payloadJson,
                             long feedbackVersion, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.answerId = Objects.requireNonNull(answerId);
        this.payloadJson = Objects.requireNonNull(payloadJson);
        this.feedbackVersion = feedbackVersion;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getAnswerId() { return answerId; }
    public String getPayloadJson() { return payloadJson; }
    public long getFeedbackVersion() { return feedbackVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
