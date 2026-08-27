package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
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
            if ((resume.getVisibilityState()==VisibilityState.ADMIN_SOFT_DELETED || resume.getVisibilityState()==VisibilityState.ADMIN_CACHE_ARCHIVED) && command.role()!=UserRole.ADMIN) throw new ResourceNotFoundException();
            return resume;
        }
        requireVersion(resume, command.expectedVersion());
        VisibilityState prior=resume.getVisibilityState(); Instant at=clock.instant();
        resume.softDelete(command.actorId(), command.role(), at); repository.save(resume); cache.evict(resume.getId());
        audit.save(newAudit(resume.getId(),command.actorId(),"SOFT_DELETED",prior,resume.getVisibilityState(),at)); return resume;
    }

    @Transactional
    public ResumeView recover(UUID resumeId, UUID actorId, UserRole role, long expectedVersion) {
        Resume resume = repository.findRecoverable(resumeId,actorId,role).orElseThrow(ResourceNotFoundException::new);
        if (resume.getVisibilityState()==VisibilityState.ACTIVE) return ResumeView.from(resume);
        requireVersion(resume, expectedVersion); VisibilityState prior=resume.getVisibilityState(); Instant at=clock.instant();
        resume.restore(at); repository.save(resume); cache.put(resume);
        audit.save(newAudit(resumeId,actorId,"RESTORED",prior,resume.getVisibilityState(),at)); return ResumeView.from(resume);
    }

    @Transactional
    public int archiveDue(Instant at) {
        int count=0;
        Page<Resume> due;
        do {
            due=repository.findActiveDue(at,PageRequest.of(0,100,Sort.by("id")));
            for (Resume resume : due) {
                if (resume.getVisibilityState()!=VisibilityState.ACTIVE) continue;
                VisibilityState prior=resume.getVisibilityState(); resume.archive(at); repository.save(resume); cache.evict(resume.getId());
                audit.save(newAudit(resume.getId(),null,"ARCHIVED",prior,resume.getVisibilityState(),at)); count++;
            }
        } while (due.hasContent());
        return count;
    }
    public Page<Resume> listActive(UUID actorId, UserRole role, Pageable pageable){ return role==UserRole.ADMIN ? repository.findByVisibilityStateIn(List.of(VisibilityState.ACTIVE),pageable) : repository.findByOwnerIdAndVisibilityState(actorId,VisibilityState.ACTIVE,pageable); }
    public Resume getActive(UUID id, UUID actorId, UserRole role){ return repository.findById(id).filter(r -> r.getVisibilityState()==VisibilityState.ACTIVE && (role==UserRole.ADMIN || r.getOwnerId().equals(actorId))).orElseThrow(ResourceNotFoundException::new); }
    public Page<Resume> listRecoverable(UUID actorId, UserRole role, UUID ownerId, Pageable pageable){
        var states=List.of(VisibilityState.USER_SOFT_DELETED,VisibilityState.ADMIN_SOFT_DELETED,VisibilityState.USER_CACHE_ARCHIVED,VisibilityState.ADMIN_CACHE_ARCHIVED);
        if (role==UserRole.USER) return repository.findByOwnerIdAndVisibilityStateIn(actorId,List.of(VisibilityState.USER_SOFT_DELETED,VisibilityState.USER_CACHE_ARCHIVED),pageable);
        return ownerId==null ? repository.findByVisibilityStateIn(states,pageable) : repository.findByOwnerIdAndVisibilityStateIn(ownerId,states,pageable);
    }
    @Transactional(readOnly=true)
    public ResumeAnalysisReservation reserveForAnalysis(UUID resumeId, UUID actorId, UserRole role){
        Resume resume=getActive(resumeId,actorId,role); return new ResumeAnalysisReservation(resume.getId(),resume.getVersion(),resume.getSourceType());
    }
    @Transactional(readOnly=true)
    public boolean isActiveAtVersion(UUID resumeId,long version){return repository.findById(resumeId).map(r -> r.getVisibilityState()==VisibilityState.ACTIVE && r.getVersion()==version).orElse(false);}
    private ResumeAuditRepository.ResumeLifecycleAudit newAudit(UUID resumeId,UUID actorId,String action,VisibilityState prior,VisibilityState next,Instant at){return new ResumeAuditRepository.ResumeLifecycleAudit(resumeId,actorId,action,prior,next,at,UUID.randomUUID());}
    private void requireVersion(Resume resume,long expected){if(resume.getVersion()!=expected) throw new VersionConflictException();}
}
