package com.resumethinking.platform.config;

import com.resumethinking.platform.resumes.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;

@Configuration
public class RedisConfig {
    @Bean public Clock clock(){ return Clock.systemUTC(); }
    @Bean public ResumeCache resumeCache(RedisTemplate<String,Object> template, Clock clock){ return new RedisResumeCache(template,clock); }
    @Bean public ApplicationRunner legacyResumeCacheCleanup(ResumeCache cache){ return arguments -> cache.clearLegacyKeys(); }
    static final class RedisResumeCache implements ResumeCache {
        private final RedisTemplate<String,Object> template; private final Clock clock;
        RedisResumeCache(RedisTemplate<String,Object> template, Clock clock){this.template=template;this.clock=clock;}
        public void put(Resume resume){
            if (resume.getVisibilityState()!=VisibilityState.ACTIVE || resume.getVisibleUntil()==null) return;
            Duration ttl=Duration.between(clock.instant(),resume.getVisibleUntil());
            if (ttl.isZero() || ttl.isNegative()) return;
            template.opsForValue().set(ResumeCache.key(resume.getId()), ResumeView.from(resume), ttl);
        }
        public void evict(String resumeId){ template.delete(ResumeCache.key(resumeId)); }
        public void clearLegacyKeys(){
            try {
                template.execute((RedisCallback<Void>) connection -> {
                    try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(ResumeCache.LEGACY_KEY_PATTERN).count(100).build())) {
                        while (cursor.hasNext()) connection.keyCommands().del(cursor.next());
                    }
                    return null;
                });
            } catch (RuntimeException ignored) {
                // Redis is derived storage; cleanup must not block a durable application start.
            }
        }
    }
    @Bean public RedisTemplate<String,Object> redisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory factory, ObjectMapper objectMapper){
        RedisTemplate<String,Object> t=new RedisTemplate<>(); t.setConnectionFactory(factory); t.setKeySerializer(new StringRedisSerializer()); t.setHashKeySerializer(new StringRedisSerializer());
        ObjectMapper redisMapper=objectMapper.copy().registerModule(new JavaTimeModule());
        var validator=BasicPolymorphicTypeValidator.builder().allowIfSubType("com.resumethinking.platform.resumes.").allowIfSubType("java.time.").allowIfSubType("java.util.").build();
        redisMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisMapper)); t.afterPropertiesSet(); return t;
    }
}
