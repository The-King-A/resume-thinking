package com.resumethinking.platform.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "users")
public class User {
    @Id @Column(nullable = false, length = 64, columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String id;
    @Column(nullable = false, unique = true, length = 64) private String username;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private UserRole role;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected User() {}
    /** Test/legacy convenience constructor; production registration supplies an allocated ID. */
    public User(String username, String email, String passwordHash, UserRole role) {
        this("user001", username, email, passwordHash, role);
    }
    public User(String id, String username, String email, String passwordHash, UserRole role) {
        this.id = id; this.username = username; this.email = email;
        this.passwordHash = passwordHash; this.role = role; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public UserRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
