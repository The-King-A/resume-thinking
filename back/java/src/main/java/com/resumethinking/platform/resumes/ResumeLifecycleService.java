package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class ResumeLifecycleService {
    public static final String CONFIRMATION = "确认删除简历";
    private final ResumeRepository repository; private final ResumeCache cache; private final ResumeAuditRepository audit; private final Clock clock;
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit, Clock clock){this.repository=repository;this.cache=cache;this.audit=audit;this.clock=clock;}
    public ResumeLifecycleService(ResumeRepository repository, ResumeCache cache, ResumeAuditRepository audit){this(repository,cache,audit,Clock.systemUTC());}

    @Transactional
    public Resume softDelete(DeleteResumeCommand command) {
        if (!CONFIRMATION.equals(command.confirmationText())) throw new InvalidConfirmationException();
        Resume resume = repository.findById(command.resumeId()).filter(r -> command.role()==UserRole.ADMIN || r.getOwnerId().equals(command.actorId())).orElseThrow(ResourceNotFoundException::new);
        if (resume.getVisibilityState()!=VisibilityState.ACTIVE) {
            if (resume.getVisibilityState()==VisibilityState.ADMIN_SOFT_DELETED && command.role()!=UserRole.ADMIN) throw new ResourceNotFoundException();
            return resume;
        }
        requireVersion(resume, command.expectedVersion());
        resume.softDelete(command.actorId(), command.role(), clock.instant()); repository.save(resume); cache.evict(resume.getId());
        audit.save(new ResumeAuditRepository.ResumeLifecycleAudit(resume.getId(),command.actorId(),"SOFT_DELETED",resume.getVisibilityState(),clock.instant())); return resume;
    }

    @Transactional
    public ResumeView recover(UUID resumeId, UUID actorId, UserRole role, long expectedVersion) {
        Resume resume = repository.findRecoverable(resumeId,actorId,role).orElseThrow(ResourceNotFoundException::new);
        if (resume.getVisibilityState()==VisibilityState.ACTIVE) return ResumeView.from(resume);
        requireVersion(resume, expectedVersion); resume.restore(clock.instant()); repository.save(resume); cache.put(resume);
        audit.save(new ResumeAuditRepository.ResumeLifecycleAudit(resumeId,actorId,"RESTORED",resume.getVisibilityState(),clock.instant())); return ResumeView.from(resume);
    }

    @Transactional
    public int archiveDue(Instant at) {
        int count=0; for (Resume resume : repository.findActiveDue(at)) { if (resume.getVisibilityState()!=VisibilityState.ACTIVE) continue; resume.archive(at); repository.save(resume); cache.evict(resume.getId()); audit.save(new ResumeAuditRepository.ResumeLifecycleAudit(resume.getId(),null,"ARCHIVED",resume.getVisibilityState(),at)); count++; } return count;
    }
    public List<Resume> listActive(UUID ownerId){ return repository.findActiveByOwnerId(ownerId); }
    public List<Resume> listActive(UUID actorId, UserRole role){ return role==UserRole.ADMIN ? repository.findByVisibilityState(VisibilityState.ACTIVE) : repository.findActiveByOwnerId(actorId); }
    public Resume getActive(UUID id, UUID actorId, UserRole role){ return repository.findById(id).filter(r -> r.getVisibilityState()==VisibilityState.ACTIVE && (role==UserRole.ADMIN || r.getOwnerId().equals(actorId))).orElseThrow(ResourceNotFoundException::new); }
    public List<Resume> listRecoverable(UUID actorId, UserRole role){
        if (role==UserRole.USER) { var a=new ArrayList<Resume>(); a.addAll(repository.findByOwnerIdAndVisibilityState(actorId, VisibilityState.USER_SOFT_DELETED)); a.addAll(repository.findByOwnerIdAndVisibilityState(actorId, VisibilityState.USER_CACHE_ARCHIVED)); return a; }
        var a=new ArrayList<Resume>(); for (var state : VisibilityState.values()) if (state!=VisibilityState.ACTIVE) a.addAll(repository.findByVisibilityState(state)); return a;
    }
    public List<Resume> listRecoverable(UUID actorId, UserRole role, UUID ownerId){ return listRecoverable(actorId,role).stream().filter(r -> ownerId==null || r.getOwnerId().equals(ownerId)).toList(); }
    private void requireVersion(Resume resume,long expected){if(resume.getVersion()!=expected) throw new VersionConflictException();}
}
