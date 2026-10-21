package dev.irv.authonly;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class IngressSecretTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static Stream<String> validSecrets() {
    return Stream.of(
        "a".repeat(31) + "!",
        "Ingress-key_37!@#$%^&*()-+=1234567890",
        "0123456789abcdef".repeat(4),
        "~".repeat(256));
  }

  static Stream<String> invalidSecrets() {
    return Stream.of(
        "a".repeat(31),
        "a".repeat(257),
        "a".repeat(31) + " ",
        "a".repeat(31) + "\t",
        "a".repeat(31) + "\n",
        "a".repeat(31) + "\u001f",
        "a".repeat(31) + "\u00e9");
  }

  AuthSettings settings(String secret) {
    MockEnvironment env = new MockEnvironment();
    if (secret != null) env.setProperty("IRV_AUTH_INGRESS_SECRET", secret);
    return new AuthSettings(env, JSON);
  }

  MockHttpServletRequest request(String... supplied) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setLocalPort(8081);
    request.setRemoteAddr("127.0.0.1");
    for (String value : supplied) request.addHeader("X-Irv-Ingress-Key", value);
    return request;
  }

  @ParameterizedTest
  @MethodSource("validSecrets")
  void acceptsConfiguredSecretsAcrossAllowedLengths(String secret) {
    assertEquals(secret, settings(secret).ingressSecret);
  }

  @ParameterizedTest
  @MethodSource("invalidSecrets")
  void rejectsConfiguredSecretsOutsidePrintableAsciiRequirements(String secret) {
    IllegalStateException error = assertThrows(IllegalStateException.class, () -> settings(secret));
    assertEquals(
        "IRV_AUTH_INGRESS_SECRET must be 32-256 printable ASCII characters (U+0021 through U+007E), with no whitespace",
        error.getMessage());
    assertFalse(error.getMessage().contains(secret));
  }

  @Test
  void missingAndEmptyConfigurationRemainFailClosedAndNotConfigured() {
    for (String secret : new String[] {null, ""}) {
      AuthSettings settings = settings(secret);
      assertEquals("", settings.ingressSecret);
      assertFalse(new CertificateGate(settings).trusted(request()));
    }
  }

  @Test
  void requiresOneExactlyMatchingPrintableAsciiHeader() {
    String secret = "Mixed-Case_ingress-key!1234567890";
    CertificateGate gate = new CertificateGate(settings(secret));
    assertTrue(gate.trusted(request(secret)));
    assertFalse(gate.trusted(request()));
    assertFalse(gate.trusted(request(secret, secret)));
    assertFalse(gate.trusted(request(secret + "!")));
    assertFalse(gate.trusted(request(secret.toLowerCase())));
  }

  @Test
  void rejectsNonAsciiHeaderBeforeLossyAsciiEncoding() {
    CertificateGate gate = new CertificateGate(settings("?".repeat(32)));
    assertFalse(gate.trusted(request("\u00e9".repeat(32))));
  }
}
