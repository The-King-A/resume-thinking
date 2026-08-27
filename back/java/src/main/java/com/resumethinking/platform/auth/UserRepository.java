package com.resumethinking.platform.auth;

import org.springframework.data.repository.Repository;
import java.util.*;

public interface UserRepository extends Repository<User, UUID> {
    User save(User user);
    Optional<User> findById(UUID id);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
}
