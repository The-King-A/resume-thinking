package com.resumethinking.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {
    @Test
    void registersSelectedAdminRoleWithoutPersistingPlaintextPassword() {
        var repository = new InMemoryUserRepository();
        var authService = new AuthService(repository, new BCryptPasswordEncoder(), new JwtService("test-signing-key-test-signing-key-test-signing-key"));

        var result = authService.register(new RegisterCommand("admin1", "admin1@example.test", "StrongPassphrase1", UserRole.ADMIN));

        assertThat(result.role()).isEqualTo(UserRole.ADMIN);
        assertThat(repository.findById(result.id()).orElseThrow().getPasswordHash()).doesNotContain("StrongPassphrase1");
    }

    private static final class InMemoryUserRepository extends InMemoryRepositories.UserRepositoryStub {
        @Override
        public User save(User user) { return super.save(user); }
    }
}
