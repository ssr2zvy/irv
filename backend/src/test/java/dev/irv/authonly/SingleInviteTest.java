package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SingleInviteTest {
  static final String CODE = "UserDefined_-123".repeat(4), OTHER = "AnotherCode_123-".repeat(4);
  static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  @TempDir Path temp;
  MockEnvironment env(String code) {
    return new MockEnvironment().withProperty("IRV_AUTH_INVITE_CODE", code)
        .withProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "test-token")
        .withProperty("IRV_AUTH_INVITE_DB", temp.resolve("auth/invites").toString());
  }
  AuthSettings settings(MockEnvironment e) { return new AuthSettings(e, new ObjectMapper()); }
  InviteLedger open(AuthSettings s, Instant time) { return new InviteLedger(s, Clock.fixed(time, ZoneOffset.UTC)); }
  @Test void exactCodeAndPersistentExpiryAreEnforced() throws Exception {
    var s = settings(env(CODE));
    assertTrue(s.enrollmentEnabled);
    assertFalse(s.signupAvailable(NOW)); // Fail closed until durable activation.
    try (var ledger = open(s, NOW)) {
      assertTrue(s.signupAvailable(NOW));
      assertEquals(NOW.plusSeconds(7200), s.inviteExpiry(Enrollment.hash(CODE)));
      assertEquals(Instant.EPOCH, s.inviteExpiry(Enrollment.hash(CODE.toLowerCase())));
      assertFalse(s.signupAvailable(NOW.plusSeconds(7200)));
    }
    var restarted = settings(env(CODE));
    try (var ledger = open(restarted, NOW.plusSeconds(10800))) {
      assertEquals(NOW.plusSeconds(7200), restarted.singleInviteExpiry);
      assertFalse(restarted.signupAvailable(NOW.plusSeconds(10800)));
    }
  }
  @Test void rotationAndReaddingNeverResurrectUsedCode() throws Exception {
    var first = settings(env(CODE));
    try (var ledger = open(first, NOW)) { assertTrue(ledger.reserve(first.singleInviteHash, NOW)); }
    var next = settings(env(OTHER));
    try (var ledger = open(next, NOW.plusSeconds(100))) {
      assertEquals(NOW.plusSeconds(7300), next.singleInviteExpiry);
      assertEquals(Instant.EPOCH, next.inviteExpiry(first.singleInviteHash));
    }
    var readded = settings(env(CODE));
    try (var ledger = open(readded, NOW.plusSeconds(200))) {
      assertEquals(NOW.plusSeconds(7200), readded.singleInviteExpiry);
      assertFalse(ledger.reserve(first.singleInviteHash, NOW.plusSeconds(200)));
    }
  }
  @Test void disabledEnrollmentDoesNotActivateOrExtendInvite() throws Exception {
    var disabled = settings(env(CODE).withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "false"));
    try (var ledger = open(disabled, NOW)) { assertFalse(disabled.signupAvailable(NOW)); }
    var enabled = settings(env(CODE));
    try (var ledger = open(enabled, NOW.plusSeconds(100))) {
      assertEquals(NOW.plusSeconds(7300), enabled.singleInviteExpiry);
    }
  }
  @Test void formatAndMixedConfigurationFailWithoutExposingSecret() {
    for (String code : java.util.List.of("x".repeat(42), "x".repeat(129), CODE + " ", CODE + "!")) {
      var error = assertThrows(IllegalStateException.class, () -> settings(env(code)));
      assertFalse(error.getMessage().contains(code)); assertNull(error.getCause());
    }
    assertThrows(IllegalStateException.class, () -> settings(env(CODE).withProperty("IRV_AUTH_INVITES_JSON",
        "{\"" + "a".repeat(64) + "\":\"2099-01-01T00:00:00Z\"}")));
    assertThrows(IllegalStateException.class, () -> settings(env(CODE).withProperty("IRV_AUTH_INVITE_CODES_JSON",
        "[{\"code\":\"" + OTHER + "\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}]")));
    assertFalse(settings(env("")).signupAvailable(NOW));
  }
  @Test void legacyConsumptionSurvivesMigrationToSingleSecret() throws Exception {
    var legacy = settings(env("").withProperty("IRV_AUTH_INVITES_JSON",
        "{\"" + Enrollment.hash(CODE) + "\":\"2099-01-01T00:00:00Z\"}"));
    try (var ledger = open(legacy, NOW)) { assertTrue(ledger.reserve(Enrollment.hash(CODE), NOW)); }
    var migrated = settings(env(CODE));
    try (var ledger = open(migrated, NOW.plusSeconds(10))) {
      assertFalse(ledger.reserve(migrated.singleInviteHash, NOW.plusSeconds(10)));
    }
  }
  @Test void upgradesOldDeadlineOnceWithoutRenewingActivation() throws Exception {
    var configuration = env(CODE);
    java.nio.file.Files.createDirectories(temp.resolve("auth"));
    try (var db = java.sql.DriverManager.getConnection("jdbc:h2:file:" + temp.resolve("auth/invites"), "sa", "");
         var statement = db.createStatement()) {
      statement.execute("CREATE TABLE invite_activation(code_hash VARCHAR(64) PRIMARY KEY, expires_at BIGINT NOT NULL)");
      try (var insert = db.prepareStatement("INSERT INTO invite_activation VALUES(?,?)")) {
        insert.setString(1, Enrollment.hash(CODE)); insert.setLong(2, NOW.plusSeconds(86400).toEpochMilli()); insert.executeUpdate();
      }
    }
    for (int i = 0; i < 2; i++) {
      var upgraded = settings(configuration);
      try (var ledger = open(upgraded, NOW.plusSeconds(10800))) {
        assertEquals(NOW.plusSeconds(7200), upgraded.singleInviteExpiry);
        assertFalse(upgraded.signupAvailable(NOW.plusSeconds(10800)));
      }
    }
    var next = settings(env(OTHER));
    try (var ledger = open(next, NOW.plusSeconds(10800))) {
      assertEquals(NOW.plusSeconds(18000), next.singleInviteExpiry);
    }
    var nextRestart = settings(env(OTHER));
    try (var ledger = open(nextRestart, NOW.plusSeconds(11000))) {
      assertEquals(NOW.plusSeconds(18000), nextRestart.singleInviteExpiry);
    }
  }

}
