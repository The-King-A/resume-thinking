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

@Configuration
public class RedisConfig {
    @Bean public Clock clock(){ return Clock.systemUTC(); }
    @Bean public ResumeCache resumeCache(RedisTemplate<String,Object> template, Clock clock){ return new RedisResumeCache(template,clock); }
    static final class RedisResumeCache implements ResumeCache {
        private final RedisTemplate<String,Object> template; private final Clock clock;
        RedisResumeCache(RedisTemplate<String,Object> template, Clock clock){this.template=template;this.clock=clock;}
        public void put(Resume resume){
            if (resume.getVisibilityState()!=VisibilityState.ACTIVE || resume.getVisibleUntil()==null) return;
            Duration ttl=Duration.between(clock.instant(),resume.getVisibleUntil());
            if (ttl.isZero() || ttl.isNegative()) return;
            template.opsForValue().set("resume:view:"+resume.getId(), ResumeView.from(resume), ttl);
        }
        public void evict(String key){ template.delete(key); }
    }
    @Bean public RedisTemplate<String,Object> redisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory factory, ObjectMapper objectMapper){
        RedisTemplate<String,Object> t=new RedisTemplate<>(); t.setConnectionFactory(factory); t.setKeySerializer(new StringRedisSerializer()); t.setHashKeySerializer(new StringRedisSerializer());
        ObjectMapper redisMapper=objectMapper.copy().registerModule(new JavaTimeModule());
        var validator=BasicPolymorphicTypeValidator.builder().allowIfSubType("com.resumethinking.platform.resumes.").allowIfSubType("java.time.").allowIfSubType("java.util.").build();
        redisMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisMapper)); t.afterPropertiesSet(); return t;
    }
}
