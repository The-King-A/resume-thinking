package com.resumethinking.platform.resumes;

import java.util.*;

public interface ResumeCache { void put(Resume resume); void evict(String key); default void evict(UUID id){evict("resume:view:"+id);} default Optional<Resume> get(UUID id){return Optional.empty();}
    class InMemory implements ResumeCache { private final Map<String,Resume> values=new HashMap<>(); public void put(Resume r){values.put("resume:view:"+r.getId(),r);} public void evict(String key){values.remove(key);} public Optional<Resume> get(UUID id){return Optional.ofNullable(values.get("resume:view:"+id));} }
}
