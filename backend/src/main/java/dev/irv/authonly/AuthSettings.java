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
  final String ingressSecret, enrollmentToken, inviteDb, singleInviteHash;
  // Published by the durable ledger before enrollment can serve requests.
  volatile java.time.Instant singleInviteExpiry = java.time.Instant.EPOCH;
  final boolean certificateRequired, enrollmentEnabled, enrollmentExplicitlyDisabled;
  final Map<String, java.time.Instant> invites;

  AuthSettings(Environment env, ObjectMapper mapper) {
    ingressSecret = env.getProperty("IRV_AUTH_INGRESS_SECRET", "");
    if (!ingressSecret.isEmpty() && !ingressSecret.matches("[!-~]{32,256}"))
      throw new IllegalStateException(
          "IRV_AUTH_INGRESS_SECRET must be 32-256 printable ASCII characters (U+0021 through U+007E), with no whitespace");
    certificateRequired = flag(env, "IRV_AUTH_CERT_REQUIRED", true);
    enrollmentToken = env.getProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "");
    inviteDb = env.getProperty("IRV_AUTH_INVITE_DB", "/data/auth/invites");
    Map<String, java.time.Instant> parsed = new HashMap<>();
    try {
      Map<String, String> input = mapper.readValue(env.getProperty("IRV_AUTH_INVITES_JSON", "{}"), new TypeReference<>() {});
      if (input == null || input.size() > 1000) throw new IllegalArgumentException();
      for (var entry : input.entrySet()) {
        if (!entry.getKey().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        parsed.put(entry.getKey(), java.time.Instant.parse(entry.getValue()));
      }
    } catch (Exception ex) { throw new IllegalStateException("Invalid IRV_AUTH_INVITES_JSON: expected SHA256-to-UTC-expiry object"); }
    // Parse separately so configuration errors identify the setting without exposing its value.
    try {
      var input = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readTree(env.getProperty("IRV_AUTH_INVITE_CODES_JSON", "[]"));
      if (input == null || !input.isArray() || input.size() > 1000) throw new IllegalArgumentException();
      for (var entry : input) {
        if (!entry.isObject() || entry.size() != 2 || !entry.path("code").isTextual()
            || !entry.path("expiresAt").isTextual()) throw new IllegalArgumentException();
        String code = entry.path("code").textValue();
        String expiry = entry.path("expiresAt").textValue();
        if (!code.matches("[A-Za-z0-9_-]{43,128}") || !expiry.endsWith("Z")) throw new IllegalArgumentException();
        var instant = java.time.Instant.parse(expiry);
        var previous = parsed.putIfAbsent(Enrollment.hash(code), instant);
        // Never silently extend or shorten an invite configured in both formats.
        if (previous != null && !previous.equals(instant)) throw new IllegalArgumentException();
      }
      if (parsed.size() > 1000) throw new IllegalArgumentException();
    } catch (Exception ex) {
      // Deliberately omit the parser exception: its message can contain the raw secret.
      throw new IllegalStateException("Invalid IRV_AUTH_INVITE_CODES_JSON: expected code/UTC expiresAt array; "
          + "codes must be 43-128 letters, digits, underscores or hyphens; maximum 1000 combined invites; "
          + "duplicate codes must have identical expiry");
    }
    String singleCode = env.getProperty("IRV_AUTH_INVITE_CODE", "");
    if (!singleCode.isEmpty() && !singleCode.matches("[A-Za-z0-9_-]{43,128}"))
      throw new IllegalStateException("IRV_AUTH_INVITE_CODE must be 43-128 letters, digits, underscores or hyphens");
    singleInviteHash = singleCode.isEmpty() ? "" : Enrollment.hash(singleCode);
    if (!singleInviteHash.isEmpty() && !parsed.isEmpty())
      throw new IllegalStateException("Use IRV_AUTH_INVITE_CODE or JSON invites, not both; remove old JSON invite secrets");
    invites = Map.copyOf(parsed);
    enrollmentExplicitlyDisabled = "false".equals(env.getProperty("IRV_AUTH_ENROLLMENT_ENABLED"));
    enrollmentEnabled = flag(env, "IRV_AUTH_ENROLLMENT_ENABLED", !enrollmentToken.isBlank() && (!invites.isEmpty() || !singleInviteHash.isEmpty()));
    if (enrollmentEnabled && (enrollmentToken.isBlank() || enrollmentToken.chars().anyMatch(Character::isWhitespace)))
      throw new IllegalStateException("Enrollment requires IRV_ZITADEL_ENROLLMENT_TOKEN");
    if (!inviteDb.matches("/[a-zA-Z0-9_./-]+") || inviteDb.contains(".."))
      throw new IllegalStateException("IRV_AUTH_INVITE_DB must be an absolute file path");
  }
  AuthAudit.Reason signupAvailability(java.time.Instant now) {
    if (enrollmentExplicitlyDisabled) return AuthAudit.Reason.ENROLLMENT_DISABLED;
    if (enrollmentToken.isBlank()) return AuthAudit.Reason.PROVIDER_TOKEN_MISSING;
    if (!singleInviteHash.isEmpty())
      return now.isBefore(singleInviteExpiry) ? AuthAudit.Reason.VALID : AuthAudit.Reason.INVITES_EXPIRED;
    if (invites.isEmpty()) return AuthAudit.Reason.INVITES_NOT_CONFIGURED;
    if (invites.values().stream().noneMatch(now::isBefore)) return AuthAudit.Reason.INVITES_EXPIRED;
    return AuthAudit.Reason.VALID;
  }
  boolean signupAvailable(java.time.Instant now) { return signupAvailability(now) == AuthAudit.Reason.VALID; }

  java.time.Instant inviteExpiry(String hash) {
    return !singleInviteHash.isEmpty() && singleInviteHash.equals(hash)
        ? singleInviteExpiry : invites.getOrDefault(hash, java.time.Instant.EPOCH);
  }

  private static boolean flag(Environment env, String name, boolean fallback) {
    String value = env.getProperty(name, Boolean.toString(fallback));
    if (!Set.of("true", "false").contains(value)) throw new IllegalStateException("Invalid boolean: " + name);
    return Boolean.parseBoolean(value);
  }
}
