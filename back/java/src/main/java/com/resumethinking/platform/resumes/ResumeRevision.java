package com.resumethinking.platform.resumes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

@Entity
@Table(name = "resume_revisions", uniqueConstraints = @UniqueConstraint(
        name = "uq_resume_revisions_resume_revision_no",
        columnNames = {"resume_id", "revision_no"}))
public class ResumeRevision {
    public enum State { PENDING, EFFECTIVE, FAILED, SUPERSEDED }

    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "resume_id", nullable = false, updatable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String resumeId;
    @Column(name = "revision_no", nullable = false, updatable = false)
    private long revisionNo;
    @Column(nullable = false, updatable = false, length = 200)
    private String title;
    @Column(name = "title_key", nullable = false, updatable = false, length = 200)
    private String titleKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 8)
    private Resume.SourceType sourceType;
    @Column(name = "parser_version", nullable = false, updatable = false, length = 64)
    private String parserVersion;
    @Column(name = "raw_content_ciphertext", nullable = false, updatable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] ciphertext;
    @Column(name = "raw_content_nonce", updatable = false, columnDefinition = "VARBINARY(12)")
    private byte[] nonce;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ResumeRevision() {
    }

    public ResumeRevision(String id, String resumeId, long revisionNo, String title, String titleKey,
                          Resume.SourceType sourceType, String parserVersion, byte[] ciphertext,
                          byte[] nonce, State state) {
        this(id, resumeId, revisionNo, title, titleKey, sourceType, parserVersion,
                ciphertext, nonce, state, Instant.now());
    }

    public ResumeRevision(String id, String resumeId, long revisionNo, String title, String titleKey,
                          Resume.SourceType sourceType, String parserVersion, byte[] ciphertext,
                          byte[] nonce, State state, Instant createdAt) {
        if (revisionNo < 1) {
            throw new IllegalArgumentException("revision number must be at least 1");
        }
        this.id = Objects.requireNonNull(id, "revision id must not be null");
        this.resumeId = Objects.requireNonNull(resumeId, "resume id must not be null");
        this.revisionNo = revisionNo;
        this.title = Objects.requireNonNull(title, "revision title must not be null");
        this.titleKey = Objects.requireNonNull(titleKey, "revision title key must not be null");
        this.sourceType = Objects.requireNonNull(sourceType, "revision source type must not be null");
        this.parserVersion = Objects.requireNonNull(parserVersion, "parser version must not be null");
        this.ciphertext = copy(ciphertext);
        this.nonce = copyNullable(nonce);
        this.state = Objects.requireNonNull(state, "revision state must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "revision creation time must not be null");
    }

    public void markEffective() {
        state = State.EFFECTIVE;
    }

    public void markFailed() {
        if (state == State.PENDING) {
            state = State.FAILED;
        }
    }

    public void markSuperseded() {
        if (state != State.EFFECTIVE) {
            state = State.SUPERSEDED;
        }
    }

    public String getId() { return id; }
    public String id() { return id; }
    public String getResumeId() { return resumeId; }
    public String resumeId() { return resumeId; }
    public long getRevisionNo() { return revisionNo; }
    public long revisionNo() { return revisionNo; }
    public String getTitle() { return title; }
    public String title() { return title; }
    public String getTitleKey() { return titleKey; }
    public String titleKey() { return titleKey; }
    public Resume.SourceType getSourceType() { return sourceType; }
    public Resume.SourceType sourceType() { return sourceType; }
    public String getParserVersion() { return parserVersion; }
    public String parserVersion() { return parserVersion; }
    public byte[] getCiphertext() { return copy(ciphertext); }
    public byte[] ciphertext() { return getCiphertext(); }
    public byte[] getNonce() { return copyNullable(nonce); }
    public byte[] nonce() { return getNonce(); }
    public State getState() { return state; }
    public State state() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant createdAt() { return createdAt; }

    private static byte[] copy(byte[] value) {
        return Arrays.copyOf(Objects.requireNonNull(value, "revision ciphertext must not be null"), value.length);
    }

    private static byte[] copyNullable(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
