package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PlaintextInviteTest {
  static final String CODE = "aB3_-".repeat(10), EXPIRY = "2099-01-01T00:00:00Z";
  @TempDir Path temp;
  String json(String code, String expiry) { return "[{\"code\":\"" + code + "\",\"expiresAt\":\"" + expiry + "\"}]"; }
  MockEnvironment env(String value) {
    return new MockEnvironment().withProperty("IRV_AUTH_INVITE_CODES_JSON", value)
        .withProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "test-token")
        .withProperty("IRV_AUTH_INVITE_DB", temp.resolve("auth/invites").toString());
  }
  AuthSettings settings(MockEnvironment e) { return new AuthSettings(e, new ObjectMapper()); }
  @Test void hashesRawCodeAndEnablesSignupWithExpiry() {
    var s = settings(env(json(CODE, EXPIRY)));
    assertEquals(java.util.Map.of(Enrollment.hash(CODE), Instant.parse(EXPIRY)), s.invites);
    assertTrue(s.enrollmentEnabled);
    assertTrue(s.signupAvailable(Instant.parse("2026-10-22T00:00:00Z")));
    assertFalse(s.signupAvailable(Instant.parse(EXPIRY)));
    assertFalse(settings(env(json(CODE, EXPIRY)).withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "false")).signupAvailable(Instant.now()));
  }
  @Test void rejectsMalformedInputWithoutLeakingCodesInExceptionChain() {
    for (String value : java.util.List.of("null", "{}", "[", json(CODE, "bad-date"), json("x".repeat(42), EXPIRY),
        json("x".repeat(129), EXPIRY), json(CODE + " ", EXPIRY), json(CODE, "2099-01-01T00:00:00+01:00"),
        json(CODE, EXPIRY) + " []", "[{\"code\":\"" + CODE + "\",\"expiresAt\":123}]",
        "[{\"code\":\"" + CODE + "\",\"expiresAt\":\"" + EXPIRY + "\",\"extra\":true}]")) {
      var error = assertThrows(IllegalStateException.class, () -> settings(env(value)));
      assertFalse(error.toString().contains(CODE));
      assertNull(error.getCause());
    }
  }
  @Test void supportsBothFormatsAndRejectsConflictingExpiry() {
    var e = env(json(CODE, EXPIRY)).withProperty("IRV_AUTH_INVITES_JSON",
        "{\"" + Enrollment.hash(CODE) + "\":\"" + EXPIRY + "\",\"" + "b".repeat(64) + "\":\"" + EXPIRY + "\"}");
    assertEquals(2, settings(e).invites.size());
    e.withProperty("IRV_AUTH_INVITE_CODES_JSON", json(CODE, "2098-01-01T00:00:00Z"));
    assertThrows(IllegalStateException.class, () -> settings(e));
  }
  @Test void migrationAndRestartNeverMakeReservedInviteReusable() throws Exception {
    var e = env("[]").withProperty("IRV_AUTH_INVITES_JSON", "{\"" + Enrollment.hash(CODE) + "\":\"" + EXPIRY + "\"}");
    try (var ledger = new InviteLedger(settings(e))) { assertTrue(ledger.reserve(Enrollment.hash(CODE), Instant.now())); }
    e.withProperty("IRV_AUTH_INVITES_JSON", "{}").withProperty("IRV_AUTH_INVITE_CODES_JSON", json(CODE, EXPIRY));
    try (var ledger = new InviteLedger(settings(e))) { assertFalse(ledger.reserve(Enrollment.hash(CODE), Instant.now())); }
  }
  @Test void enforcesCombinedLimit() {
    var hashes = new java.util.HashMap<String,String>();
    for (int i = 0; i < 1000; i++) hashes.put(String.format("%064x", i), EXPIRY);
    assertThrows(IllegalStateException.class, () -> settings(env(json(CODE, EXPIRY))
        .withProperty("IRV_AUTH_INVITES_JSON", new ObjectMapper().writeValueAsString(hashes))));
  }
}
