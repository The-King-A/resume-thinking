package com.resumethinking.platform.resumes;

import java.util.*;
import java.time.Clock;
import java.time.Duration;

public interface ResumeCache { void put(Resume resume); void evict(String key); default void evict(UUID id){evict("resume:view:"+id);} default Optional<Resume> get(UUID id){return Optional.empty();}
    class Noop implements ResumeCache { public void put(Resume resume) {} public void evict(String key) {} }
    class InMemory implements ResumeCache {
        private final Map<String,Resume> values=new HashMap<>(); private final Clock clock;
        public InMemory(){this(Clock.systemUTC());} public InMemory(Clock clock){this.clock=clock;}
        public void put(Resume r){if(r.getVisibilityState()!=VisibilityState.ACTIVE||r.getVisibleUntil()==null||!clock.instant().isBefore(r.getVisibleUntil()))return; values.put("resume:view:"+r.getId(),r);}
        public void evict(String key){values.remove(key);} public Optional<Resume> get(UUID id){Resume r=values.get("resume:view:"+id); if(r==null||r.getVisibilityState()!=VisibilityState.ACTIVE||r.getVisibleUntil()==null||!clock.instant().isBefore(r.getVisibleUntil())){values.remove("resume:view:"+id);return Optional.empty();} return Optional.of(r);}
    }
}
