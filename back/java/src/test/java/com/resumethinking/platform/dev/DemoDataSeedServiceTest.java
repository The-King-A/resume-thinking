package com.resumethinking.platform.dev;

import com.resumethinking.platform.auth.User;
import com.resumethinking.platform.auth.UserRepository;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.resumes.Resume;
import com.resumethinking.platform.resumes.ResumeAuditRepository;
import com.resumethinking.platform.resumes.ResumeCache;
import com.resumethinking.platform.resumes.ResumeRepository;
import com.resumethinking.platform.resumes.VisibilityState;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoDataSeedServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final ResumeRepository.InMemory resumes = new ResumeRepository.InMemory();
    private final ResumeAuditRepository.InMemory audits = new ResumeAuditRepository.InMemory();
    private final RecordingCache cache = new RecordingCache();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final AesGcmCryptoService crypto = new AesGcmCryptoService(Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII)));
    private final DemoDataSeedService service = new DemoDataSeedService(users, passwords, crypto, resumes, audits,
            cache, Clock.fixed(NOW, ZoneOffset.UTC));
    private final DemoDataSeedService.DemoCredentials credentials =
            new DemoDataSeedService.DemoCredentials("user-local-password", "admin-local-password");

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void seedsTwoBcryptAccountsAndSixEncryptedAnonymousTextResumes() {
        DemoDataSeedService.SeedResult result = service.seed(credentials);

        assertThat(result.createdResumeCount()).isEqualTo(6);
        assertThat(result.reusedResumeCount()).isZero();
        assertThat(users.findByUsername("demo_user")).hasValueSatisfying(user -> {
            assertThat(user.getEmail()).isEqualTo("demo_user@local.invalid");
            assertThat(user.getRole()).isEqualTo(UserRole.USER);
            assertThat(passwords.matches(credentials.userPassword(), user.getPasswordHash())).isTrue();
        });
        assertThat(users.findByUsername("demo_admin")).hasValueSatisfying(user -> {
            assertThat(user.getEmail()).isEqualTo("demo_admin@local.invalid");
            assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
            assertThat(passwords.matches(credentials.adminPassword(), user.getPasswordHash())).isTrue();
        });
        assertThat(allResumes()).hasSize(6).allSatisfy(resume -> {
            assertThat(resume.getSourceType()).isEqualTo(Resume.SourceType.TXT);
            assertThat(resume.getRawContentNonce()).hasSize(12);
            assertThat(resume.getEncryptedRawContent()).isNotEmpty();
        });
    }

    @Test
    void leavesOnlyActiveResumesCachedAndAuditsEveryHiddenLifecycleRow() {
        service.seed(credentials);

        List<Resume> active = resumes.findByVisibilityStateIn(List.of(VisibilityState.ACTIVE), PageRequest.of(0, 20)).getContent();
        assertThat(active).hasSize(2);
        assertThat(audits.all()).hasSize(4);
        assertThat(allResumes()).filteredOn(resume -> resume.getVisibilityState() != VisibilityState.ACTIVE)
                .allSatisfy(resume -> assertThat(cache.get(resume.getId())).isEmpty());
        assertThat(active).allSatisfy(resume -> assertThat(cache.get(resume.getId())).contains(resume));
    }

    @Test
    void recordsSoftDeleteActorsAndLeavesArchiveAuditActorsNull() {
        service.seed(credentials);

        assertThat(audits.all()).filteredOn(audit -> audit.newVisibilityState()
                        == VisibilityState.USER_SOFT_DELETED)
                .singleElement()
                .satisfies(audit -> assertThat(audit.actorId())
                        .isEqualTo(resume(audit.resumeId()).getSoftDeletedBy()));
        assertThat(audits.all()).filteredOn(audit -> audit.newVisibilityState()
                        == VisibilityState.ADMIN_SOFT_DELETED)
                .singleElement()
                .satisfies(audit -> assertThat(audit.actorId())
                        .isEqualTo(resume(audit.resumeId()).getSoftDeletedBy()));
        assertThat(audits.all()).filteredOn(audit -> audit.newVisibilityState()
                        == VisibilityState.USER_CACHE_ARCHIVED
                        || audit.newVisibilityState() == VisibilityState.ADMIN_CACHE_ARCHIVED)
                .allSatisfy(audit -> assertThat(audit.actorId()).isNull());
    }

    @Test
    void archivedSamplesHaveElapsedTheirVisibilityRetentionWindows() {
        service.seed(credentials);

        assertThat(allResumes()).filteredOn(resume -> resume.getVisibilityState() == VisibilityState.USER_CACHE_ARCHIVED
                        || resume.getVisibilityState() == VisibilityState.ADMIN_CACHE_ARCHIVED)
                .hasSize(2)
                .allSatisfy(resume -> assertThat(resume.getVisibleUntil()).isBeforeOrEqualTo(NOW));
    }

    @Test
    void encryptsAsciiContentInsteadOfPersistingPlaintext() {
        service.seed(credentials);

        assertThat(allResumes()).allSatisfy(resume -> {
            String plaintext = crypto.decrypt(resume.getEncryptedRawContent(), resume.getRawContentNonce());
            assertThat(plaintext).startsWith("Anonymous local demo resume ");
            assertThat(new String(resume.getEncryptedRawContent(), StandardCharsets.US_ASCII)).doesNotContain(plaintext);
        });
    }

    @Test
    void matchingRerunReconcilesCacheAndBackfillsOnlyMissingSoftDeleteActor() {
        service.seed(credentials);
        List<Resume> active = allResumes().stream()
                .filter(resume -> resume.getVisibilityState() == VisibilityState.ACTIVE)
                .toList();
        List<Resume> hidden = allResumes().stream()
                .filter(resume -> resume.getVisibilityState() != VisibilityState.ACTIVE)
                .toList();
        active.forEach(resume -> cache.values.remove(resume.getId()));
        hidden.forEach(cache::put);

        int userSoftDeleteIndex = auditIndex(VisibilityState.USER_SOFT_DELETED);
        ResumeAuditRepository.ResumeLifecycleAudit userSoftDelete = audits.all().get(userSoftDeleteIndex);
        audits.all().set(userSoftDeleteIndex, new ResumeAuditRepository.ResumeLifecycleAudit(
                userSoftDelete.resumeId(), null, userSoftDelete.action(), userSoftDelete.priorVisibilityState(),
                userSoftDelete.newVisibilityState(), userSoftDelete.occurredAt(), userSoftDelete.correlationId()));
        int adminSoftDeleteIndex = auditIndex(VisibilityState.ADMIN_SOFT_DELETED);
        ResumeAuditRepository.ResumeLifecycleAudit adminSoftDelete = audits.all().get(adminSoftDeleteIndex);
        UUID preservedAdminActor = UUID.randomUUID();
        assertThat(preservedAdminActor).isNotEqualTo(resume(adminSoftDelete.resumeId()).getSoftDeletedBy());
        audits.all().set(adminSoftDeleteIndex, new ResumeAuditRepository.ResumeLifecycleAudit(
                adminSoftDelete.resumeId(), preservedAdminActor, adminSoftDelete.action(),
                adminSoftDelete.priorVisibilityState(), adminSoftDelete.newVisibilityState(),
                adminSoftDelete.occurredAt(), adminSoftDelete.correlationId()));
        int auditCount = audits.all().size();

        DemoDataSeedService.SeedResult result = service.seed(credentials);

        assertThat(result.createdResumeCount()).isZero();
        assertThat(result.reusedResumeCount()).isEqualTo(6);
        assertThat(allResumes()).hasSize(6);
        assertThat(audits.all()).hasSize(auditCount);
        assertThat(audits.all().get(userSoftDeleteIndex).actorId())
                .isEqualTo(resume(userSoftDelete.resumeId()).getSoftDeletedBy());
        assertThat(audits.all().get(adminSoftDeleteIndex).actorId()).isEqualTo(preservedAdminActor);
        assertThat(cache.values).containsOnlyKeys(active.get(0).getId(), active.get(1).getId());
        assertThat(hidden).allSatisfy(resume -> assertThat(cache.get(resume.getId())).isEmpty());
    }

    @Test
    void defersExistingRerunCacheReconciliationUntilTransactionCommit() {
        service.seed(credentials);
        List<Resume> active = allResumes().stream()
                .filter(resume -> resume.getVisibilityState() == VisibilityState.ACTIVE)
                .toList();
        List<Resume> hidden = allResumes().stream()
                .filter(resume -> resume.getVisibilityState() != VisibilityState.ACTIVE)
                .toList();
        active.forEach(resume -> cache.values.remove(resume.getId()));
        hidden.forEach(cache::put);
        int cacheEventsBeforeRerun = cache.events.size();

        TransactionSynchronizationManager.initSynchronization();
        service.seed(credentials);

        assertThat(cache.events).hasSize(cacheEventsBeforeRerun);
        assertThat(active).allSatisfy(resume -> assertThat(cache.get(resume.getId())).isEmpty());
        assertThat(hidden).allSatisfy(resume -> assertThat(cache.get(resume.getId())).contains(resume));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        assertThat(cache.values).containsOnlyKeys(active.get(0).getId(), active.get(1).getId());
        assertThat(hidden).allSatisfy(resume -> assertThat(cache.get(resume.getId())).isEmpty());
    }

    @Test
    void conflictingSuppliedPasswordFailsWithoutMutation() {
        service.seed(credentials);
        List<Resume> before = List.copyOf(allResumes());
        int auditCount = audits.all().size();
        int cacheEvents = cache.events.size();

        assertThatThrownBy(() -> service.seed(new DemoDataSeedService.DemoCredentials(
                "different-password", credentials.adminPassword()))).isInstanceOf(IllegalStateException.class);

        assertThat(allResumes()).containsExactlyElementsOf(before);
        assertThat(audits.all()).hasSize(auditCount);
        assertThat(cache.events).hasSize(cacheEvents);
        assertThat(passwords.matches(credentials.userPassword(), users.findByUsername("demo_user").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void defersActiveCachePublicationUntilTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        service.seed(credentials);

        assertThat(cache.events).isEmpty();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        assertThat(cache.events).hasSize(6);
        assertThat(cache.events).filteredOn(event -> event.startsWith("put:")).hasSize(2);
        assertThat(cache.events).filteredOn(event -> event.startsWith("evict:")).hasSize(4);
    }

    private List<Resume> allResumes() {
        return resumes.findByVisibilityStateIn(List.of(VisibilityState.values()), PageRequest.of(0, 20)).getContent();
    }

    private Resume resume(UUID id) {
        return resumes.findById(id).orElseThrow();
    }

    private int auditIndex(VisibilityState state) {
        return java.util.stream.IntStream.range(0, audits.all().size())
                .filter(index -> audits.all().get(index).newVisibilityState() == state)
                .findFirst()
                .orElseThrow();
    }

    private static final class InMemoryUserRepository implements UserRepository {
        private final Map<UUID, User> values = new HashMap<>();

        @Override public User save(User user) { values.put(user.getId(), user); return user; }
        @Override public Optional<User> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<User> findByUsername(String username) {
            return values.values().stream().filter(user -> user.getUsername().equals(username)).findFirst();
        }
        @Override public Optional<User> findByEmail(String email) {
            return values.values().stream().filter(user -> user.getEmail().equals(email)).findFirst();
        }
    }

    private static final class RecordingCache implements ResumeCache {
        private final Map<UUID, Resume> values = new HashMap<>();
        private final List<String> events = new ArrayList<>();

        @Override public void put(Resume resume) { values.put(resume.getId(), resume); events.add("put:" + resume.getId()); }
        @Override public void evict(String key) { values.remove(UUID.fromString(key.substring("resume:view:".length()))); events.add("evict:" + key); }
        @Override public Optional<Resume> get(UUID id) { return Optional.ofNullable(values.get(id)); }
    }
}
