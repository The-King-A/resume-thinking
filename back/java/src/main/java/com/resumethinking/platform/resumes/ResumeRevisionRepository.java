package com.resumethinking.platform.resumes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface ResumeRevisionRepository {
    ResumeRevision save(ResumeRevision revision);

    Optional<ResumeRevision> findById(String id);

    Optional<ResumeRevision> findByIdForUpdate(String id);

    Optional<ResumeRevision> findLatestByResumeId(String resumeId);

    final class InMemory implements ResumeRevisionRepository {
        private final Map<String, ResumeRevision> values = new LinkedHashMap<>();

        @Override
        public synchronized ResumeRevision save(ResumeRevision revision) {
            values.put(revision.getId(), revision);
            return revision;
        }

        @Override
        public synchronized Optional<ResumeRevision> findById(String id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public synchronized Optional<ResumeRevision> findByIdForUpdate(String id) {
            return findById(id);
        }

        @Override
        public synchronized Optional<ResumeRevision> findLatestByResumeId(String resumeId) {
            return values.values().stream()
                    .filter(revision -> revision.getResumeId().equals(resumeId))
                    .max(Comparator.comparingLong(ResumeRevision::getRevisionNo));
        }
    }
}
