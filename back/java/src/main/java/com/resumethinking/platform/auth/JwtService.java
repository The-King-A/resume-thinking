package com.resumethinking.platform.auth;

import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import java.security.MessageDigest;
import java.nio.charset.StandardCharsets; import java.time.Instant; import java.util.*;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final byte[] key; private final long ttl; private final ObjectMapper mapper = new ObjectMapper();
    public JwtService(@Value("${app.jwt-signing-key-base64:}") String configured) {
        if (configured == null || configured.isBlank()) throw new IllegalStateException("app.jwt-signing-key-base64 must be configured");
        try { this.key = Base64.getDecoder().decode(configured); } catch (IllegalArgumentException e) { throw new IllegalStateException("app.jwt-signing-key-base64 must be valid base64", e); }
        if (key.length != 32) throw new IllegalStateException("app.jwt-signing-key-base64 must decode to 256 bits"); this.ttl = 3600;
    }
    public String issue(User user) { ReadableIdGenerator.validate(BusinessIdType.USER, user.getId()); long now = Instant.now().getEpochSecond(); String h = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"); String p = b64("{\"sub\":\""+user.getId()+"\",\"role\":\""+user.getRole()+"\",\"iat\":"+now+",\"exp\":"+(now+ttl)+"}"); return h+"."+p+"."+sign(h+"."+p); }
    public long expiresInSeconds() { return ttl; }
    public Optional<Claims> parse(String token) {
        try {
            if (token == null) return Optional.empty();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return Optional.empty();
            if (!MessageDigest.isEqual(sign(parts[0]+"."+parts[1]).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            JsonNode header = mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (header == null || !"HS256".equals(header.path("alg").textValue()) || !"JWT".equals(header.path("typ").textValue())) return Optional.empty();
            JsonNode payload = mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (payload == null || !payload.isObject()) return Optional.empty();
            String id = payload.path("sub").textValue(); String roleText = payload.path("role").textValue(); JsonNode expNode = payload.get("exp");
            if (!ReadableIdGenerator.isValid(BusinessIdType.USER, id) || roleText == null || expNode == null || !expNode.canConvertToLong()) return Optional.empty();
            long exp = expNode.longValue(); if (exp <= Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(new Claims(id, UserRole.valueOf(roleText)));
        } catch (Exception e) { return Optional.empty(); }
    }
    private String sign(String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    public record Claims(String subject, UserRole role) {}
}
