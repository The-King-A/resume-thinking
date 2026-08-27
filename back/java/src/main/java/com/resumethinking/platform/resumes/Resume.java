package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "resumes")
public class Resume {
    public enum SourceType { TXT, DOCX }
    @Id @Column(columnDefinition = "BINARY(16)") private UUID id;
    @Column(name="owner_id", nullable=false, columnDefinition="BINARY(16)") private UUID ownerId;
    @Column(nullable=false, length=200) private String title;
    @Enumerated(EnumType.STRING) @Column(name="source_type", nullable=false, length=8) private SourceType sourceType;
    @Column(name="parser_version", nullable=false, length=64) private String parserVersion;
    @Column(name="raw_content_ciphertext", nullable=false, columnDefinition="MEDIUMBLOB") private byte[] encryptedRawContent;
    @Column(name="raw_content_nonce", columnDefinition="VARBINARY(12)") private byte[] rawContentNonce;
    @Enumerated(EnumType.STRING) @Column(name="creator_role", nullable=false, length=16) private UserRole creatorRole;
    @Column(nullable=false) private int status;
    @Enumerated(EnumType.STRING) @Column(name="visibility_state", nullable=false, length=32) private VisibilityState visibilityState;
    @Column(name="visible_until") private Instant visibleUntil;
    @Column(name="soft_deleted_by", columnDefinition="BINARY(16)") private UUID softDeletedBy;
    @Column(name="soft_deleted_at") private Instant softDeletedAt;
    @Column(name="archived_at") private Instant archivedAt;
    @Column(name="restored_at") private Instant restoredAt;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Version private long version;

    protected Resume() {}
    public static Resume active(UUID id, UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, Instant createdAt, long version) {
        Resume r = new Resume(); r.id=id; r.ownerId=ownerId; r.title=title; r.sourceType=sourceType; r.creatorRole=creatorRole;
        r.encryptedRawContent=new byte[0]; r.parserVersion="v1"; r.status=0; r.visibilityState=VisibilityState.ACTIVE; r.createdAt=createdAt; r.updatedAt=createdAt;
        r.visibleUntil=createdAt.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7)); r.version=version; return r;
    }
    public Resume(UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, Instant now) {
        this(UUID.randomUUID(), ownerId, title, sourceType, creatorRole, encryptedRawContent, now, "v1");
    }
    public Resume(UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, byte[] rawContentNonce, Instant now, String parserVersion) {
        this(UUID.randomUUID(), ownerId, title, sourceType, creatorRole, encryptedRawContent, rawContentNonce, now, parserVersion);
    }
    public Resume(UUID id, UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, Instant now) {
        this(id, ownerId, title, sourceType, creatorRole, encryptedRawContent, now, "v1");
    }
    public Resume(UUID id, UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, Instant now, String parserVersion) {
        this.id=id; this.ownerId=ownerId; this.title=title; this.sourceType=sourceType; this.creatorRole=creatorRole; this.encryptedRawContent=encryptedRawContent; this.parserVersion=parserVersion;
        this.status=0; this.visibilityState=VisibilityState.ACTIVE; this.createdAt=now; this.updatedAt=now; this.visibleUntil=now.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7));
    }
    public Resume(UUID id, UUID ownerId, String title, SourceType sourceType, UserRole creatorRole, byte[] encryptedRawContent, byte[] rawContentNonce, Instant now, String parserVersion) {
        this(id, ownerId, title, sourceType, creatorRole, encryptedRawContent, now, parserVersion); this.rawContentNonce = rawContentNonce;
    }
    public void softDelete(UUID actorId, UserRole role, Instant now) { status=1; visibilityState=role==UserRole.ADMIN?VisibilityState.ADMIN_SOFT_DELETED:VisibilityState.USER_SOFT_DELETED; softDeletedBy=actorId; softDeletedAt=now; updatedAt=now; }
    public void archive(Instant now) { if (visibilityState==VisibilityState.ACTIVE) { visibilityState=creatorRole==UserRole.ADMIN?VisibilityState.ADMIN_CACHE_ARCHIVED:VisibilityState.USER_CACHE_ARCHIVED; archivedAt=now; updatedAt=now; } }
    public void restore(Instant now) { status=0; visibilityState=VisibilityState.ACTIVE; softDeletedBy=null; softDeletedAt=null; archivedAt=null; restoredAt=now; updatedAt=now; visibleUntil=now.plus(creatorRole==UserRole.ADMIN?Duration.ofDays(30):Duration.ofDays(7)); }
    void advanceVersionForPersistence() { version++; }
    public UUID getId(){return id;} public UUID id(){return id;} public UUID getOwnerId(){return ownerId;} public String getTitle(){return title;} public SourceType getSourceType(){return sourceType;} public String getParserVersion(){return parserVersion;} public byte[] getEncryptedRawContent(){return encryptedRawContent;} public byte[] getRawContentNonce(){return rawContentNonce;} public UserRole getCreatorRole(){return creatorRole;} public int getStatus(){return status;} public VisibilityState getVisibilityState(){return visibilityState;} public Instant getVisibleUntil(){return visibleUntil;} public UUID getSoftDeletedBy(){return softDeletedBy;} public Instant getSoftDeletedAt(){return softDeletedAt;} public Instant getArchivedAt(){return archivedAt;} public Instant getRestoredAt(){return restoredAt;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public long getVersion(){return version;}
}
