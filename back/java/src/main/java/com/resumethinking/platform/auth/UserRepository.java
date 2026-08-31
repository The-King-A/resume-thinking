package com.resumethinking.platform.auth;

import org.springframework.data.repository.Repository;
import java.util.*;

public interface UserRepository extends Repository<User, String> {
    User save(User user);
    Optional<User> findById(String id);
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
}
