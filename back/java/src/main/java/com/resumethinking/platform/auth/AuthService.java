package com.resumethinking.platform.auth;

import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class AuthService {
    private final UserRepository users; private final PasswordEncoder encoder; private final JwtService jwt; private final ReadableIdGenerator ids;
    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, ReadableIdGenerator ids) { this.users = users; this.encoder = encoder; this.jwt = jwt; this.ids = ids; }
    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) { this(users, encoder, jwt, new InMemoryReadableIdGenerator()); }
    @Transactional
    public AuthResult register(RegisterCommand command) {
        if (users.findByUsername(command.username()).isPresent() || users.findByEmail(command.email().toLowerCase(Locale.ROOT)).isPresent()) throw new DuplicateResourceException();
        var user = users.save(new User(ids.next(BusinessIdType.USER), command.username(), command.email().toLowerCase(Locale.ROOT), encoder.encode(command.password()), command.role()));
        return result(user);
    }
    public AuthResult login(LoginCommand command) {
        var user = users.findByUsername(command.identifier()).or(() -> users.findByEmail(command.identifier().toLowerCase(Locale.ROOT))).orElseThrow(AuthenticationException::new);
        if (!encoder.matches(command.password(), user.getPasswordHash())) throw new AuthenticationException();
        return result(user);
    }
    public User current(String id) { return users.findById(id).orElseThrow(com.resumethinking.platform.profiles.ResourceNotFoundException::new); }
    private AuthResult result(User user) { long expires = jwt.expiresInSeconds(); return new AuthResult(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), jwt.issue(user), "Bearer", expires, user.getCreatedAt()); }
}
