package com.resumethinking.platform.auth;

import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import java.security.MessageDigest;
import java.nio.charset.StandardCharsets; import java.time.Instant; import java.util.*;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final byte[] key; private final long ttl;
    public JwtService(@Value("${app.jwt-signing-key-base64:}") String configured) {
        if (configured == null || configured.isBlank()) throw new IllegalStateException("app.jwt-signing-key-base64 must be configured");
        try { this.key = Base64.getDecoder().decode(configured); } catch (IllegalArgumentException e) { throw new IllegalStateException("app.jwt-signing-key-base64 must be valid base64", e); }
        if (key.length != 32) throw new IllegalStateException("app.jwt-signing-key-base64 must decode to 256 bits"); this.ttl = 3600;
    }
    public String issue(User user) { long now = Instant.now().getEpochSecond(); String h = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"); String p = b64("{\"sub\":\""+user.getId()+"\",\"role\":\""+user.getRole()+"\",\"iat\":"+now+",\"exp\":"+(now+ttl)+"}"); return h+"."+p+"."+sign(h+"."+p); }
    public long expiresInSeconds() { return ttl; }
    public Optional<Claims> parse(String token) { try { String[] parts = token.split("\\."); if (parts.length != 3 || !MessageDigest.isEqual(sign(parts[0]+"."+parts[1]).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) return Optional.empty(); String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8); UUID id = UUID.fromString(payload.replaceAll(".*\\\"sub\\\":\\\"([^\\\"]+).*", "$1")); UserRole role = UserRole.valueOf(payload.replaceAll(".*\\\"role\\\":\\\"([^\\\"]+).*", "$1")); long exp = Long.parseLong(payload.replaceAll(".*\\\"exp\\\":(\\d+).*", "$1")); return exp < Instant.now().getEpochSecond() ? Optional.empty() : Optional.of(new Claims(id, role)); } catch (Exception e) { return Optional.empty(); } }
    private String sign(String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    public record Claims(UUID subject, UserRole role) {}
}
