package com.resumethinking.platform.interviews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "interview_questions")
public class InterviewQuestion {
    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "session_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String sessionId;
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;
    @Column(name = "question_type", nullable = false, length = 32)
    private String questionType;
    @Column(nullable = false, length = 16)
    private String difficulty;
    @Column(name = "question_text", nullable = false, length = 2000)
    private String questionText;
    @Column(name = "requirement_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String requirementId;
    @Column(name = "requirement_text", nullable = false, length = 5000)
    private String requirementText;
    @Column(name = "evidence_ids_json", nullable = false, columnDefinition = "JSON")
    private String evidenceIdsJson;
    @Column(name = "generation_reason", nullable = false, length = 2000)
    private String generationReason;
    @Column(nullable = false)
    private double confidence;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InterviewQuestion() {
    }

    public InterviewQuestion(String id, String sessionId, int sequenceNo, String questionType,
                             String difficulty, String questionText, String requirementId,
                             String requirementText, String evidenceIdsJson, String generationReason,
                             double confidence, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.sequenceNo = sequenceNo;
        this.questionType = Objects.requireNonNull(questionType);
        this.difficulty = Objects.requireNonNull(difficulty);
        this.questionText = Objects.requireNonNull(questionText);
        this.requirementId = Objects.requireNonNull(requirementId);
        this.requirementText = Objects.requireNonNull(requirementText);
        this.evidenceIdsJson = Objects.requireNonNull(evidenceIdsJson);
        this.generationReason = Objects.requireNonNull(generationReason);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("question confidence must be between 0 and 1");
        }
        this.confidence = confidence;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public int getSequenceNo() { return sequenceNo; }
    public String getQuestionType() { return questionType; }
    public String getDifficulty() { return difficulty; }
    public String getQuestionText() { return questionText; }
    public String getRequirementId() { return requirementId; }
    public String getRequirementText() { return requirementText; }
    public String getEvidenceIdsJson() { return evidenceIdsJson; }
    public String getGenerationReason() { return generationReason; }
    public double getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
