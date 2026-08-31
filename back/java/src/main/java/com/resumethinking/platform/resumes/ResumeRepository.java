package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.*;

public interface ResumeRepository extends Repository<Resume, String> {
    Resume save(Resume resume);
    Optional<Resume> findById(String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Resume r where r.id = :id") Optional<Resume> findByIdForUpdate(@Param("id") String id);
    Page<Resume> findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState state, Instant at, Pageable pageable);
    Page<Resume> findByVisibilityStateAndVisibleUntilAfter(VisibilityState state, Instant at, Pageable pageable);
    Page<Resume> findByOwnerIdAndVisibilityState(String ownerId, VisibilityState state, Pageable pageable);
    Page<Resume> findByOwnerIdAndVisibilityStateAndVisibleUntilAfter(String ownerId, VisibilityState state, Instant at, Pageable pageable);
    Page<Resume> findByOwnerIdAndVisibilityStateIn(String ownerId, Collection<VisibilityState> states, Pageable pageable);
    Page<Resume> findByOwnerIdAndVisibilityStateInAndVisibleUntilAfter(String ownerId, Collection<VisibilityState> states, Instant at, Pageable pageable);
    Page<Resume> findByVisibilityStateIn(Collection<VisibilityState> states, Pageable pageable);
    Page<Resume> findByVisibilityStateInAndVisibleUntilAfter(Collection<VisibilityState> states, Instant at, Pageable pageable);
    default Optional<Resume> findActiveByIdAndOwnerId(String id, String ownerId) { return findById(id).filter(r -> r.getOwnerId().equals(ownerId) && r.getVisibilityState()==VisibilityState.ACTIVE); }
    default Optional<Resume> findRecoverable(String id, String actorId, UserRole role) {
        return findByIdForUpdate(id).filter(r -> role==UserRole.ADMIN
            ? EnumSet.of(VisibilityState.USER_SOFT_DELETED, VisibilityState.ADMIN_SOFT_DELETED,
                    VisibilityState.USER_CACHE_ARCHIVED, VisibilityState.ADMIN_CACHE_ARCHIVED)
                    .contains(r.getVisibilityState())
            : r.getOwnerId().equals(actorId) && (r.getVisibilityState()==VisibilityState.USER_SOFT_DELETED
                || r.getVisibilityState()==VisibilityState.USER_CACHE_ARCHIVED));
    }
    default Page<Resume> findActiveDue(Instant at, Pageable pageable) { return findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState.ACTIVE, at, pageable); }
    class InMemory implements ResumeRepository {
        private final Map<String,Resume> values=new LinkedHashMap<>();
        public Resume save(Resume r){if(values.containsKey(r.getId())) r.advanceVersionForPersistence(); values.put(r.getId(),r); return r;}
        public Optional<Resume> findById(String id){return Optional.ofNullable(values.get(id));}
        public Optional<Resume> findByIdForUpdate(String id){return findById(id);}
        public Page<Resume> findByVisibilityStateAndVisibleUntilLessThanEqual(VisibilityState s,Instant at,Pageable p){return page(values.values().stream().filter(r->r.getVisibilityState()==s && r.getVisibleUntil()!=null && !r.getVisibleUntil().isAfter(at)).toList(),p);}
        public Page<Resume> findByVisibilityStateAndVisibleUntilAfter(VisibilityState s,Instant at,Pageable p){return page(values.values().stream().filter(r->r.getVisibilityState()==s && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at)).toList(),p);}
        public Page<Resume> findByOwnerIdAndVisibilityState(String owner,VisibilityState s,Pageable p){return page(values.values().stream().filter(r->r.getOwnerId().equals(owner)&&r.getVisibilityState()==s).toList(),p);}
        public Page<Resume> findByOwnerIdAndVisibilityStateAndVisibleUntilAfter(String owner,VisibilityState s,Instant at,Pageable p){return page(values.values().stream().filter(r->r.getOwnerId().equals(owner)&&r.getVisibilityState()==s&&r.getVisibleUntil()!=null&&r.getVisibleUntil().isAfter(at)).toList(),p);}
        public Page<Resume> findByOwnerIdAndVisibilityStateIn(String owner,Collection<VisibilityState> states,Pageable p){return page(values.values().stream().filter(r->r.getOwnerId().equals(owner)&&states.contains(r.getVisibilityState())).toList(),p);}
        public Page<Resume> findByOwnerIdAndVisibilityStateInAndVisibleUntilAfter(String owner,Collection<VisibilityState> states,Instant at,Pageable p){return page(values.values().stream().filter(r->r.getOwnerId().equals(owner)&&states.contains(r.getVisibilityState())&&r.getVisibleUntil()!=null&&r.getVisibleUntil().isAfter(at)).toList(),p);}
        public Page<Resume> findByVisibilityStateIn(Collection<VisibilityState> states,Pageable p){return page(values.values().stream().filter(r->states.contains(r.getVisibilityState())).toList(),p);}
        public Page<Resume> findByVisibilityStateInAndVisibleUntilAfter(Collection<VisibilityState> states,Instant at,Pageable p){return page(values.values().stream().filter(r->states.contains(r.getVisibilityState())&&r.getVisibleUntil()!=null&&r.getVisibleUntil().isAfter(at)).toList(),p);}
        private static Page<Resume> page(List<Resume> all,Pageable p){int from=(int)Math.min(all.size(),p.getOffset()),to=Math.min(all.size(),from+p.getPageSize());return new PageImpl<>(all.subList(from,to),p,all.size());}
    }
}
