package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.TestIds;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

class ResumeLifecycleServiceTest {
 private final String userId="user001", otherUserId="user002", adminId="user003";
 private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
 private final ResumeRepository repo=new ResumeRepository.InMemory();
 private final ResumeCache cache=new ResumeCache.InMemory();
 private final ResumeAuditRepository.InMemory audit=new ResumeAuditRepository.InMemory();
 private final ResumeLifecycleService lifecycleService=new ResumeLifecycleService(repo,cache,audit,Clock.fixed(now,ZoneOffset.UTC));

 @Test void pendingCandidateIsHiddenUntilItsRevisionIsPublished(){
  Resume pending=new Resume(TestIds.resume(),userId,"Pending",Resume.SourceType.TXT,UserRole.USER,new byte[0],now);
  ResumeRevision revision=new ResumeRevision("revision901",pending.getId(),1,"Pending","pending",
      Resume.SourceType.TXT,"v1",new byte[]{1},new byte[12],ResumeRevision.State.PENDING,now);
  pending.stageRevision(revision,now); repo.save(pending);

  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,20)).getContent()).isEmpty();

  revision.markEffective(); pending.publish(revision,now); repo.save(pending);
  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,20)).getContent())
      .extracting(Resume::getId).containsExactly(pending.getId());
 }

 @Test void softDeleteReleasesTitleButRestoreRejectsTheReusedTitle(){
  Resume original=published(TestIds.resume(),userId,"Java CV","revision902"); repo.save(original);
  lifecycleService.softDelete(new DeleteResumeCommand(original.getId(),userId,UserRole.USER,
      ResumeLifecycleService.CONFIRMATION,original.getVersion()));
  assertThat(original.getEffectiveTitleKey()).isNull();
  Resume replacement=published(TestIds.resume(),userId,"\u3000JAVA CV\u00a0","revision903"); repo.save(replacement);

  assertThatThrownBy(() -> lifecycleService.recover(original.getId(),userId,UserRole.USER,original.getVersion()))
      .isInstanceOf(DuplicateResumeTitleException.class);
  assertThat(original.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
  assertThat(replacement.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
 }

 @Test void unicodeEquivalentPendingTitleCannotPublishForTheSameOwner(){
  var revisions=new ResumeRevisionRepository.InMemory();
  var service=new ResumeLifecycleService(repo,cache,audit,Clock.fixed(now,ZoneOffset.UTC),
      ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator(),null,revisions);
  Resume existing=published(TestIds.resume(),userId,"Java CV","revision910"); repo.save(existing);
  Resume candidateResume=new Resume(TestIds.resume(),userId,"legacy",Resume.SourceType.TXT,UserRole.USER,
      new byte[0],now);
  ResumeRevision candidate=new ResumeRevision("revision911",candidateResume.getId(),1,"\u00a0JAVA CV\u3000",
      ResumeLifecycleService.normalizeTitle("\u00a0JAVA CV\u3000"),Resume.SourceType.TXT,"v1",new byte[]{1},
      new byte[12],ResumeRevision.State.PENDING,now);
  revisions.save(candidate); candidateResume.stageRevision(candidate,now); repo.save(candidateResume);

  assertThatThrownBy(() -> service.publishPendingRevision(candidateResume.getId(),candidate.getId()))
      .isInstanceOf(DuplicateResumeTitleException.class);
  assertThat(candidateResume.getEffectiveRevisionId()).isNull();
  assertThat(candidate.getState()).isEqualTo(ResumeRevision.State.PENDING);
 }

 @Test void titleNormalizationTrimsUnicodeSpaceCharactersAndUsesLocaleRootCase(){
  Locale prior=Locale.getDefault();
  try {
   Locale.setDefault(Locale.forLanguageTag("tr-TR"));
   assertThat(ResumeLifecycleService.normalizeTitle("\u3000\u00a0 JAVA CV\t\n"))
       .isEqualTo("java cv");
  } finally {
   Locale.setDefault(prior);
  }
 }

 @Test void effectiveTitleKeyIsReleasedAndRestoredWithLifecycleVisibility(){
  Resume resume=published(TestIds.resume(),userId,"\u3000JAVA CV\u00a0","revision912"); repo.save(resume);
  assertThat(resume.getEffectiveTitleKey()).isEqualTo("java cv");

  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,
      ResumeLifecycleService.CONFIRMATION,resume.getVersion()));
  assertThat(resume.getEffectiveTitleKey()).isNull();

  lifecycleService.recover(resume.getId(),userId,UserRole.USER,resume.getVersion());
  assertThat(resume.getEffectiveTitleKey()).isEqualTo("java cv");
 }

 @Test void effectiveListIsOwnerScopedWhileAdministratorsKeepCrossOwnerVisibility(){
  Resume mine=published(TestIds.resume(),userId,"Mine","revision904");
  Resume other=published(TestIds.resume(),otherUserId,"Other","revision905");
  repo.save(mine); repo.save(other);

  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,20)).getContent())
      .containsExactly(mine);
  assertThat(lifecycleService.listActive(adminId,UserRole.ADMIN,PageRequest.of(0,20)).getContent())
      .containsExactly(mine,other);
 }

 @Test void userSoftDeleteRemovesCacheAndCanRecoverOnlyOwnRecord(){
  Resume resume=Resume.active(TestIds.resume(),userId,"CV",Resume.SourceType.TXT,UserRole.USER,now,2L); repo.save(resume); cache.put(resume);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,"确认删除简历",2L));
  assertThat(resume.getStatus()).isEqualTo(1); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED); assertThat(cache.get(resume.getId())).isEmpty();
  assertThatThrownBy(()->lifecycleService.recover(resume.getId(),otherUserId,UserRole.USER,3L)).isInstanceOf(ResourceNotFoundException.class);
  lifecycleService.recover(resume.getId(),userId,UserRole.USER,3L); assertThat(resume.getStatus()).isZero(); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
 }
  @Test void administratorSoftDeleteCannotBeRecoveredByOwner(){
  Resume resume=Resume.active(TestIds.resume(),userId,"CV",Resume.SourceType.TXT,UserRole.USER,now,1L); repo.save(resume);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),adminId,UserRole.ADMIN,"确认删除简历",1L));
  assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_SOFT_DELETED);
  assertThatThrownBy(()->lifecycleService.recover(resume.getId(),userId,UserRole.USER,2L)).isInstanceOf(ResourceNotFoundException.class);
   lifecycleService.recover(resume.getId(),adminId,UserRole.ADMIN,2L); assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  }

  @Test void administratorSoftDeleteSupersedesAnExistingUserDeletion(){
  Resume resume=Resume.active(TestIds.resume(),userId,"CV",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(resume);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,"确认删除简历",0L));
  assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
  lifecycleService.softDelete(new DeleteResumeCommand(resume.getId(),adminId,UserRole.ADMIN,"确认删除简历",1L));
  assertThat(resume.getStatus()).isEqualTo(1);
  assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_SOFT_DELETED);
  assertThatThrownBy(() -> lifecycleService.recover(resume.getId(),userId,UserRole.USER,2L))
      .isInstanceOf(ResourceNotFoundException.class);
  lifecycleService.recover(resume.getId(),adminId,UserRole.ADMIN,2L);
  assertThat(resume.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  }

  @Test void administratorCannotRestoreAnActiveRecordThroughRecovery(){
   Resume resume=Resume.active(TestIds.resume(),userId,"active",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(resume);
   assertThatThrownBy(()->lifecycleService.recover(resume.getId(),adminId,UserRole.ADMIN,0L))
       .isInstanceOf(ResourceNotFoundException.class);
  }

 @Test void recoveryUsesPessimisticLookupAndNeverTheUnlockedLookup(){
  ResumeRepository lockedRepository=mock(ResumeRepository.class);
  Resume resume=Resume.active(TestIds.resume(),userId,"locked-recovery",Resume.SourceType.TXT,UserRole.USER,now,0L);
  resume.softDelete(userId,UserRole.USER,now);
  when(lockedRepository.findByIdForUpdate(resume.getId())).thenReturn(Optional.of(resume));
  var service=new ResumeLifecycleService(lockedRepository,new ResumeCache.Noop(),new ResumeAuditRepository.InMemory(),Clock.fixed(now,ZoneOffset.UTC));

  ResumeView restored=service.recover(resume.getId(),userId,UserRole.USER,0L);

  assertThat(restored.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  verify(lockedRepository).findByIdForUpdate(resume.getId());
  verify(lockedRepository,never()).findRecoverable(resume.getId(),userId,UserRole.USER);
  verify(lockedRepository,never()).findById(resume.getId());
 }

 @Test void expiredActiveUserResumeCannotBypassArchiveThroughRecovery(){
  Resume expired=Resume.active(TestIds.resume(),userId,"expired",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  repo.save(expired);

  assertThat(expired.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
  assertThat(expired.getVisibleUntil()).isBefore(now);
  assertThatThrownBy(() -> lifecycleService.recover(expired.getId(),userId,UserRole.USER,0L))
      .isInstanceOf(ResourceNotFoundException.class);
  assertThat(expired.getVisibilityState()).isEqualTo(VisibilityState.ACTIVE);
 }
 @Test void archiveUsesCreatorRoleRetentionAndIsRecoverable(){
  Resume user=Resume.active(TestIds.resume(),userId,"u",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  Resume admin=Resume.active(TestIds.resume(),adminId,"a",Resume.SourceType.DOCX,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L); repo.save(user);repo.save(admin);cache.put(user);cache.put(admin);
  lifecycleService.archiveDue(now);
  assertThat(user.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED); assertThat(admin.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED); assertThat(cache.get(user.getId())).isEmpty();
 }
 @Test void wrongConfirmationAndStaleVersionRejected(){
  Resume r=Resume.active(TestIds.resume(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,4L);repo.save(r);
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"删除",4L))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",3L))).isInstanceOf(VersionConflictException.class);
 }

 @Test void softDeleteUsesTheSamePessimisticLookupAsCallbacks(){
  ResumeRepository lockedRepository=mock(ResumeRepository.class);
  Resume r=Resume.active(TestIds.resume(),userId,"locked",Resume.SourceType.TXT,UserRole.USER,now,0L);
  when(lockedRepository.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
  var service=new ResumeLifecycleService(lockedRepository,new ResumeCache.Noop(),new ResumeAuditRepository.InMemory(),Clock.fixed(now,ZoneOffset.UTC));
  service.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  verify(lockedRepository).findByIdForUpdate(r.getId());
  verify(lockedRepository,never()).findById(r.getId());
 }

 @Test void repeatedRestoreOfAuthorizedActiveRecordIsIdempotent(){
  Resume r=Resume.active(TestIds.resume(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  ResumeView first=lifecycleService.recover(r.getId(),userId,UserRole.USER,1L);
  ResumeView second=lifecycleService.recover(r.getId(),userId,UserRole.USER,999L);
  assertThat(second).isEqualTo(first);
 }

 @Test void restoreViewReportsTheVersionAfterPersistenceCommit(){
  ResumeRepository lockedRepository=mock(ResumeRepository.class);
  EntityManager entityManager=mock(EntityManager.class);
  Resume r=Resume.active(TestIds.resume(),userId,"commit-version",Resume.SourceType.TXT,UserRole.USER,now,1L);
  r.softDelete(userId,UserRole.USER,now);
  when(lockedRepository.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
  when(lockedRepository.save(r)).thenReturn(r);
  doAnswer(invocation -> { r.advanceVersionForPersistence(); return null; }).when(entityManager).flush();
  var service=new ResumeLifecycleService(lockedRepository,new ResumeCache.Noop(),new ResumeAuditRepository.InMemory(),Clock.fixed(now,ZoneOffset.UTC),ResumeTaskBlocker.NOOP,new InMemoryReadableIdGenerator(),entityManager);

  ResumeView restored=service.recover(r.getId(),userId,UserRole.USER,1L);

  assertThat(restored.version()).isEqualTo(r.getVersion());
 }

 @Test void lifecycleVersionChangesOnlyWhenRepositoryPersists(){
  Resume r=Resume.active(TestIds.resume(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  r.softDelete(userId,UserRole.USER,now);
  assertThat(r.getVersion()).isZero();
  repo.save(r);
  assertThat(r.getVersion()).isEqualTo(1L);
 }

 @Test void pagedQueriesApplyOwnerAndRoleFiltersBeforeReturningResults(){
  Resume mine=effective(Resume.active(TestIds.resume(),userId,"mine",Resume.SourceType.TXT,UserRole.USER,now,0L));
  Resume other=effective(Resume.active(TestIds.resume(),otherUserId,"other",Resume.SourceType.TXT,UserRole.USER,now,0L));
  repo.save(mine); repo.save(other);
  assertThat(lifecycleService.listActive(userId,UserRole.USER,PageRequest.of(0,1)).getContent()).containsExactly(mine);
  lifecycleService.softDelete(new DeleteResumeCommand(mine.getId(),userId,UserRole.USER,"确认删除简历",0L));
  lifecycleService.softDelete(new DeleteResumeCommand(other.getId(),otherUserId,UserRole.USER,"确认删除简历",0L));
  assertThat(lifecycleService.listRecoverable(userId,UserRole.USER,null,PageRequest.of(0,20)).getContent()).containsExactly(mine);
  assertThat(lifecycleService.listRecoverable(adminId,UserRole.ADMIN,userId,PageRequest.of(0,20)).getContent()).containsExactly(mine);
 }

 @Test void analysisReservationIsInvalidatedByVersionOrVisibilityChange(){
  Resume r=Resume.active(TestIds.resume(),userId,"x",Resume.SourceType.TXT,UserRole.USER,now,0L);repo.save(r);
  var reservation=lifecycleService.reserveForAnalysis(r.getId(),userId,UserRole.USER);
  assertThat(reservation.resumeVersion()).isZero();
  assertThat(lifecycleService.isActiveAtVersion(r.getId(),reservation.resumeVersion())).isTrue();
  lifecycleService.softDelete(new DeleteResumeCommand(r.getId(),userId,UserRole.USER,"确认删除简历",0L));
  assertThat(lifecycleService.isActiveAtVersion(r.getId(),reservation.resumeVersion())).isFalse();
 }

 @Test void ownerCannotDeleteAdministratorSoftDeletedOrArchivedRecords(){
  Resume softDeleted=Resume.active(TestIds.resume(),userId,"soft",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(softDeleted);
  lifecycleService.softDelete(new DeleteResumeCommand(softDeleted.getId(),adminId,UserRole.ADMIN,"确认删除简历",0L));
  assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(softDeleted.getId(),userId,UserRole.USER,"确认删除简历",1L))).isInstanceOf(ResourceNotFoundException.class);

  Resume archived=Resume.active(TestIds.resume(),userId,"archived",Resume.SourceType.TXT,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L); repo.save(archived);
  lifecycleService.archiveDue(now);
  assertThat(archived.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED);
   assertThatThrownBy(()->lifecycleService.softDelete(new DeleteResumeCommand(archived.getId(),userId,UserRole.USER,"确认删除简历",1L))).isInstanceOf(ResourceNotFoundException.class);
 }

 @Test void cacheArchivedRecordsCannotBeSoftDeletedByEitherRole(){
  Resume userArchived=Resume.active(TestIds.resume(),userId,"user-archived",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  Resume adminArchived=Resume.active(TestIds.resume(),adminId,"admin-archived",Resume.SourceType.TXT,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L);
  repo.save(userArchived); repo.save(adminArchived);
  lifecycleService.archiveDue(now);
  assertThat(userArchived.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED);
  assertThat(adminArchived.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED);
  int userStatus=userArchived.getStatus(), adminStatus=adminArchived.getStatus();
  long userVersion=userArchived.getVersion(), adminVersion=adminArchived.getVersion();

  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      userArchived.getId(),userId,UserRole.USER,"确认删除简历",userVersion)))
      .isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      userArchived.getId(),adminId,UserRole.ADMIN,"确认删除简历",userVersion)))
      .isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      adminArchived.getId(),adminId,UserRole.ADMIN,"确认删除简历",adminVersion)))
      .isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      adminArchived.getId(),userId,UserRole.USER,"确认删除简历",adminVersion)))
      .isInstanceOf(ResourceNotFoundException.class);

  assertThat(userArchived.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED);
  assertThat(adminArchived.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED);
  assertThat(userArchived.getStatus()).isEqualTo(userStatus);
  assertThat(adminArchived.getStatus()).isEqualTo(adminStatus);
  assertThat(userArchived.getVersion()).isEqualTo(userVersion);
  assertThat(adminArchived.getVersion()).isEqualTo(adminVersion);
 }

 @Test void expiredUserDeleteArchivesAndReturnsNotFoundBeforeSchedulerRuns(){
  Resume expired=Resume.active(TestIds.resume(),userId,"expired-user",Resume.SourceType.TXT,
      UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  repo.save(expired); cache.put(expired);

  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      expired.getId(),userId,UserRole.USER,"确认删除简历",0L)))
      .isInstanceOf(ResourceNotFoundException.class);

  assertThat(expired.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED);
  assertThat(expired.getStatus()).isZero();
  assertThat(cache.get(expired.getId())).isEmpty();
  assertThat(audit.all()).anySatisfy(event -> {
    assertThat(event.resumeId()).isEqualTo(expired.getId());
    assertThat(event.action()).isEqualTo("ARCHIVED");
  });
 }

 @Test void expiredAdminDeleteArchivesAndReturnsNotFoundBeforeSchedulerRuns(){
  Resume expired=Resume.active(TestIds.resume(),adminId,"expired-admin",Resume.SourceType.TXT,
      UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L);
  repo.save(expired); cache.put(expired);

  assertThatThrownBy(() -> lifecycleService.softDelete(new DeleteResumeCommand(
      expired.getId(),adminId,UserRole.ADMIN,"确认删除简历",0L)))
      .isInstanceOf(ResourceNotFoundException.class);

  assertThat(expired.getVisibilityState()).isEqualTo(VisibilityState.ADMIN_CACHE_ARCHIVED);
  assertThat(expired.getStatus()).isZero();
  assertThat(cache.get(expired.getId())).isEmpty();
  assertThat(audit.all()).anySatisfy(event -> {
    assertThat(event.resumeId()).isEqualTo(expired.getId());
    assertThat(event.action()).isEqualTo("ARCHIVED");
  });
 }

 @Test void activeReadsAndAnalysisReservationsExcludeTheVisibilityBoundary(){
  Resume visible=effective(Resume.active(TestIds.resume(),userId,"visible",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(1)),0L));
  Resume boundary=Resume.active(TestIds.resume(),userId,"boundary",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(7)),0L);
  Resume adminExpired=Resume.active(TestIds.resume(),adminId,"admin-expired",Resume.SourceType.TXT,UserRole.ADMIN,now.minus(Duration.ofDays(31)),0L);
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
  Resume resume=Resume.active(TestIds.resume(),userId,"cache",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(resume);
  TransactionSynchronizationManager.initSynchronization();
  try {
   service.softDelete(new DeleteResumeCommand(resume.getId(),userId,UserRole.USER,"确认删除简历",0L));
   assertThat(recording.events).isEmpty();
   fireAfterCommit();
   assertThat(recording.events).containsExactly("evict:"+resume.getId());
  } finally {
   if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }

  RecordingCache failing = new RecordingCache(); failing.fail = true;
  ResumeLifecycleService failingService = new ResumeLifecycleService(repo,failing,audit,Clock.fixed(now,ZoneOffset.UTC));
  Resume second=Resume.active(TestIds.resume(),userId,"failure",Resume.SourceType.TXT,UserRole.USER,now,0L);
  repo.save(second);
  assertThatCode(() -> failingService.softDelete(new DeleteResumeCommand(second.getId(),userId,UserRole.USER,"确认删除简历",0L))).doesNotThrowAnyException();
  assertThat(second.getVisibilityState()).isEqualTo(VisibilityState.USER_SOFT_DELETED);
 }

 @Test void restoreAndUploadCacheWritesAreAlsoDeferredUntilCommit(){
  RecordingCache recording = new RecordingCache();
  ResumeLifecycleService service = new ResumeLifecycleService(repo,recording,audit,Clock.fixed(now,ZoneOffset.UTC));
  Resume deleted=Resume.active(TestIds.resume(),userId,"restore",Resume.SourceType.TXT,UserRole.USER,now,0L); repo.save(deleted);
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

 private Resume effective(Resume resume) {
  ResumeRevision revision=new ResumeRevision(new InMemoryReadableIdGenerator().next(com.resumethinking.platform.ids.BusinessIdType.REVISION),
      resume.getId(),1,resume.getTitle(),ResumeLifecycleService.normalizeTitle(resume.getTitle()),resume.getSourceType(),
      "v1",new byte[]{1},new byte[12],ResumeRevision.State.EFFECTIVE,now);
  resume.publish(revision,now);
  return resume;
 }

 @Test void archivePersistsAuditInTransactionAndEvictsCacheOnlyAfterCommit(){
  RecordingCache recording = new RecordingCache();
  ResumeAuditRepository.InMemory recordingAudit = new ResumeAuditRepository.InMemory();
  ResumeLifecycleService service = new ResumeLifecycleService(repo,recording,recordingAudit,Clock.fixed(now,ZoneOffset.UTC));
  Resume expired=Resume.active(TestIds.resume(),userId,"archive-transaction",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  repo.save(expired);
  recording.put(expired);
  TransactionSynchronizationManager.initSynchronization();
  try {
   assertThat(service.archiveDue(now)).isEqualTo(1);
   assertThat(expired.getVisibilityState()).isEqualTo(VisibilityState.USER_CACHE_ARCHIVED);
   assertThat(recordingAudit.all()).hasSize(1);
   assertThat(recording.events).containsExactly("put:"+expired.getId());
   // The initial put happened before the transaction; eviction is deferred.
   assertThat(recording.events).doesNotContain("evict:"+expired.getId());
   fireAfterCommit();
   assertThat(recording.events).containsExactly("put:"+expired.getId(),"evict:"+expired.getId());
  } finally {
   if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }
 }

 @Test void archiveDueRetainsTransactionalBoundary() throws NoSuchMethodException{
  assertThat(ResumeLifecycleService.class.getMethod("archiveDue",Instant.class)
      .isAnnotationPresent(Transactional.class)).isTrue();
 }

 private static void fireAfterCommit(){
  for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCommit();
 }

 private Resume published(String id,String owner,String title,String revisionId){
  Resume resume=new Resume(id,owner,"legacy",Resume.SourceType.TXT,UserRole.USER,new byte[0],now);
  ResumeRevision revision=new ResumeRevision(revisionId,id,1,title,ResumeLifecycleService.normalizeTitle(title),
      Resume.SourceType.TXT,"v1",new byte[]{1},new byte[12],ResumeRevision.State.EFFECTIVE,now);
  resume.publish(revision,now);
  return resume;
 }

 private static final class RecordingCache implements ResumeCache {
  final List<String> events = new ArrayList<>();
  boolean fail;
  public void put(Resume resume){ if (fail) throw new IllegalStateException("cache down"); events.add("put:"+resume.getId()); }
  public void evict(String key){ if (fail) throw new IllegalStateException("cache down"); events.add("evict:"+key); }
 }
}
