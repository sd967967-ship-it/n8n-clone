package com.automationstudio.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM secrets. Key from SECRETS_KEY (base64, 32 bytes). Values write-only via API. */
@Component
public class SecretsService {

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SecretsService(@Value("${secrets.key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank() || base64Key.startsWith("change-me")) {
      throw new IllegalStateException(
          "SECRETS_KEY is not set. Copy .env.example to .env and generate one.");
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key.trim());
    } catch (IllegalArgumentException e) {
      raw = base64Key.trim().getBytes(StandardCharsets.UTF_8);
    }
    if (raw.length != 32) {
      throw new IllegalStateException("SECRETS_KEY must decode to 32 bytes (got " + raw.length + ")");
    }
    this.key = new SecretKeySpec(raw, "AES");
  }

  public record Sealed(byte[] ciphertext, byte[] nonce) {}

  public Sealed seal(String plaintext) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return new Sealed(ct, nonce);
    } catch (Exception e) {
      throw new IllegalStateException("Seal failed", e);
    }
  }

  public String open(byte[] ciphertext, byte[] nonce) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Open failed", e);
    }
  }
}
