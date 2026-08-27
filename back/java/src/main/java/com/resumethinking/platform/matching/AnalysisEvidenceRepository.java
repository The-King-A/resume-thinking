package com.resumethinking.platform.matching;
import org.springframework.data.repository.Repository;
import java.util.*;
public interface AnalysisEvidenceRepository extends Repository<AnalysisEvidence,UUID> {
 AnalysisEvidence save(AnalysisEvidence e); List<AnalysisEvidence> findByTaskId(UUID taskId);
 final class InMemory implements AnalysisEvidenceRepository { private final Map<UUID,List<AnalysisEvidence>> values=new HashMap<>(); public synchronized AnalysisEvidence save(AnalysisEvidence e){values.computeIfAbsent(e.getTaskId(),k->new ArrayList<>()).add(e);return e;} public synchronized List<AnalysisEvidence> findByTaskId(UUID id){return List.copyOf(values.getOrDefault(id,List.of()));} }
}
