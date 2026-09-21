package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class JpaAnalysisResultRepository implements AnalysisResultRepository {
    private final AnalysisResultJpaRepository delegate;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ReadableIdGenerator ids;

    public JpaAnalysisResultRepository(AnalysisResultJpaRepository delegate, ReadableIdGenerator ids) {
        this.delegate = delegate;
        this.ids = ids;
    }

    public AnalysisResult save(AnalysisResult result) {
        try {
            delegate.save(new AnalysisResultEntity(
                    ids.next(BusinessIdType.RESULT), result, mapper.writeValueAsString(result)));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("result persistence failed", e);
        }
    }

    public Optional<AnalysisResult> findByTaskId(String id) {
        return read(delegate.findByTaskId(id));
    }

    public Optional<AnalysisResult> findLatestByResumeId(String resumeId) {
        return read(delegate.findFirstByResumeIdOrderByCompletedAtDesc(resumeId));
    }

    public Optional<AnalysisResult> findLatestByResumeIdAndRevisionId(String resumeId, String revisionId) {
        return read(delegate.findFirstByResumeIdAndRevisionIdOrderByCompletedAtDesc(resumeId, revisionId));
    }

    public long countByTaskId(String id) {
        return delegate.findByTaskId(id).isPresent() ? 1 : 0;
    }

    private Optional<AnalysisResult> read(Optional<AnalysisResultEntity> entity) {
        return entity.map(stored -> {
            try {
                AnalysisResult payload = mapper.readValue(stored.getPayloadJson(), AnalysisResult.class);
                return new AnalysisResult(
                        stored.getTaskId(), stored.getResumeId(), stored.getRevisionId(),
                        stored.getResumeVersion(), payload.jobDescriptionText(), payload.score(),
                        payload.requirements(), payload.suggestions(), stored.getCompletedAt(),
                        payload.outcome(), payload.errorCode());
            } catch (Exception exception) {
                return null;
            }
        }).filter(Objects::nonNull);
    }
}
