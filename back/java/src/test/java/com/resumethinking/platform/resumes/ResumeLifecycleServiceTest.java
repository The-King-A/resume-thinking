package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ResumeLifecycleServiceTest {
 private final UUID userId=UUID.randomUUID(), otherUserId=UUID.randomUUID(), adminId=UUID.randomUUID();
 private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
 private final ResumeRepository repo=new ResumeRepository.InMemory();
 private final ResumeCache cache=new ResumeCache.InMemory();
 private final ResumeAuditRepository audit=new ResumeAuditRepository.InMemory();
 private final ResumeLifecycleService lifecycleService=new ResumeLifecycleService(repo,cache,audit,Clock.fixed(now,ZoneOffset.UTC));

 @Test void userSoftDeleteRemovesCacheAndCanRecoverOnlyOwnRecord(){
  Resume resume=Resume.active(UUID.randomUUID(),userId,"CV",Resume.SourceType.TXT,UserRole.USER,now,2L); repo.save(resume); cache.put(resume);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,"确认删除简历",2L));
  assertThat(resume.getStatus()).isEqualTo(1); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED); assertThat(cache.get(resume.getId())).isEmpty();
  assertThatThrownBy(()->lifecycleService.recover(resume.getId(),otherUserId,UserRole.USER,3L)).isInstanceOf(ResourceNotFoundException.class);
  lifecycleService.recover(resume.getId(),userId,UserRole.USER,3L); assertThat(resume.getStatus()).isZero(); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
 }
  @Test void administratorSoftDeleteCannotBeRecoveredByOwner(){
  Resume resume=Resume.active(UUID.randomUUID(),userId,"CV",Resume.SourceType.TXT,UserRole.USER,now,1L); repo.save(resume);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),adminId,UserRole.ADMIN,"确认删除简历",1L));
  assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_SOFT_DELETED);
  assertThatThrownBy(()->lifecycleService.recover(resume.getId(),userId,UserRole.USER,2L)).isInstanceOf(ResourceNotFoundException.class);
   lifecycleService.recover(resume.getId(),adminId,UserRole.ADMIN,2L); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  }

  @Test void administratorCannotRestoreAnActiveRecordThroughRecovery(){
   Resume resume=Resume.active(UUID.randomUUID(),userId,"active",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(resume);
   assertThatThrownBy(()->lifecycleService.recover(resume.getId(),adminId,UserRole.ADMIN,0L))
       .isInstanceOf(ResourceNotFoundException.class);
  }
 @Test void archiveUsesCreatorRoleRetentionAndIsRecoverable(){
  Resume user=Resume.active(UUID.randomUUID(),userId,"u",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  Resume admin=Resume.active(UUID.randomUUID(),adminId,"a",Resume.SourceType.DOCX,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L); repo.save(user);repo.save(admin);cache.put(user);cache.put(admin);
  lifecycleService.archiveDue(now);
  assertThat(user.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED); assertThat(admin.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED); assertThat(cache.get(user.getId())).isEmpty();
 }
 @Test void wrongConfirmationAndStaleVersionRejected(){
  Resume r=Resume.active(UUID.randomUUID(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,4L);repo.save(r);
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"删除",4L))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",3L))).isInstanceOf(VersionConflictException.class);
 }

 @Test void softDeleteUsesTheSamePessimisticLookupAsCallbacks(){
  ResumeRepository lockedRepository=mock(ResumeRepository.class);
  Resume r=Resume.active(UUID.randomUUID(),userId,"locked",Resume.SourceType.TXT,UserRole.USER,now,0L);
  when(lockedRepository.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
  var service=new ResumeLifecycleService(lockedRepository,new ResumeCache.Noop(),new ResumeAuditRepository.InMemory(),Clock.fixed(now,ZoneOffset.UTC));
  service.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  verify(lockedRepository).findByIdForUpdate(r.getId());
  verify(lockedRepository,never()).findById(r.getId());
 }

 @Test void repeatedRestoreOfAuthorizedActiveRecordIsIdempotent(){
  Resume r=Resume.active(UUID.randomUUID(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  ResumeView first=lifecycleService.recover(r.getId(),userId,UserRole.USER,1L);
  ResumeView second=lifecycleService.recover(r.getId(),userId,UserRole.USER,999L);
  assertThat(second).isEqualTo(first);
 }

 @Test void lifecycleVersionChangesOnlyWhenRepositoryPersists(){
  Resume r=Resume.active(UUID.randomUUID(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  r.softDelete(userId,UserRole.USER,now);
  assertThat(r.getVersion()).isZero();
  repo.save(r);
  assertThat(r.getVersion()).isEqualTo(1L);
 }

 @Test void pagedQueriesApplyOwnerAndRoleFiltersBeforeReturningResults(){
  Resume mine=Resume.active(UUID.randomUUID(),userId,"mine",Resume.SourceType.TXT,UserRole.USER,now,0L);
  Resume other=Resume.active(UUID.randomUUID(),otherUserId,"other",Resume.SourceType.TXT,UserRole.USER,now,0L);
  repo.save(mine); repo.save(other);
  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,1)).getContent()).containsExactly(mine);
  lifecycleService.softDelete(new DeleteResumeCommand(mine.getId(),userId,UserRole.USER,"确认删除简历",0L));
  lifecycleService.softDelete(new DeleteResumeCommand(other.getId(),otherUserId,UserRole.USER,"确认删除简历",0L));
  assertThat(lifecycleService.listRecoverable(userId,UserRole.USER,null,PageRequest.of(0,20)).getContent()).containsExactly(mine);
  assertThat(lifecycleService.listRecoverable(adminId,UserRole.ADMIN,userId,PageRequest.of(0,20)).getContent()).containsExactly(mine);
 }

 @Test void analysisReservationIsInvalidatedByVersionOrVisibilityChange(){
  Resume r=Resume.active(UUID.randomUUID(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  var reservation=lifecycleService.reserveForAnalysis(r.getId(),userId,UserRole.USER);
  assertThat(reservation.resumeVersion()).isZero();
  assertThat(lifecycleService.isActiveAtVersion(r.getId(),reservation.resumeVersion())).isTrue();
  lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  assertThat(lifecycleService.isActiveAtVersion(r.getId(),reservation.resumeVersion())).isFalse();
 }

 @Test void ownerCannotDeleteAdministratorSoftDeletedOrArchivedRecords(){
  Resume softDeleted=Resume.active(UUID.randomUUID(),userId,"soft",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(softDeleted);
  lifecycleService.softDelete(new DeleteResumeCommand(softDeleted.getId(),adminId,UserRole.ADMIN,"确认删除简历",0L));
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(softDeleted.getId(),userId,UserRole.USER,"确认删除简历",1L))).isInstanceOf(ResourceNotFoundException.class);

  Resume archived=Resume.active(UUID.randomUUID(),userId,"archived",Resume.SourceType.TXT,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L); repo.save(archived);
  lifecycleService.archiveDue(now);
  assertThat(archived.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED);
   assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(archived.getId(),userId,UserRole.USER,"确认删除简历",1L))).isInstanceOf(ResourceNotFoundException.class);
 }

 @Test void activeReadsAndAnalysisReservationsExcludeTheVisibilityBoundary(){
  Resume visible=Resume.active(UUID.randomUUID(),userId,"visible",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(1)),0L);
  Resume boundary=Resume.active(UUID.randomUUID(),userId,"boundary",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(7)),0L);
  Resume adminExpired=Resume.active(UUID.randomUUID(),adminId,"admin-expired",Resume.SourceType.TXT,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L);
  repo.save(visible); repo.save(boundary); repo.save(adminExpired);

  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,20)).getContent()).containsExactly(visible);
  assertThat(lifecycleService.listActive(adminId,UserRole.ADMIN,PageRequest.of(0,20)).getContent()).containsExactly(visible);
  assertThatThrownBy(() -> lifecycleService.getActive(boundary.getId(),userId,UserRole.USER)).isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(() -> lifecycleService.reserveForAnalysis(boundary.getId(),userId,UserRole.USER)).isInstanceOf(ResourceNotFoundException.class);
  assertThat(lifecycleService.isActiveAtVersion(boundary.getId(),0L)).isFalse();
  assertThat(lifecycleService.lockActiveAtVersion(boundary.getId(),0L)).isEmpty();
  assertThat(lifecycleService.findActiveForAnalysis(boundary.getId(),0L)).isEmpty();
 }

 @Test void cacheMutationsRunOnlyAfterCommitAndCacheFailuresAreBestEffort(){
  RecordingCache recording = new RecordingCache();
  ResumeLifecycleService service = new ResumeLifecycleService(repo,recording,audit,Clock.fixed(now,ZoneOffset.UTC));
  Resume resume=Resume.active(UUID.randomUUID(),userId,"cache",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(resume);
  TransactionSynchronizationManager.initSynchronization();
  try {
   service.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,"确认删除简历",0L));
   assertThat(recording.events).isEmpty();
   fireAfterCommit();
   assertThat(recording.events).containsExactly("evict:resume:view:"+resume.getId());
  } finally {
   if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }

  RecordingCache failing = new RecordingCache(); failing.fail = true;
  ResumeLifecycleService failingService = new ResumeLifecycleService(repo,failing,audit,Clock.fixed(now,ZoneOffset.UTC));
  Resume second=Resume.active(UUID.randomUUID(),userId,"failure",Resume.SourceType.TXT,UserRole.USER,now,0L);
  repo.save(second);
  assertThatCode(() -> failingService.softDelete(new DeleteResumeCommand(second.getId(),userId,UserRole.USER,"确认删除简历",0L))).doesNotThrowAnyException();
  assertThat(second.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
 }

 @Test void restoreAndUploadCacheWritesAreAlsoDeferredUntilCommit(){
  RecordingCache recording = new RecordingCache();
  ResumeLifecycleService service = new ResumeLifecycleService(repo,recording,audit,Clock.fixed(now,ZoneOffset.UTC));
  Resume deleted=Resume.active(UUID.randomUUID(),userId,"restore",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(deleted);
  service.softDelete(new DeleteResumeCommand(deleted.getId(),userId,UserRole.USER,"确认删除简历",0L));
  recording.events.clear();
  TransactionSynchronizationManager.initSynchronization();
  try {
   service.recover(deleted.getId(),userId,UserRole.USER,1L);
   assertThat(recording.events).isEmpty();
   fireAfterCommit();
   assertThat(recording.events).containsExactly("put:"+deleted.getId());
  } finally {
   if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }

  recording.events.clear();
  TransactionSynchronizationManager.initSynchronization();
  try {
   Resume uploaded=service.upload(userId,UserRole.USER,"uploaded",Resume.SourceType.TXT,new byte[]{1},new byte[12]);
   assertThat(recording.events).isEmpty();
   fireAfterCommit();
   assertThat(recording.events).containsExactly("put:"+uploaded.getId());
  } finally {
   if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }
 }

 private static void fireAfterCommit(){
  for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCommit();
 }

 private static final class RecordingCache implements ResumeCache {
  final List<String> events = new ArrayList<>();
  boolean fail;
  public void put(Resume resume){ if (fail) throw new IllegalStateException("cache down"); events.add("put:"+resume.getId()); }
  public void evict(String key){ if (fail) throw new IllegalStateException("cache down"); events.add("evict:"+key); }
 }
}
