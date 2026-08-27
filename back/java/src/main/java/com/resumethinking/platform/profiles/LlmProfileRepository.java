package com.resumethinking.platform.profiles;
import org.springframework.data.repository.Repository; import java.util.*;
public interface LlmProfileRepository extends Repository<LlmProfile,UUID> { LlmProfile save(LlmProfile p); Optional<LlmProfile> findByIdAndOwnerId(UUID id, UUID ownerId); List<LlmProfile> findAllByOwnerId(UUID ownerId); void delete(LlmProfile p); }
