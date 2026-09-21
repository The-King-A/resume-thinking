package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.matching.AnalysisEvidenceRepository;
import com.resumethinking.platform.matching.AnalysisResult;
import com.resumethinking.platform.matching.AnalysisResultRepository;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.matching.MatchTaskRepository;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
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
    private final List<ResumeTaskBlocker> taskBlockers;
    private final EntityManager entityManager;
    private final ResumeRevisionRepository revisions;
    private final MatchTaskRepository analysisTasks;
    private final AnalysisResultRepository analysisResults;
    private final AnalysisEvidenceRepository analysisEvidence;
    @Autowired
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  List<ResumeTaskBlocker> taskBlockers, ReadableIdGenerator ids, EntityManager entityManager,
                                  ResumeRevisionRepository revisions, MatchTaskRepository analysisTasks,
                                  AnalysisResultRepository analysisResults, AnalysisEvidenceRepository analysisEvidence){
        this.repository=repository;this.cache=cache;this.audit=audit;this.clock=clock;
        this.taskBlockers = taskBlockers == null || taskBlockers.isEmpty()
                ? List.of(ResumeTaskBlocker.NOOP) : List.copyOf(taskBlockers);
        this.ids=ids; this.entityManager=entityManager;
        this.revisions=revisions == null ? new ResumeRevisionRepository.InMemory() : revisions;
        this.analysisTasks=analysisTasks;
        this.analysisResults=analysisResults;
        this.analysisEvidence=analysisEvidence;
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  ResumeTaskBlocker taskBlocker, ReadableIdGenerator ids, EntityManager entityManager,
                                  ResumeRevisionRepository revisions, MatchTaskRepository analysisTasks,
                                  AnalysisResultRepository analysisResults, AnalysisEvidenceRepository analysisEvidence) {
        this(repository, cache, audit, clock, taskBlocker == null ? List.of() : List.of(taskBlocker), ids,
                entityManager, revisions, analysisTasks, analysisResults, analysisEvidence);
    }
    /** Compatibility constructor for tests and embedders that do not expose report stores. */
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  ResumeTaskBlocker taskBlocker, ReadableIdGenerator ids, EntityManager entityManager,
                                  ResumeRevisionRepository revisions){
        this(repository,cache,audit,clock,taskBlocker,ids,entityManager,revisions,null,null,null);
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  ResumeTaskBlocker taskBlocker, ReadableIdGenerator ids, EntityManager entityManager){
        this(repository,cache,audit,clock,taskBlocker,ids,entityManager,new ResumeRevisionRepository.InMemory());
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock){
        this(repository,cache,audit,clock,ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator(),null);
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock, ResumeTaskBlocker taskBlocker){
        this(repository,cache,audit,clock,taskBlocker,new InMemoryReadableIdGenerator(),null);
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock,
                                  ResumeTaskBlocker taskBlocker, ReadableIdGenerator ids){
        this(repository,cache,audit,clock,taskBlocker,ids,null);
    }
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit){
        this(repository,cache,audit,Clock.systemUTC(),ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator(),null);
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
            discardPendingRevision(resume, at);
            resume.archive(at);
            blockPendingTasks(resume.getId());
            repository.save(resume);
            cacheAfterCommit(() -> cache.evict(resume.getId()));
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
                if (resume.getVisibilityState()==VisibilityState.USER_SOFT_DELETED) {
                    discardPendingRevisionIfPresent(resume, at);
                    return resume;
                }
                throw new ResourceNotFoundException();
            }
            if (resume.getVisibilityState()==VisibilityState.ADMIN_SOFT_DELETED) {
                discardPendingRevisionIfPresent(resume, at);
                return resume;
            }
            // An administrator action supersedes a prior user soft deletion,
            // but cache-archived records were rejected above.
            if (resume.getVisibilityState()!=VisibilityState.USER_SOFT_DELETED) throw new ResourceNotFoundException();
        }
        requireVersion(resume, command.expectedVersion());
        VisibilityState prior=resume.getVisibilityState();
        discardPendingRevision(resume, at);
        resume.softDelete(command.actorId(), command.role(), at);
        blockPendingTasks(resume.getId());
        repository.save(resume);
        cacheAfterCommit(() -> cache.evict(resume.getId()));
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
                // Do not report an idempotent success for a legacy row that
                // has no validated report: it would still be absent from the
                // effective-resume list after this call.  This check stays
                // behind the authorization/state gate so hidden records do
                // not leak a more specific validation error.
                requireEffectiveRevision(resume);
                return ResumeView.from(resume);
            }
            throw new ResourceNotFoundException();
        }

        boolean recoverable = role == UserRole.ADMIN
                ? EnumSet.of(VisibilityState.USER_SOFT_DELETED, VisibilityState.ADMIN_SOFT_DELETED,
                        VisibilityState.USER_CACHE_ARCHIVED, VisibilityState.ADMIN_CACHE_ARCHIVED).contains(state)
                : (state == VisibilityState.USER_SOFT_DELETED || state == VisibilityState.USER_CACHE_ARCHIVED);
        if (!recoverable) throw new ResourceNotFoundException();
        requireVersion(resume, expectedVersion);
        // A restore is only meaningful when a validated revision exists.  Do
        // this after the ownership/state/version gates so an unauthorized or
        // otherwise non-recoverable row keeps the existing not-found behavior.
        ResumeRevision effectiveRevision = requireEffectiveRevision(resume);
        VisibilityState prior=resume.getVisibilityState(); Instant at=now;
        rejectDuplicateEffectiveTitle(resume, effectiveRevision);
        discardPendingRevision(resume, at);
        resume.restore(effectiveRevision, at); repository.save(resume); flushPersistence();
        cacheAfterCommit(() -> cache.put(resume));
        audit.save(newAudit(resumeId,actorId,"RESTORED",prior,resume.getVisibilityState(),at)); return ResumeView.from(resume);
    }

    private void flushPersistence() {
        if (entityManager != null) entityManager.flush();
    }

    @Transactional
    public int archiveDue(Instant at) {
        int count=0;
        Page<Resume> due;
        do {
            due=repository.findActiveDue(at,PageRequest.of(0,100,Sort.by("id")));
            int archivedInPage = 0;
            for (Resume candidate : due) {
                Resume resume = repository.findByIdForUpdate(candidate.getId()).orElse(null);
                if (resume == null || resume.getStatus() != 0 || resume.getVisibilityState()!=VisibilityState.ACTIVE || resume.getVisibleUntil() == null || resume.getVisibleUntil().isAfter(at)) continue;
                VisibilityState prior=resume.getVisibilityState();
                discardPendingRevision(resume, at);
                resume.archive(at);
                blockPendingTasks(resume.getId());
                repository.save(resume);
                cacheAfterCommit(() -> cache.evict(resume.getId()));
                audit.save(newAudit(resume.getId(),null,"ARCHIVED",prior,resume.getVisibilityState(),at)); count++; archivedInPage++;
            }
            // A concurrent lifecycle transition can make every row in the
            // page ineligible after the initial query.  Stop when no row was
            // changed so the scheduler cannot spin on the same page forever.
            if (archivedInPage == 0) break;
        } while (due.hasContent());
        return count;
    }
    public Page<Resume> listActive(String actorId, UserRole role, Pageable pageable){
        Instant at = clock.instant();
        return role==UserRole.ADMIN
                ? repository.findByStatusAndVisibilityStateAndEffectiveRevisionIdIsNotNullAndVisibleUntilAfter(0,VisibilityState.ACTIVE,at,pageable)
                : repository.findByOwnerIdAndStatusAndVisibilityStateAndEffectiveRevisionIdIsNotNullAndVisibleUntilAfter(actorId,0,VisibilityState.ACTIVE,at,pageable);
    }
    public Resume getActive(String id, String actorId, UserRole role){
        Instant at = clock.instant();
        return repository.findById(id).filter(r -> r.getStatus() == 0 && r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at)
                && (role==UserRole.ADMIN || r.getOwnerId().equals(actorId))).orElseThrow(ResourceNotFoundException::new);
    }
    public Resume authorizeTaskOwner(String id, String actorId, UserRole role) {
        return repository.findById(id)
                .filter(resume -> role == UserRole.ADMIN || resume.getOwnerId().equals(actorId))
                .orElseThrow(ResourceNotFoundException::new);
    }
    @Transactional
    public Resume upload(String ownerId, UserRole role, String title, Resume.SourceType sourceType, byte[] ciphertext, byte[] nonce) {
        if (ciphertext == null || ciphertext.length == 0 || nonce == null || nonce.length != 12) throw new IllegalArgumentException("VALIDATION_ERROR");
        Instant now=clock.instant();
        Resume resume=new Resume(ids.next(BusinessIdType.RESUME),ownerId,title,sourceType,role,ciphertext,nonce,now,"v1");
        repository.save(resume);
        ResumeRevision revision=new ResumeRevision(ids.next(BusinessIdType.REVISION),resume.getId(),1,title,
                normalizeTitle(title),sourceType,"v1",ciphertext,nonce,ResumeRevision.State.EFFECTIVE,now);
        revisions.save(revision); resume.publish(revision,now); repository.save(resume);
        cacheAfterCommit(() -> cache.put(resume)); return resume;
    }
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
                        && r.getStatus() == 0
                        && r.getVisibilityState() == VisibilityState.ACTIVE
                        && r.getVisibleUntil() != null
                        && r.getVisibleUntil().isAfter(at))
                .orElseThrow(ResourceNotFoundException::new);
        String revisionId = resume.getEffectiveRevisionId();
        if (revisionId == null) {
            Optional<ResumeRevision> latest = revisions.findLatestByResumeId(resume.getId());
            if (latest.isPresent()) {
                if (latest.get().getState() != ResumeRevision.State.EFFECTIVE) {
                    throw new ResourceNotFoundException();
                }
                revisionId = latest.get().getId();
            } else {
                ResumeRevision compatibilityRevision = new ResumeRevision(ids.next(BusinessIdType.REVISION),
                        resume.getId(), 1, resume.getTitle(), normalizeTitle(resume.getTitle()), resume.getSourceType(),
                        resume.getParserVersion(), resume.getEncryptedRawContent(), resume.getRawContentNonce(),
                        ResumeRevision.State.EFFECTIVE, at);
                revisionId = revisions.save(compatibilityRevision).getId();
            }
        }
        return new ResumeAnalysisReservation(resume.getId(),revisionId,resume.getVersion(),resume.getSourceType());
    }
    @Transactional(readOnly=true)
    public boolean isActiveAtVersion(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findById(resumeId).map(r -> r.getStatus() == 0 && r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version).orElse(false);
    }
    @Transactional
    public Optional<Resume> lockActiveAtVersion(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findByIdForUpdate(resumeId).filter(r -> r.getStatus() == 0 && r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version);
    }
    @Transactional(readOnly=true)
    public Optional<Resume> findActiveForAnalysis(String resumeId,long version){
        Instant at = clock.instant();
        return repository.findById(resumeId).filter(r -> r.getStatus() == 0
                && r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at) && r.getVersion()==version);
    }

    @Transactional
    public Optional<Resume> lockActiveForRevision(String resumeId,String revisionId){
        Instant at=clock.instant();
        Optional<Resume> locked=repository.findByIdForUpdate(resumeId).filter(r -> r.getStatus() == 0 && r.getVisibilityState()==VisibilityState.ACTIVE
                && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at));
        if (locked.isEmpty()) return Optional.empty();
        return revisions.findByIdForUpdate(revisionId).filter(revision -> revision.getResumeId().equals(resumeId))
                .map(ignored -> locked.get());
    }

    @Transactional(readOnly=true)
    public boolean isActiveForRevision(String resumeId,String revisionId){
        Instant at=clock.instant();
        return repository.findById(resumeId).filter(r -> r.getStatus() == 0 && r.getVisibilityState()==VisibilityState.ACTIVE
                        && r.getVisibleUntil()!=null && r.getVisibleUntil().isAfter(at))
                .flatMap(r -> revisions.findById(revisionId).filter(revision -> revision.getResumeId().equals(resumeId)))
                .isPresent();
    }

    @Transactional
    public void publishPendingRevision(String resumeId,String revisionId){
        Resume resume=repository.findByIdForUpdate(resumeId).orElseThrow(ResourceNotFoundException::new);
        if (resume.getStatus() != 0 || resume.getVisibilityState()!=VisibilityState.ACTIVE
                || !Objects.equals(resume.getPendingRevisionId(),revisionId)) {
            throw new IllegalStateException("revision is not the current candidate");
        }
        ResumeRevision revision=revisions.findByIdForUpdate(revisionId)
                .filter(value -> value.getResumeId().equals(resumeId) && value.getState()==ResumeRevision.State.PENDING)
                .orElseThrow(() -> new IllegalStateException("revision is not pending"));
        rejectDuplicateEffectiveTitle(resume,revision.getTitleKey());
        revision.markEffective(); revisions.save(revision);
        resume.publish(revision,clock.instant()); repository.saveAndFlush(resume); flushPersistence();
        cacheAfterCommit(() -> cache.put(resume));
    }

    @Transactional
    public void markPendingRevisionFailed(String resumeId,String revisionId){
        Resume resume=repository.findByIdForUpdate(resumeId).orElseThrow(ResourceNotFoundException::new);
        if (!Objects.equals(resume.getPendingRevisionId(),revisionId)) return;
        revisions.findByIdForUpdate(revisionId).filter(value -> value.getResumeId().equals(resumeId))
                .ifPresent(value -> { value.markFailed(); revisions.save(value); });
    }

    public static String normalizeTitle(String title){
        return ResumeTitleNormalizer.normalize(title);
    }

    private void rejectDuplicateEffectiveTitle(Resume resume){
        if (resume.getEffectiveRevisionId()!=null) rejectDuplicateEffectiveTitle(resume,normalizeTitle(resume.getTitle()));
    }

    private void rejectDuplicateEffectiveTitle(Resume resume, ResumeRevision effectiveRevision){
        if (resume.getEffectiveRevisionId() == null && effectiveRevision == null) return;
        String titleKey = effectiveRevision == null
                ? normalizeTitle(resume.getTitle())
                : effectiveRevision.getTitleKey();
        rejectDuplicateEffectiveTitle(resume, titleKey);
    }

    private void rejectDuplicateEffectiveTitle(Resume resume,String titleKey){
        repository.findByOwnerIdAndEffectiveTitleKey(resume.getOwnerId(),titleKey)
                .filter(other -> !other.getId().equals(resume.getId()))
                .ifPresent(other -> { throw new DuplicateResumeTitleException(); });
    }

    private ResumeRevision requireEffectiveRevision(Resume resume) {
        String revisionId = resume.getEffectiveRevisionId();
        if (revisionId == null) {
            // Keep the lightweight in-memory/embedding constructors compatible
            // with their original lifecycle-only contract.  The production
            // bean always wires all three report repositories, so a real
            // request with no effective revision still fails closed.
            if (analysisTasks == null && analysisResults == null && analysisEvidence == null) return null;
            throw new ResumeNotEffectiveException();
        }

        // The pointer is only a projection.  Validate the referenced row
        // while the resume is locked so a manually repaired or stale pointer
        // cannot make an unmatched resume appear effective.
        ResumeRevision revision;
        try {
            revision = revisions.findByIdForUpdate(revisionId).orElse(null);
        } catch (RuntimeException failure) {
            throw new ResumeNotEffectiveException();
        }
        if (revision == null) {
            // The simplified constructors used by older in-memory embedders
            // can carry only the logical resume projection and therefore do
            // not register immutable revision rows.  Production always
            // wires the report stores and must fail closed instead.
            if (analysisTasks == null && analysisResults == null && analysisEvidence == null) return null;
            throw new ResumeNotEffectiveException();
        }
        if (!Objects.equals(revision.getResumeId(), resume.getId())
                || revision.getState() != ResumeRevision.State.EFFECTIVE) {
            throw new ResumeNotEffectiveException();
        }

        // A partially wired report store is unsafe: accepting recovery in
        // that state would make evidence validation depend on which bean was
        // omitted.  Only the explicit all-null compatibility mode above may
        // bypass report validation.
        if (analysisTasks == null || analysisResults == null || analysisEvidence == null) {
            throw new ResumeNotEffectiveException();
        }

        AnalysisResult result;
        try {
            result = analysisResults.findLatestByResumeIdAndRevisionId(resume.getId(), revisionId)
                    .orElse(null);
        } catch (RuntimeException failure) {
            throw new ResumeNotEffectiveException();
        }
        if (result == null
                || !Objects.equals(result.resumeId(), resume.getId())
                || !Objects.equals(result.revisionId(), revisionId)
                || result.taskId() == null) {
            throw new ResumeNotEffectiveException();
        }

        MatchTask task;
        try {
            task = analysisTasks.findById(result.taskId()).orElse(null);
        } catch (RuntimeException failure) {
            throw new ResumeNotEffectiveException();
        }
        if (task == null
                || !Objects.equals(task.getResumeId(), resume.getId())
                || !Objects.equals(task.getRevisionId(), revisionId)
                || task.getState() != MatchTask.State.SUCCEEDED
                || !task.isResultAvailable()
                || (task.getPublicationState() != MatchTask.PublicationState.NOT_REQUESTED
                    && task.getPublicationState() != MatchTask.PublicationState.PUBLISHED)) {
            throw new ResumeNotEffectiveException();
        }
        try {
            if (analysisEvidence.findByTaskId(task.getId()).isEmpty()) {
                throw new ResumeNotEffectiveException();
            }
        } catch (ResumeNotEffectiveException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new ResumeNotEffectiveException();
        }
        return revision;
    }

    /**
     * Invalidate a staged candidate whenever its logical resume is hidden or
     * restored.  The resume row is already locked by the caller, so locking
     * the candidate revision here preserves the callback lock order.  A
     * malformed/missing pointer is cleared, but a revision owned by another
     * resume is rejected rather than mutated.
     */
    private void discardPendingRevision(Resume resume, Instant at) {
        String pendingId = resume.getPendingRevisionId();
        if (pendingId == null) return;
        ResumeRevision pending = revisions.findByIdForUpdate(pendingId).orElse(null);
        if (pending != null) {
            if (!Objects.equals(pending.getResumeId(), resume.getId())) {
                throw new IllegalStateException("pending revision belongs to another resume");
            }
            // Never demote an EFFECTIVE revision.  In a consistent row this
            // branch is a PENDING candidate; FAILED/SUPERSEDED are already
            // terminal and only need their stale pointer removed.
            if (pending.getState() == ResumeRevision.State.PENDING) {
                pending.markSuperseded();
                revisions.save(pending);
            }
        }
        resume.clearPendingRevision(pendingId, at);
    }

    private void discardPendingRevisionIfPresent(Resume resume, Instant at) {
        if (resume.getPendingRevisionId() == null) return;
        discardPendingRevision(resume, at);
        blockPendingTasks(resume.getId());
        repository.save(resume);
    }
    private void blockPendingTasks(String resumeId) {
        for (ResumeTaskBlocker blocker : taskBlockers) {
            blocker.blockPendingTasks(resumeId);
        }
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
