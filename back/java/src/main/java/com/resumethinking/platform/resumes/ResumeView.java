package com.resumethinking.platform.resumes;

import java.time.Instant;
import java.util.UUID;

public record ResumeView(UUID id, UUID ownerId, String title, Resume.SourceType sourceType, int status,
                         VisibilityState visibilityState, long version, Instant visibleUntil,
                         Instant softDeletedAt, Instant archivedAt, Instant restoredAt,
                         Instant createdAt, Instant updatedAt) {
    public static ResumeView from(Resume r) { return new ResumeView(r.getId(),r.getOwnerId(),r.getTitle(),r.getSourceType(),r.getStatus(),r.getVisibilityState(),r.getVersion(),r.getVisibleUntil(),r.getSoftDeletedAt(),r.getArchivedAt(),r.getRestoredAt(),r.getCreatedAt(),r.getUpdatedAt()); }
    public int getStatus(){return status;} public VisibilityState getVisibilityState(){return visibilityState;} public long getVersion(){return version;}
}
