package com.resumethinking.platform.profiles;
import org.springframework.data.repository.Repository; import java.util.*;
public interface LlmProfileRepository extends Repository<LlmProfile,String> { LlmProfile save(LlmProfile p); Optional<LlmProfile> findByIdAndOwnerId(String id, String ownerId); List<LlmProfile> findAllByOwnerId(String ownerId); void delete(LlmProfile p); }
