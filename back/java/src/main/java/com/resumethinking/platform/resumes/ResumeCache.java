package com.resumethinking.platform.resumes;

import java.util.*;
import java.time.Clock;
import java.time.Duration;

public interface ResumeCache {
    String KEY_PREFIX = "resume:v2:view:";
    String LEGACY_KEY_PATTERN = "resume:view:*";

    static String key(String resumeId) { return KEY_PREFIX + resumeId; }

    void put(Resume resume);
    void evict(String resumeId);
    default void clearLegacyKeys() {}
    default Optional<Resume> get(String id){return Optional.empty();}

    class Noop implements ResumeCache { public void put(Resume resume) {} public void evict(String resumeId) {} }
    class InMemory implements ResumeCache {
        private final Map<String,Resume> values=new HashMap<>(); private final Clock clock;
        public InMemory(){this(Clock.systemUTC());} public InMemory(Clock clock){this.clock=clock;}
        public void put(Resume r){if(r.getVisibilityState()!=VisibilityState.ACTIVE||r.getVisibleUntil()==null||!clock.instant().isBefore(r.getVisibleUntil()))return; values.put(key(r.getId()),r);}
        public void evict(String resumeId){values.remove(key(resumeId));} public Optional<Resume> get(String id){Resume r=values.get(key(id)); if(r==null||r.getVisibilityState()!=VisibilityState.ACTIVE||r.getVisibleUntil()==null||!clock.instant().isBefore(r.getVisibleUntil())){values.remove(key(id));return Optional.empty();} return Optional.of(r);}
    }
}
