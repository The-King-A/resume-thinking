package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import org.springframework.data.repository.Repository;
import java.time.Instant;
import java.util.*;

public interface ResumeRepository extends Repository<Resume, UUID> {
    Resume save(Resume resume);
    Optional<Resume> findById(UUID id);
    List<Resume> findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState state, Instant at);
    List<Resume> findByOwnerIdAndVisibilityState(UUID ownerId, VisibilityState state);
    List<Resume> findByVisibilityState(VisibilityState state);
    default Optional<Resume> findActiveByIdAndOwnerId(UUID id, UUID ownerId) { return findById(id).filter(r -> r.getOwnerId().equals(ownerId) && r.getVisibilityState()==VisibilityState.ACTIVE); }
    default Optional<Resume> findRecoverable(UUID id, UUID actorId, UserRole role) { return findById(id).filter(r -> role==UserRole.ADMIN ? r.getVisibilityState()!=VisibilityState.ACTIVE : r.getOwnerId().equals(actorId) && (r.getVisibilityState()==VisibilityState.USER_SOFT_DELETED || r.getVisibilityState()==VisibilityState.USER_CACHE_ARCHIVED)); }
    default List<Resume> findActiveDue(Instant at) { return findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState.ACTIVE, at); }
    default List<Resume> findActiveByOwnerId(UUID ownerId) { return findByOwnerIdAndVisibilityState(ownerId, VisibilityState.ACTIVE); }
    class InMemory implements ResumeRepository { private final Map<UUID,Resume> values=new LinkedHashMap<>(); public Resume save(Resume r){values.put(r.getId(),r);return r;} public Optional<Resume> findById(UUID id){return Optional.ofNullable(values.get(id));} public List<Resume> findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState s,Instant at){return values.values().stream().filter(r->r.getVisibilityState()==s && r.getVisibleUntil()!=null && !r.getVisibleUntil().isAfter(at)).toList();} public List<Resume> findByOwnerIdAndVisibilityState(UUID owner,VisibilityState s){return values.values().stream().filter(r->r.getOwnerId().equals(owner)&&r.getVisibilityState()==s).toList();} public List<Resume> findByVisibilityState(VisibilityState s){return values.values().stream().filter(r->r.getVisibilityState()==s).toList();} }
}
