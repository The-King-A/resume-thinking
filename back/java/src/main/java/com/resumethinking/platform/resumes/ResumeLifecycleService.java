package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.*;
import com.resumethinking.platform.ids.*;
import java.time.*;
import java.util.*;

@Service
public class ResumeLifecycleService {
    public static final String CONFIRMATION = "确认删除简历";
    private final ResumeRepository repository; private final ResumeCache cache; private final ResumeAuditRepository audit; private final Clock clock; private final ReadableIdGenerator ids;
    private final ResumeTaskBlocker taskBlocker;
    @Autowired
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  ResumeTaskBlocker taskBlocker, ReadableIdGenerator ids){
        this.repository=repository;this.cache=cache;this.audit=audit;this.clock=clock;
        this.taskBlocker=taskBlocker == null ? ResumeTaskBlocker.NOOP : taskBlocker; this.ids=ids;
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock){
        this(repository,cache,audit,clock,ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator());
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock, ResumeTaskBlocker taskBlocker){
        this(repository,cache,audit,clock,taskBlocker,new InMemoryReadableIdGenerator());
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit){
        this(repository,cache,audit,Clock.systemUTC(),ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator());
    }

    @Transactional(noRollbackFor = ResourceNotFoundException.class)
    public Resume softDelete(DeleteResumeCommand command) {
        if (!CONFIRMATION.equals(command.confirmationText())) throw new InvalidConfirmationException();
        // The callback path locks this same row before deciding whether to persist a result.
        // Lock the row before authorization/version checks so delete and callback cannot race.
        Resume resume = repository.findByIdForUpdate(command.resumeId())
                .filter(r -> command.role()==UserRole.ADMIN || r.getOwnerId().equals(command.actorId()))
                .orElseThrow(ResourceNotFoundException::new);
        Instant at = clock.instant();
        if (resume.getVisibilityState() == VisibilityState.ACTIVE
                && resume.getVisibleUntil() != null
                && !resume.getVisibleUntil().isAfter(at)) {
            requireVersion(resume, command.expectedVersion());
            VisibilityState prior = resume.getVisibilityState();
            resume.archive(at);
            taskBlocker.blockPendingTasks(resume.getId());
            repository.save(resume);
            cacheAfterCommit(() -> cache.evict("resume:view:" + resume.getId()));
            audit.save(newAudit(resume.getId(), null, "ARCHIVED", prior,
                    resume.getVisibilityState(), at));
            throw new ResourceNotFoundException();
        }
        if (resume.getVisibilityState()!=VisibilityState.ACTIVE) {
            // Cache expiry is an archival transition, not a soft deletion.
            // It must be restored explicitly before either role can delete it.
            if (resume.getVisibilityState()==VisibilityState.USER_CACHE_ARCHIVED
                    || resume.getVisibilityState()==VisibilityState.ADMIN_CACHE_ARCHIVED) {
                throw new ResourceNotFoundException();
            }
            if (command.role()!=UserRole.ADMIN) {
                if (resume.getVisibilityState()==VisibilityState.ADMIN_SOFT_DELETED) throw new ResourceNotFoundException();
                // Repeating a user's own deletion is idempotent, but it must
                // never change an archived record back into an active one.
                if (resume.getVisibilityState()==VisibilityState.USER_SOFT_DELETED) return resume;
                throw new ResourceNotFoundException();
            }
            if (resume.getVisibilityState()==VisibilityState.ADMIN_SOFT_DELETED) return resume;
            // An administrator action supersedes a prior user soft deletion,
            // but cache-archived records were rejected above.
            if (resume.getVisibilityState()!=VisibilityState.USER_SOFT_DELETED) throw new ResourceNotFoundException();
        }
        requireVersion(resume, command.expectedVersion());
        VisibilityState prior=resume.getVisibilityState();
        resume.softDelete(command.actorId(), command.role(), at);
        taskBlocker.blockPendingTasks(resume.getId());
        repository.save(resume);
        cacheAfterCommit(() -> cache.evict("resume:view:" + resume.getId()));
        audit.save(newAudit(resume.getId(),command.actorId(),"SOFT_DELETED",prior,resume.getVisibilityState(),at)); return resume;
    }

    @Transactional
    public ResumeView recover(String resumeId, String actorId, UserRole role, long expectedVersion) {
        // Recovery competes with soft-delete, archive, and callback updates.
        // Read and authorize the row under the same pessimistic lock used by
        // those writers so a stale state cannot be restored around them.
        Resume resume = repository.findByIdForUpdate(resumeId)
                .filter(r -> role == UserRole.ADMIN || r.getOwnerId().equals(actorId))
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = clock.instant();
        VisibilityState state = resume.getVisibilityState();

        // A repeated user restore is idempotent while the record is still
        // visible.  An expired ACTIVE row must go through archiveDue first;
        // otherwise recovery would bypass the retention boundary.
        if (state == VisibilityState.ACTIVE) {
            if (role == UserRole.USER && resume.getVisibleUntil() != null && resume.getVisibleUntil().isAfter(now)) {
                return ResumeView.from(resume);
            }
            throw new ResourceNotFoundException();
        }

        boolean recoverable = role == UserRole.ADMIN
                ? EnumSet.of(VisibilityState.USER_SOFT_DELETED, VisibilityState.ADMIN_SOFT_DELETED,
                        VisibilityState.USER_CACHE_ARCHIVED, VisibilityState.ADMIN_CACHE_ARCHIVED).contains(state)
                : (state == VisibilityState.USER_SOFT_DELETED || state == VisibilityState.USER_CACHE_ARCHIVED);
        if (!recoverable) throw new ResourceNotFoundException();
        requireVersion(resume, expectedVersion); VisibilityState prior=resume.getVisibilityState(); Instant at=now;
        resume.restore(at); repository.save(resume);
        cacheAfterCommit(() -> cache.put(resume));
        audit.save(newAudit(resumeId,actorId,"RESTORED",prior,resume.getVisibilityState(),at)); return ResumeView.from(resume);
    }

    @Transactional
    public int archiveDue(Instant at) {
        int count=0;
        Page<Resume> due;
        do {
            due=repository.findActiveDue(at,PageRequest.of(0,100,Sort.by("id")));
            for (Resume candidate : due) {
                Resume resume = repository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (resume == null || resume.getVisibilityState()!=VisibilityState.ACTIVE || resume.getVisibleUntil() == null || resume.getVisibleUntil().isAfter(at)) continue;
                VisibilityState prior=resume.getVisibilityState();
                resume.archive(at);
                taskBlocker.blockPendingTasks(resume.getId());
                repository.save(resume);
                cacheAfterCommit(() -> cache.evict("resume:view:" + resume.getId()));
                audit.save(newAudit(resume.getId(),null,"ARCHIVED",prior,resume.getVisibilityState(),at)); count++;
            }
        } while (due.hasContent());
        return count;
    }
    public Page<Resume> listActive(String actorId, UserRole role, Pageable pageable){
        Instant at = clock.instant();
        return role==UserRole.ADMIN
                ? repository.findByVisibilityStateInAndVisibleUntilAfter(List.of(VisibilityState.ACTIVE),at,pageable)
                : repository.findByOwnerIdAndVisibilityStateAndVisibleUntilAfter(actorId,VisibilityState.ACTIVE,at,pageable);
    }
    public Resume getActive(String id, String actorId, UserRole role){
        Instant at = clock.instant();
        return repository.findById(id).filter(r -> r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at)
                && (role==UserRole.ADMIN || r.getOwnerId().equals(actorId))).orElseThrow(ResourceNotFoundException::new);
    }
    @Transactional
    public Resume upload(String ownerId, UserRole role, String title, Resume.SourceType sourceType, byte[] ciphertext, byte[] nonce) { if (ciphertext == null || ciphertext.length == 0 || nonce == null || nonce.length != 12) throw new IllegalArgumentException("VALIDATION_ERROR"); Resume r = new Resume(ids.next(BusinessIdType.RESUME), ownerId, title, sourceType, role, ciphertext, nonce, clock.instant(), "v1"); repository.save(r); cacheAfterCommit(() -> cache.put(r)); return r; }
    public Page<Resume> listRecoverable(String actorId, UserRole role, String ownerId, Pageable pageable){
        var states=List.of(VisibilityState.USER_SOFT_DELETED,VisibilityState.ADMIN_SOFT_DELETED,VisibilityState.USER_CACHE_ARCHIVED,VisibilityState.ADMIN_CACHE_ARCHIVED);
        if (role==UserRole.USER) return repository.findByOwnerIdAndVisibilityStateIn(actorId,List.of(VisibilityState.USER_SOFT_DELETED,VisibilityState.USER_CACHE_ARCHIVED),pageable);
        return ownerId==null ? repository.findByVisibilityStateIn(states,pageable) : repository.findByOwnerIdAndVisibilityStateIn(ownerId,states,pageable);
    }
    /**
     * Reserve the durable resume row for task creation.  Creation and
     * lifecycle transitions both acquire the resume lock before touching task
     * rows, which closes the window where a task could be inserted after a
     * delete/archive transaction has scanned pending work.
     */
    @Transactional
    public ResumeAnalysisReservation reserveForAnalysis(String resumeId, String actorId, UserRole role){
        Instant at = clock.instant();
        Resume resume = repository.findByIdForUpdate(resumeId)
                .filter(r -> (role == UserRole.ADMIN || r.getOwnerId().equals(actorId))
                        && r.getVisibilityState() == VisibilityState.ACTIVE
                        && r.getVisibleUntil() != null
                        && r.getVisibleUntil().isAfter(at))
                .orElseThrow(ResourceNotFoundException::new);
        return new ResumeAnalysisReservation(resume.getId(),resume.getVersion(),resume.getSourceType());
    }
    @Transactional(readOnly=true)
    public boolean isActiveAtVersion(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findById(resumeId).map(r -> r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version).orElse(false);
    }
    @Transactional
    public Optional<Resume> lockActiveAtVersion(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findByIdForUpdate(resumeId).filter(r -> r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version);
    }
    @Transactional(readOnly=true)
    public Optional<Resume> findActiveForAnalysis(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findById(resumeId).filter(r -> r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version);
    }
    /**
     * Cache is a derived, best-effort view. Register mutations after a
     * successful database commit so a rollback cannot leave stale state, and
     * isolate cache outages from the lifecycle transaction.
     */
    private void cacheAfterCommit(Runnable mutation) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { runCacheMutation(mutation); }
            });
            return;
        }
        // Direct callers (including unit tests and non-proxied embeddings) do
        // not have a transaction boundary; still preserve best-effort cache semantics.
        runCacheMutation(mutation);
    }

    private void runCacheMutation(Runnable mutation) {
        try { mutation.run(); } catch (RuntimeException ignored) { /* cache is non-authoritative */ }
    }
    private ResumeAuditRepository.ResumeLifecycleAudit newAudit(String resumeId,String actorId,String action,VisibilityState prior,VisibilityState next,Instant at){return new ResumeAuditRepository.ResumeLifecycleAudit(resumeId,actorId,action,prior,next,at,UUID.randomUUID());}
    private void requireVersion(Resume resume,long expected){if(resume.getVersion()!=expected) throw new VersionConflictException();}
}
