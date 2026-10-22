package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class SignupAvailabilityTest {
  static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
  MockEnvironment configured() {
    return new MockEnvironment().withProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "test-token")
        .withProperty("IRV_AUTH_INVITES_JSON", "{\"" + "a".repeat(64) + "\":\"2099-01-01T00:00:00Z\"}");
  }
  AuthSettings settings(MockEnvironment env) {return new AuthSettings(env, new ObjectMapper());}
  @Test void missingConfigurationStaysClosed() {
    var settings = settings(new MockEnvironment());
    assertFalse(settings.signupAvailable(NOW));
    assertEquals(AuthAudit.Reason.PROVIDER_TOKEN_MISSING, settings.signupAvailability(NOW));
  }
  @Test void configuredProviderAndInvitesEnableSignupWithoutExtraFlag() {
    assertTrue(settings(configured()).signupAvailable(NOW));
  }
  @Test void explicitOffRemainsOff() {
    var settings = settings(configured().withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "false"));
    assertFalse(settings.signupAvailable(NOW));
    assertEquals(AuthAudit.Reason.ENROLLMENT_DISABLED, settings.signupAvailability(NOW));
  }
  @Test void explicitOnWithoutProviderFailsStartup() {
    assertThrows(IllegalStateException.class, () -> settings(new MockEnvironment()
        .withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "true")));
  }
  @Test void missingInvitesStayClosed() {
    var settings = settings(new MockEnvironment().withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "true")
        .withProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "test-token"));
    assertFalse(settings.signupAvailable(NOW));
    assertEquals(AuthAudit.Reason.INVITES_NOT_CONFIGURED, settings.signupAvailability(NOW));
  }
  @Test void expiredInvitesStayClosed() {
    var settings = settings(configured());
    assertFalse(settings.signupAvailable(Instant.parse("2100-01-01T00:00:00Z")));
    assertEquals(AuthAudit.Reason.INVITES_EXPIRED, settings.signupAvailability(Instant.parse("2100-01-01T00:00:00Z")));
  }
}
