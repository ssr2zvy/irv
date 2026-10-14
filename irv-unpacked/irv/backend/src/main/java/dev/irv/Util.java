package dev.irv;

import com.fasterxml.jackson.databind.*;
import java.security.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Util {
  public static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RNG = new SecureRandom();

  public static long now() {
    return System.currentTimeMillis();
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String token() {
    byte[] b = new byte[32];
    RNG.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String env(String k, String d) {
    return System.getenv().getOrDefault(k, d);
  }

  public static int num(String k, int d) {
    return Integer.parseInt(env(k, "" + d));
  }

  public static String hash(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(String s) {
    return hash(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public static String json(Object x) {
    try {
      return JSON.writeValueAsString(x);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static JsonNode node(String s) {
    try {
      return JSON.readTree(s);
    } catch (Exception e) {
      throw bad("Invalid JSON");
    }
  }

  public static String str(Map<String, Object> m, String k) {
    return Objects.toString(m.get(k), "");
  }

  public static long lng(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v instanceof Number n ? n.longValue() : 0;
  }

  public static ResponseStatusException bad(String s) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, s);
  }

  public static ResponseStatusException deny() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  public static ResponseStatusException conflict(String s) {
    return new ResponseStatusException(HttpStatus.CONFLICT, s);
  }
}
