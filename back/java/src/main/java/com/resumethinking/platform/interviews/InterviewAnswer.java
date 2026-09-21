package com.resumethinking.platform.interviews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

@Entity
@Table(name = "interview_answers")
public class InterviewAnswer {
    public enum State { ANALYZING, FEEDBACK_READY, CLEARED }

    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "session_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String sessionId;
    @Column(name = "question_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String questionId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "answer_fingerprint", nullable = false, length = 64)
    private String fingerprint;
    @Column(name = "answer_ciphertext", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] ciphertext;
    @Column(name = "answer_nonce", nullable = false, columnDefinition = "VARBINARY(12)")
    private byte[] nonce;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State state;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "cleared_at")
    private Instant clearedAt;

    protected InterviewAnswer() {
    }

    public InterviewAnswer(String id, String sessionId, String questionId, String idempotencyKey,
                           String fingerprint, byte[] ciphertext, byte[] nonce, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.questionId = Objects.requireNonNull(questionId);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.ciphertext = copy(ciphertext);
        this.nonce = copy(nonce);
        if (this.nonce.length != 12) throw new IllegalArgumentException("answer nonce must be 12 bytes");
        this.state = State.ANALYZING;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public void markFeedbackReady() { if (state == State.ANALYZING) state = State.FEEDBACK_READY; }

    public void clear(Instant at) {
        state = State.CLEARED;
        ciphertext = new byte[0];
        nonce = new byte[12];
        clearedAt = Objects.requireNonNull(at);
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getQuestionId() { return questionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getFingerprint() { return fingerprint; }
    public byte[] getCiphertext() { return copy(ciphertext); }
    public byte[] getNonce() { return copy(nonce); }
    public State getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClearedAt() { return clearedAt; }

    private static byte[] copy(byte[] value) {
        return Arrays.copyOf(Objects.requireNonNull(value), value.length);
    }
}
