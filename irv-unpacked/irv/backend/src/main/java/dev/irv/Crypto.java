package dev.irv;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.*;

@Component
public class Crypto {
  private final byte[] key;
  private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  public Crypto() {
    String s = Util.env("IRV_ENCRYPTION_KEY", "");
    if (s.isEmpty())
      throw new IllegalStateException(
          "IRV_ENCRYPTION_KEY must be a base64-encoded random 32-byte key");
    key = Base64.getDecoder().decode(s);
    if (key.length != 32) throw new IllegalStateException("32-byte encryption key required");
  }

  public String seal(String s) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(12 + s.getBytes(StandardCharsets.UTF_8).length + 16)
                  .put(iv)
                  .put(c.doFinal(s.getBytes(StandardCharsets.UTF_8)))
                  .array());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String open(String s) {
    try {
      byte[] b = Base64.getDecoder().decode(s);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(b, 12)));
      return new String(c.doFinal(Arrays.copyOfRange(b, 12, b.length)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decrypt credential", e);
    }
  }

  public static String secret() {
    byte[] b = new byte[20];
    new SecureRandom().nextBytes(b);
    StringBuilder out = new StringBuilder();
    int v = 0, bits = 0;
    for (byte x : b) {
      v = (v << 8) | (x & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append(B32.charAt((v >> bits) & 31));
      }
    }
    return out.toString();
  }

  static byte[] decode(String s) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int v = 0, bits = 0;
    for (char c : s.toCharArray()) {
      int x = B32.indexOf(c);
      if (x < 0) throw new IllegalArgumentException();
      v = (v << 5) | x;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out.write((v >> bits) & 255);
      }
    }
    return out.toByteArray();
  }

  public static String otp(String secret, long step) {
    try {
      Mac m = Mac.getInstance("HmacSHA1");
      m.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
      byte[] h = m.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int o = h[19] & 15;
      int n = ByteBuffer.wrap(h, o, 4).getInt() & 0x7fffffff;
      return String.format("%06d", n % 1000000);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static long verify(String secret, String code, long last, long now) {
    long t = now / 30000;
    for (long i = t - 1; i <= t + 1; i++)
      if (i > last
          && MessageDigest.isEqual(
              otp(secret, i).getBytes(StandardCharsets.UTF_8),
              code.getBytes(StandardCharsets.UTF_8))) return i;
    return -1;
  }
}
