package com.resumethinking.platform.config;

import com.resumethinking.platform.resumes.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import java.time.Clock;

@Configuration
public class RedisConfig {
    @Bean public Clock clock(){ return Clock.systemUTC(); }
    @Bean public ResumeCache resumeCache(RedisTemplate<String,Object> template){ return new RedisResumeCache(template); }
    @Bean public ResumeAuditRepository resumeAuditRepository(){ return audit -> {}; }
    static final class RedisResumeCache implements ResumeCache {
        private final RedisTemplate<String,Object> template; RedisResumeCache(RedisTemplate<String,Object> template){this.template=template;}
        public void put(Resume resume){ template.opsForValue().set("resume:view:"+resume.getId(), ResumeView.from(resume), resume.getCreatorRole()==com.resumethinking.platform.auth.UserRole.ADMIN ? java.time.Duration.ofDays(30) : java.time.Duration.ofDays(7)); }
        public void evict(String key){ template.delete(key); }
    }
    @Bean public RedisTemplate<String,Object> redisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory factory){ RedisTemplate<String,Object> t=new RedisTemplate<>(); t.setConnectionFactory(factory); t.setKeySerializer(new StringRedisSerializer()); t.setHashKeySerializer(new StringRedisSerializer()); t.setValueSerializer(new GenericJackson2JsonRedisSerializer()); t.afterPropertiesSet(); return t; }
}
