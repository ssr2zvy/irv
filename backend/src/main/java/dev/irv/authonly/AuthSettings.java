package dev.irv.authonly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.core.env.Environment;

/** Independent certificate policy and invite configuration; no permanent certificate/account registry. */
public final class AuthSettings {
  public static final String ISSUER = "https://irv-instance-4ftbgu.us1.zitadel.cloud";
  public static final String ORIGIN = "https://iruvu.space";
  public static final String CLIENT = "395286007051839802";
  public static final String PROJECT = "395285999653022010";
  public static final String ORG = "395285989452532411";
  public static final String ROLE = "irv_user";
  final String ingressSecret, enrollmentToken, inviteDb;
  final boolean certificateRequired, enrollmentEnabled;
  final Map<String, java.time.Instant> invites;

  AuthSettings(Environment env, ObjectMapper mapper) {
    ingressSecret = env.getProperty("IRV_AUTH_INGRESS_SECRET", "");
    if (!ingressSecret.isEmpty() && !ingressSecret.matches("[!-~]{32,256}"))
      throw new IllegalStateException(
          "IRV_AUTH_INGRESS_SECRET must be 32-256 printable ASCII characters (U+0021 through U+007E), with no whitespace");
    certificateRequired = flag(env, "IRV_AUTH_CERT_REQUIRED", true);
    enrollmentEnabled = flag(env, "IRV_AUTH_ENROLLMENT_ENABLED", false);
    enrollmentToken = env.getProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "");
    inviteDb = env.getProperty("IRV_AUTH_INVITE_DB", "/data/auth/invites");
    try {
      Map<String, String> input = mapper.readValue(env.getProperty("IRV_AUTH_INVITES_JSON", "{}"), new TypeReference<>() {});
      if (input == null || input.size() > 1000) throw new IllegalArgumentException();
      Map<String, java.time.Instant> parsed = new HashMap<>();
      for (var entry : input.entrySet()) {
        if (!entry.getKey().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        parsed.put(entry.getKey(), java.time.Instant.parse(entry.getValue()));
      }
      invites = Map.copyOf(parsed);
    } catch (Exception ex) { throw new IllegalStateException("Invalid IRV_AUTH_INVITES_JSON: expected SHA256-to-UTC-expiry object"); }
    if (enrollmentEnabled && (enrollmentToken.isBlank() || enrollmentToken.chars().anyMatch(Character::isWhitespace)))
      throw new IllegalStateException("Enrollment requires IRV_ZITADEL_ENROLLMENT_TOKEN");
    if (!inviteDb.matches("/[a-zA-Z0-9_./-]+") || inviteDb.contains(".."))
      throw new IllegalStateException("IRV_AUTH_INVITE_DB must be an absolute file path");
  }
  private static boolean flag(Environment env, String name, boolean fallback) {
    String value = env.getProperty(name, Boolean.toString(fallback));
    if (!Set.of("true", "false").contains(value)) throw new IllegalStateException("Invalid boolean: " + name);
    return Boolean.parseBoolean(value);
  }
}
