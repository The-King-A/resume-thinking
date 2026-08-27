package com.resumethinking.platform.crypto;

import javax.crypto.Cipher; import javax.crypto.spec.GCMParameterSpec; import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets; import java.security.SecureRandom; import java.util.*;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;

@Service
public class AesGcmCryptoService {
    private final SecretKeySpec key; private final SecureRandom random = new SecureRandom();
    public AesGcmCryptoService(@Value("${app.encryption-key-base64:}") String encoded) {
        byte[] bytes; try { bytes = Base64.getDecoder().decode(encoded); } catch (Exception e) { bytes = new byte[0]; }
        if (bytes.length != 32) bytes = Arrays.copyOf(bytes, 32); key = new SecretKeySpec(bytes, "AES");
    }
    public EncryptedValue encrypt(String plaintext) { try { byte[] nonce = new byte[12]; random.nextBytes(nonce); Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); return new EncryptedValue(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce); } catch (Exception e) { throw new IllegalStateException("Encryption failed", e); } }
    public String decrypt(byte[] ciphertext, byte[] nonce) { try { Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce)); return new String(c.doFinal(ciphertext), StandardCharsets.UTF_8); } catch (Exception e) { throw new IllegalArgumentException("Invalid encrypted value", e); } }
    public record EncryptedValue(byte[] ciphertext, byte[] nonce) {}
}
