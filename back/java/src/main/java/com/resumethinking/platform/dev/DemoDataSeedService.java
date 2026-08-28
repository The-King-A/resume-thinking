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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DemoDataSeedService {
    private static final UUID USER_ACTIVE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_SOFT_DELETED = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID USER_ARCHIVED = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID USER_ADMIN_DELETED = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID ADMIN_ACTIVE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ARCHIVED = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AesGcmCryptoService crypto;
    private final ResumeRepository resumes;
    private final ResumeAuditRepository audits;
    private final ResumeCache cache;
    private final Clock clock;

    public DemoDataSeedService(UserRepository users, PasswordEncoder passwords, AesGcmCryptoService crypto,
                               ResumeRepository resumes, ResumeAuditRepository audits, ResumeCache cache, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.crypto = crypto;
        this.resumes = resumes;
        this.audits = audits;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public SeedResult seed(DemoCredentials credentials) {
        User user = verifyAccount("demo_user", "demo_user@local.invalid", UserRole.USER, credentials.userPassword());
        User admin = verifyAccount("demo_admin", "demo_admin@local.invalid", UserRole.ADMIN, credentials.adminPassword());
        List<ResumeSpec> specs = List.of(
                new ResumeSpec(USER_ACTIVE, user, "Anonymous local demo resume 1", UserRole.USER, Lifecycle.ACTIVE, null),
                new ResumeSpec(USER_SOFT_DELETED, user, "Anonymous local demo resume 2", UserRole.USER, Lifecycle.USER_SOFT_DELETED, user.getId()),
                new ResumeSpec(USER_ARCHIVED, user, "Anonymous local demo resume 3", UserRole.USER, Lifecycle.USER_CACHE_ARCHIVED, null),
                new ResumeSpec(USER_ADMIN_DELETED, user, "Anonymous local demo resume 4", UserRole.USER, Lifecycle.ADMIN_SOFT_DELETED, admin.getId()),
                new ResumeSpec(ADMIN_ACTIVE, admin, "Anonymous local demo resume 5", UserRole.ADMIN, Lifecycle.ACTIVE, null),
                new ResumeSpec(ADMIN_ARCHIVED, admin, "Anonymous local demo resume 6", UserRole.ADMIN, Lifecycle.ADMIN_CACHE_ARCHIVED, null));
        for (ResumeSpec spec : specs) verifyExistingResume(spec);

        if (users.findByUsername("demo_user").isEmpty()) users.save(user);
        if (users.findByUsername("demo_admin").isEmpty()) users.save(admin);

        int created = 0;
        for (ResumeSpec spec : specs) {
            Optional<Resume> existing = resumes.findById(spec.id());
            if (existing.isPresent()) {
                reconcileExistingResume(spec, existing.get());
                continue;
            }
            Resume resume = newResume(spec);
            resumes.save(resume);
            if (spec.lifecycle() == Lifecycle.ACTIVE) {
                cacheAfterCommit(() -> cache.put(resume));
            } else {
                cacheAfterCommit(() -> cache.evict(resume.getId()));
                audits.save(new ResumeAuditRepository.ResumeLifecycleAudit(resume.getId(), spec.lifecycleActorId(),
                        spec.lifecycle().auditAction(), VisibilityState.ACTIVE, resume.getVisibilityState(),
                        clock.instant(), UUID.randomUUID()));
            }
            created++;
        }
        return new SeedResult(created, specs.size() - created);
    }

    private void reconcileExistingResume(ResumeSpec spec, Resume resume) {
        if (spec.lifecycle() == Lifecycle.ACTIVE) {
            cacheAfterCommit(() -> cache.put(resume));
            return;
        }
        cacheAfterCommit(() -> cache.evict(resume.getId()));
        if (spec.lifecycleActorId() != null) {
            audits.backfillNullActorForLifecycleAudit(resume.getId(), spec.lifecycleActorId(),
                    spec.lifecycle().auditAction(), VisibilityState.ACTIVE, resume.getVisibilityState());
        }
    }

    private User verifyAccount(String username, String email, UserRole role, String password) {
        Optional<User> byUsername = users.findByUsername(username);
        Optional<User> byEmail = users.findByEmail(email);
        if (byUsername.isPresent() != byEmail.isPresent()
                || byUsername.isPresent() && !byUsername.get().getId().equals(byEmail.get().getId())) {
            throw new IllegalStateException("Conflicting local demo account");
        }
        if (byUsername.isEmpty()) return new User(username, email, passwords.encode(password), role);
        User account = byUsername.get();
        if (!account.getEmail().equals(email) || account.getRole() != role || !passwords.matches(password, account.getPasswordHash())) {
            throw new IllegalStateException("Conflicting local demo account");
        }
        return account;
    }

    private void verifyExistingResume(ResumeSpec spec) {
        resumes.findById(spec.id()).ifPresent(existing -> {
            if (!existing.getOwnerId().equals(spec.owner().getId()) || !existing.getTitle().equals(spec.title())
                    || existing.getSourceType() != Resume.SourceType.TXT || existing.getCreatorRole() != spec.creatorRole()
                    || existing.getStatus() != spec.lifecycle().status()
                    || existing.getVisibilityState() != spec.lifecycle().visibilityState()) {
                throw new IllegalStateException("Conflicting local demo resume");
            }
        });
    }

    private Resume newResume(ResumeSpec spec) {
        Instant now = clock.instant();
        Instant createdAt = switch (spec.lifecycle()) {
            case USER_CACHE_ARCHIVED -> now.minus(Duration.ofDays(8));
            case ADMIN_CACHE_ARCHIVED -> now.minus(Duration.ofDays(31));
            default -> now;
        };
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encrypt(spec.title());
        Resume resume = new Resume(spec.id(), spec.owner().getId(), spec.title(), Resume.SourceType.TXT,
                spec.creatorRole(), encrypted.ciphertext(), encrypted.nonce(), createdAt, "v1");
        if (spec.lifecycle() == Lifecycle.USER_SOFT_DELETED) resume.softDelete(spec.lifecycleActorId(), UserRole.USER, now);
        if (spec.lifecycle() == Lifecycle.ADMIN_SOFT_DELETED) resume.softDelete(spec.lifecycleActorId(), UserRole.ADMIN, now);
        if (spec.lifecycle() == Lifecycle.USER_CACHE_ARCHIVED || spec.lifecycle() == Lifecycle.ADMIN_CACHE_ARCHIVED) resume.archive(now);
        return resume;
    }

    private void cacheAfterCommit(Runnable mutation) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { runCacheMutation(mutation); }
            });
            return;
        }
        runCacheMutation(mutation);
    }

    private void runCacheMutation(Runnable mutation) {
        try {
            mutation.run();
        } catch (RuntimeException ignored) {
            // Cache is a derived local view and must not invalidate durable seed data.
        }
    }

    public record DemoCredentials(String userPassword, String adminPassword) {}
    public record SeedResult(int createdResumeCount, int reusedResumeCount) {}

    private record ResumeSpec(UUID id, User owner, String title, UserRole creatorRole, Lifecycle lifecycle,
                              UUID lifecycleActorId) {}

    private enum Lifecycle {
        ACTIVE(0, VisibilityState.ACTIVE, ""),
        USER_SOFT_DELETED(1, VisibilityState.USER_SOFT_DELETED, "SOFT_DELETED"),
        USER_CACHE_ARCHIVED(0, VisibilityState.USER_CACHE_ARCHIVED, "ARCHIVED"),
        ADMIN_SOFT_DELETED(1, VisibilityState.ADMIN_SOFT_DELETED, "SOFT_DELETED"),
        ADMIN_CACHE_ARCHIVED(0, VisibilityState.ADMIN_CACHE_ARCHIVED, "ARCHIVED");

        private final int status;
        private final VisibilityState visibilityState;
        private final String auditAction;

        Lifecycle(int status, VisibilityState visibilityState, String auditAction) {
            this.status = status;
            this.visibilityState = visibilityState;
            this.auditAction = auditAction;
        }

        int status() { return status; }
        VisibilityState visibilityState() { return visibilityState; }
        String auditAction() { return auditAction; }
    }
}
