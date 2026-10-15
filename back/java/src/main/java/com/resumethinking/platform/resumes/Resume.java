package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import jakarta.persistence.*;
import java.time.*;
import java.util.Arrays;
import java.util.Objects;

@Entity
@Table(name = "resumes")
public class Resume {
    public enum SourceType { TXT, DOCX }
    @Id @Column(nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String id;
    @Column(name="owner_id", nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String ownerId;
    @Column(name="effective_revision_id", length=64, columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String effectiveRevisionId;
    @Column(name="pending_revision_id", length=64, columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String pendingRevisionId;
    @Column(name="effective_title_key", length=200,
            columnDefinition="VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin") private String effectiveTitleKey;
    @Column(nullable=false, length=200) private String title;
    @Enumerated(EnumType.STRING) @Column(name="source_type", nullable=false, length=8) private SourceType sourceType;
    @Column(name="parser_version", nullable=false, length=64) private String parserVersion;
    // V2 reads this projection; publish derives it from the authoritative V3 revision.
    @Column(name="raw_content_ciphertext", nullable=false, columnDefinition="MEDIUMBLOB") private byte[] encryptedRawContent;
    @Column(name="raw_content_nonce", columnDefinition="VARBINARY(12)") private byte[] rawContentNonce;
    @Enumerated(EnumType.STRING) @Column(name="creator_role", nullable=false, length=16) private UserRole creatorRole;
    @Column(nullable=false) private int status;
    @Enumerated(EnumType.STRING) @Column(name="visibility_state", nullable=false, length=32) private VisibilityState visibilityState;
    @Column(name="visible_until") private Instant visibleUntil;
    @Column(name="soft_deleted_by", length=64, columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String softDeletedBy;
    @Column(name="soft_deleted_at") private Instant softDeletedAt;
    @Column(name="archived_at") private Instant archivedAt;
    @Column(name="restored_at") private Instant restoredAt;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Version private long version;

    protected Resume() {}
    public static Resume active(String id, String ownerId, String title, SourceType sourceType, UserRole creatorRole, Instant createdAt, long version) {
        Resume r = new Resume(); r.id=id; r.ownerId=ownerId; r.title=title; r.sourceType=sourceType; r.creatorRole=creatorRole;
        r.encryptedRawContent=new byte[0]; r.parserVersion="v1"; r.status=0; r.visibilityState=VisibilityState.ACTIVE; r.createdAt=createdAt; r.updatedAt=createdAt;
        r.visibleUntil=createdAt.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7)); r.version=version; return r;
    }
    public Resume(String id, String ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, Instant now) {
        this(id, ownerId, title, sourceType, creatorRole, encryptedRawContent, now, "v1");
    }
    public Resume(String id, String ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, Instant now, String parserVersion) {
        this.id=id; this.ownerId=ownerId; this.title=title; this.sourceType=sourceType; this.creatorRole=creatorRole; this.encryptedRawContent=copy(encryptedRawContent); this.parserVersion=parserVersion;
        this.status=0; this.visibilityState=VisibilityState.ACTIVE; this.createdAt=now; this.updatedAt=now; this.visibleUntil=now.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7));
    }
    public Resume(String id, String ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, byte[] rawContentNonce, Instant now, String parserVersion) {
        this(id, ownerId, title, sourceType, creatorRole, encryptedRawContent, now, parserVersion); this.rawContentNonce = copyNullable(rawContentNonce);
    }
    public void softDelete(String actorId, UserRole role, Instant now) { status=1; visibilityState=role==UserRole.ADMIN?VisibilityState.ADMIN_SOFT_DELETED:VisibilityState.USER_SOFT_DELETED; effectiveTitleKey=null; softDeletedBy=actorId; softDeletedAt=now; updatedAt=now; }
    public void archive(Instant now) { if (visibilityState==VisibilityState.ACTIVE) { visibilityState=creatorRole==UserRole.ADMIN?VisibilityState.ADMIN_CACHE_ARCHIVED:VisibilityState.USER_CACHE_ARCHIVED; effectiveTitleKey=null; archivedAt=now; updatedAt=now; } }
    /**
     * Restore the logical projection from the immutable effective revision.
     * The revision is the source of truth for the title, file metadata and
     * normalized title key; the no-argument overload remains for legacy
     * embedders that only carry a logical resume projection.
     */
    public void restore(Instant now) { restore(null, now); }

    public void restore(ResumeRevision effectiveRevision, Instant now) {
        if (effectiveRevision != null) {
            if (!id.equals(effectiveRevision.getResumeId())) {
                throw new IllegalArgumentException("revision belongs to another resume");
            }
            if (effectiveRevision.getState() != ResumeRevision.State.EFFECTIVE) {
                throw new IllegalStateException("only an effective revision can be restored");
            }
            effectiveRevisionId = effectiveRevision.getId();
            title = effectiveRevision.getTitle();
            sourceType = effectiveRevision.getSourceType();
            parserVersion = effectiveRevision.getParserVersion();
            encryptedRawContent = effectiveRevision.getCiphertext();
            rawContentNonce = effectiveRevision.getNonce();
            effectiveTitleKey = effectiveRevision.getTitleKey();
        } else {
            effectiveTitleKey = effectiveRevisionId == null ? null : ResumeTitleNormalizer.normalize(title);
        }
        status=0; visibilityState=VisibilityState.ACTIVE; pendingRevisionId=null;
        softDeletedBy=null; softDeletedAt=null; archivedAt=null; restoredAt=now; updatedAt=now;
        visibleUntil=now.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7));
    }
    public void stageRevision(ResumeRevision revision, Instant now) {
        if (!id.equals(revision.getResumeId())) throw new IllegalArgumentException("revision belongs to another resume");
        pendingRevisionId=revision.getId(); updatedAt=now;
    }
    public void publish(ResumeRevision revision, Instant now) {
        if (!id.equals(revision.getResumeId())) throw new IllegalArgumentException("revision belongs to another resume");
        if (revision.getState() != ResumeRevision.State.EFFECTIVE) {
            throw new IllegalStateException("only an effective revision can be published");
        }
        boolean currentRevision = Objects.equals(effectiveRevisionId, revision.getId());
        boolean stagedRevision = Objects.equals(pendingRevisionId, revision.getId());
        if ((pendingRevisionId != null && !stagedRevision)
                || (effectiveRevisionId != null && !currentRevision && !stagedRevision)) {
            throw new IllegalStateException("revision is not the staged publication candidate");
        }
        effectiveRevisionId=revision.getId(); pendingRevisionId=null;
        title=revision.getTitle(); sourceType=revision.getSourceType(); parserVersion=revision.getParserVersion();
        encryptedRawContent=revision.getCiphertext(); rawContentNonce=revision.getNonce();
        effectiveTitleKey=revision.getTitleKey(); updatedAt=now;
    }
    public void clearPendingRevision(String revisionId, Instant now) {
        if (Objects.equals(pendingRevisionId,revisionId)) { pendingRevisionId=null; updatedAt=now; }
    }
    void advanceVersionForPersistence() { version++; }
    public String getId(){return id;} public String id(){return id;} public String getOwnerId(){return ownerId;} public String getEffectiveRevisionId(){return effectiveRevisionId;} public String getPendingRevisionId(){return pendingRevisionId;} public String getEffectiveTitleKey(){return effectiveTitleKey;} public String getTitle(){return title;} public SourceType getSourceType(){return sourceType;} public String getParserVersion(){return parserVersion;} public byte[] getEncryptedRawContent(){return copy(encryptedRawContent);} public byte[] getRawContentNonce(){return copyNullable(rawContentNonce);} public UserRole getCreatorRole(){return creatorRole;} public int getStatus(){return status;} public VisibilityState getVisibilityState(){return visibilityState;} public Instant getVisibleUntil(){return visibleUntil;} public String getSoftDeletedBy(){return softDeletedBy;} public Instant getSoftDeletedAt(){return softDeletedAt;} public Instant getArchivedAt(){return archivedAt;} public Instant getRestoredAt(){return restoredAt;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public long getVersion(){return version;}

    private static byte[] copy(byte[] value) { return Arrays.copyOf(Objects.requireNonNull(value), value.length); }
    private static byte[] copyNullable(byte[] value) { return value == null ? null : Arrays.copyOf(value, value.length); }
}
