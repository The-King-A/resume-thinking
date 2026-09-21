package com.resumethinking.platform.auth;

import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import java.net.InetAddress;
import java.util.Locale;

@Service
public class AuthService {
    // Fixed BCrypt cost 10 hash for an impossible account path; never a user credential.
    static final String UNKNOWN_ACCOUNT_PASSWORD_HASH = "$2a$10$yYbXPWltdk23v0tissexlui4OM9fRfaeX059Sd91/1aTUZIh3ED2u";
    private final UserRepository users; private final PasswordEncoder encoder; private final JwtService jwt; private final ReadableIdGenerator ids; private final Environment environment; private final boolean localPasswordResetEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, ReadableIdGenerator ids,
                       Environment environment, @Value("${app.local-password-reset-enabled:false}") boolean localPasswordResetEnabled) {
        this.users = users; this.encoder = encoder; this.jwt = jwt; this.ids = ids; this.environment = environment; this.localPasswordResetEnabled = localPasswordResetEnabled;
    }
    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, ReadableIdGenerator ids) { this(users, encoder, jwt, ids, new org.springframework.core.env.StandardEnvironment(), false); }
    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) { this(users, encoder, jwt, new InMemoryReadableIdGenerator()); }
    @Transactional
    public AuthResult register(RegisterCommand command) {
        if (command == null || command.password() == null || command.password().isBlank() || command.role() == null) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
        String username = normalizeUsername(command.username());
        String email = normalizeEmail(command.email());
        if (users.findByUsername(username).isPresent() || users.findByEmail(email).isPresent()) throw new DuplicateResourceException();
        // Password whitespace is significant and must never be trimmed.
        var user = users.save(new User(ids.next(BusinessIdType.USER), username, email, encoder.encode(command.password()), command.role()));
        return result(user);
    }
    @Transactional(readOnly = true)
    public AuthResult login(LoginCommand command) {
        if (command == null || command.identifier() == null || command.password() == null) throw new InvalidCredentialsException();
        String identifier = command.identifier().trim();
        if (identifier.isEmpty()) throw new InvalidCredentialsException();
        var usernameMatch = users.findByUsername(identifier);
        var emailMatch = users.findByEmail(normalizeEmail(identifier));
        var user = usernameMatch.or(() -> emailMatch).orElse(null);
        String passwordHash = user == null ? UNKNOWN_ACCOUNT_PASSWORD_HASH : user.getPasswordHash();
        if (!encoder.matches(command.password(), passwordHash) || user == null) throw new InvalidCredentialsException();
        return result(user);
    }
    @Transactional
    public void resetLocalPassword(ResetPasswordCommand command, String remoteAddress) {
        if (!isLocalPasswordResetAvailable(remoteAddress)) throw new ResourceNotFoundException();
        if (command == null || command.identifier() == null || command.newPassword() == null
                || command.identifier().trim().isEmpty() || command.newPassword().length() < 12 || command.newPassword().length() > 128) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
        String identifier = command.identifier().trim();
        var user = users.findByUsername(identifier)
                .or(() -> users.findByEmail(normalizeEmail(identifier)))
                .orElseThrow(ResourceNotFoundException::new);
        user.replacePasswordHash(encoder.encode(command.newPassword()));
        users.save(user);
    }
    public User current(String id) { return users.findById(id).orElseThrow(com.resumethinking.platform.profiles.ResourceNotFoundException::new); }
    private boolean isLocalPasswordResetAvailable(String remoteAddress) {
        if (!localPasswordResetEnabled || !environment.acceptsProfiles(Profiles.of("local")) || remoteAddress == null) return false;
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (java.net.UnknownHostException ignored) {
            return false;
        }
    }
    private static String normalizeUsername(String value) {
        if (value == null) throw new IllegalArgumentException("VALIDATION_ERROR");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("VALIDATION_ERROR");
        return normalized;
    }
    private static String normalizeEmail(String value) {
        if (value == null) throw new IllegalArgumentException("VALIDATION_ERROR");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("VALIDATION_ERROR");
        return normalized;
    }
    private AuthResult result(User user) { long expires = jwt.expiresInSeconds(); return new AuthResult(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), jwt.issue(user), "Bearer", expires, user.getCreatedAt()); }
}
