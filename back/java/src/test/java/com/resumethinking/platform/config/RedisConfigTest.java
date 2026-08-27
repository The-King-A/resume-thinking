package com.resumethinking.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.resumes.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisConfigTest {
 private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
 private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);

 @Test void resumeViewRoundTripsWithInstantFields(){
  Resume r=Resume.active(UUID.randomUUID(),UUID.randomUUID(),"x",Resume.SourceType.TXT,UserRole.USER,now,0L);
  RedisTemplate<String,Object> template=new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class),new ObjectMapper());
  @SuppressWarnings("unchecked") RedisSerializer<Object> serializer=(RedisSerializer<Object>)(RedisSerializer<?>)template.getValueSerializer();
  byte[] encoded=serializer.serialize(ResumeView.from(r));
  Object decoded=serializer.deserialize(encoded);
  assertThat(decoded).isInstanceOf(ResumeView.class);
  assertThat(((ResumeView) decoded).visibleUntil()).isEqualTo(r.getVisibleUntil());
 }

 @Test void ttlUsesRemainingDurableVisibilityWindowAndSkipsExpiredOrHidden(){
  @SuppressWarnings("unchecked") RedisTemplate<String,Object> template=mock(RedisTemplate.class);
  @SuppressWarnings("unchecked") ValueOperations<String,Object> values=mock(ValueOperations.class);
  when(template.opsForValue()).thenReturn(values);
  var cache=new RedisConfig.RedisResumeCache(template,clock);
  Resume active=Resume.active(UUID.randomUUID(),UUID.randomUUID(),"x",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(4)),0L);
  cache.put(active);
  verify(values).set(eq("resume:view:"+active.getId()),any(ResumeView.class),eq(Duration.ofDays(3)));
  Resume expired=Resume.active(UUID.randomUUID(),UUID.randomUUID(),"expired",Resume.SourceType.TXT,UserRole.USER,now.minus(Duration.ofDays(8)),0L);
  cache.put(expired);
  verify(values,never()).set(eq("resume:view:"+expired.getId()),any(),any(Duration.class));
  expired.softDelete(expired.getOwnerId(),UserRole.USER,now);
  cache.put(expired);
  verify(values,never()).set(eq("resume:view:"+expired.getId()),any(),any(Duration.class));
 }
}
