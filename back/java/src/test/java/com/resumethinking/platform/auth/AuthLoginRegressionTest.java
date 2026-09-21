package com.resumethinking.platform.auth;

import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthLoginRegressionTest {
    private static final String KEY = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";

    @Test
    void registrationAndLoginNormalizeIdentifiersButKeepPasswordExact() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator());

        var registered = service.register(new RegisterCommand("  alice  ", " Alice@Example.TEST ", "  Keep Spaces  ", UserRole.USER));

        assertThat(registered.id()).isEqualTo("user001");
        assertThat(registered.username()).isEqualTo("alice");
        assertThat(registered.email()).isEqualTo("alice@example.test");
        assertThat(repository.findById("user001").orElseThrow().getPasswordHash())
                .doesNotContain("Keep Spaces");

        assertThat(service.login(new LoginCommand(" alice ", "  Keep Spaces  ")).id())
                .isEqualTo("user001");
        assertThat(service.login(new LoginCommand(" ALICE@EXAMPLE.TEST ", "  Keep Spaces  ")).id())
                .isEqualTo("user001");
        assertThatThrownBy(() -> service.login(new LoginCommand("alice", "Keep Spaces")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void localPasswordResetReplacesTheStoredHashAndInvalidatesTheOldPassword() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator(),
                environment, true);
        service.register(new RegisterCommand("alice", "alice@example.test", "old-password-123", UserRole.USER));

        service.resetLocalPassword(new ResetPasswordCommand(" alice@example.TEST ", "replacement-password-123"), "127.0.0.1");

        assertThat(service.login(new LoginCommand("alice", "replacement-password-123"))).isNotNull();
        assertThatThrownBy(() -> service.login(new LoginCommand("alice", "old-password-123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknownAccountStillRunsOnePasswordHashCheckBeforeTheGenericRejection() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var service = new AuthService(repository, new PasswordHashCheckEncoder(), new JwtService(KEY), new InMemoryReadableIdGenerator());

        assertThatThrownBy(() -> service.login(new LoginCommand("not-a-real-user", "long-enough-password")))
                .isInstanceOf(PasswordHashCheckReached.class);
    }

    @Test
    void unknownAccountDummyHashRemainsAValidCostTenBcryptHash() {
        assertThat(AuthService.UNKNOWN_ACCOUNT_PASSWORD_HASH).startsWith("$2a$10$");
        assertThat(new BCryptPasswordEncoder().matches("resume-matching-unknown-account-dummy", AuthService.UNKNOWN_ACCOUNT_PASSWORD_HASH)).isTrue();
    }

    @Test
    void usernameAndEmailLoginsUseTheSameTwoRepositoryLookups() {
        var user = new User("user001", "alice", "alice@example.test", "hash", UserRole.USER);
        var repository = new LookupCountingUserRepository(user);
        var service = new AuthService(repository, new AlwaysMatchingPasswordEncoder(), new JwtService(KEY), new InMemoryReadableIdGenerator());

        assertThat(service.login(new LoginCommand("alice", "long-enough-password")).id()).isEqualTo("user001");
        assertThat(repository.usernameLookups).isEqualTo(1);
        assertThat(repository.emailLookups).isEqualTo(1);

        repository.resetLookupCounts();
        assertThat(service.login(new LoginCommand("alice@example.test", "long-enough-password")).id()).isEqualTo("user001");
        assertThat(repository.usernameLookups).isEqualTo(1);
        assertThat(repository.emailLookups).isEqualTo(1);
    }

    @Test
    void localPasswordResetIsUnavailableOutsideTheEnabledLocalLoopbackBoundary() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator(),
                environment, true);
        service.register(new RegisterCommand("alice", "alice@example.test", "old-password-123", UserRole.USER));

        assertThatThrownBy(() -> service.resetLocalPassword(
                new ResetPasswordCommand("alice", "replacement-password-123"), "192.168.1.20"))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void localPasswordResetIsUnavailableWhenTheFeatureFlagIsDisabled() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator(),
                environment, false);
        service.register(new RegisterCommand("alice", "alice@example.test", "old-password-123", UserRole.USER));

        assertThatThrownBy(() -> service.resetLocalPassword(
                new ResetPasswordCommand("alice", "replacement-password-123"), "127.0.0.1"))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void localPasswordResetIsUnavailableOutsideTheLocalProfile() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator(),
                new MockEnvironment(), true);
        service.register(new RegisterCommand("alice", "alice@example.test", "old-password-123", UserRole.USER));

        assertThatThrownBy(() -> service.resetLocalPassword(
                new ResetPasswordCommand("alice", "replacement-password-123"), "127.0.0.1"))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void localPasswordResetDoesNotDistinguishAnUnknownIdentifier() {
        var repository = new InMemoryRepositories.UserRepositoryStub();
        var encoder = new BCryptPasswordEncoder();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var service = new AuthService(repository, encoder, new JwtService(KEY), new InMemoryReadableIdGenerator(),
                environment, true);

        assertThatThrownBy(() -> service.resetLocalPassword(
                new ResetPasswordCommand("nobody", "replacement-password-123"), "127.0.0.1"))
                .isInstanceOf(com.resumethinking.platform.profiles.ResourceNotFoundException.class);
    }

    @Test
    void issuedJwtUsesReadableSubjectAndLegacyUuidSubjectIsRejected() {
        var jwt = new JwtService(KEY);
        var user = new User("user007", "bob", "bob@example.test", "hash", UserRole.ADMIN);

        String issued = jwt.issue(user);
        assertThat(jwt.parse(issued)).get().satisfies(claims -> {
            assertThat(claims.subject()).isEqualTo("user007");
            assertThat(claims.role()).isEqualTo(UserRole.ADMIN);
        });

        String legacyPayload = "{\"sub\":\"" + UUID.randomUUID()
                + "\",\"role\":\"USER\",\"iat\":1,\"exp\":4102444800}";
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode(legacyPayload);
        assertThat(jwt.parse(header + "." + payload + "." + sign(KEY, header + "." + payload)))
                .isEmpty();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String base64Key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class PasswordHashCheckReached extends RuntimeException {}

    private static final class PasswordHashCheckEncoder implements PasswordEncoder {
        @Override public String encode(CharSequence rawPassword) { return "unused"; }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { throw new PasswordHashCheckReached(); }
        @Override public boolean upgradeEncoding(String encodedPassword) { return false; }
    }

    private static final class AlwaysMatchingPasswordEncoder implements PasswordEncoder {
        @Override public String encode(CharSequence rawPassword) { return "unused"; }
        @Override public boolean matches(CharSequence rawPassword, String encodedPassword) { return true; }
        @Override public boolean upgradeEncoding(String encodedPassword) { return false; }
    }

    private static final class LookupCountingUserRepository implements UserRepository {
        private final User user;
        private int usernameLookups;
        private int emailLookups;

        private LookupCountingUserRepository(User user) { this.user = user; }

        @Override public User save(User value) { return value; }
        @Override public Optional<User> findById(String id) { return user.getId().equals(id) ? Optional.of(user) : Optional.empty(); }
        @Override public Optional<User> findByUsername(String username) {
            usernameLookups++;
            return user.getUsername().equals(username) ? Optional.of(user) : Optional.empty();
        }
        @Override public Optional<User> findByEmail(String email) {
            emailLookups++;
            return user.getEmail().equals(email) ? Optional.of(user) : Optional.empty();
        }
        private void resetLookupCounts() { usernameLookups = 0; emailLookups = 0; }
    }
}
