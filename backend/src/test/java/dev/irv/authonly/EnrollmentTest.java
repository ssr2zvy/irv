package dev.irv.authonly;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;

class EnrollmentTest {
  @TempDir Path temp;
  static final String CODE = "x".repeat(43), SECRET = "a".repeat(64), FP = "b".repeat(64);
  static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  static final ObjectMapper JSON = new ObjectMapper();
  MockEnvironment env() {
    return new MockEnvironment().withProperty("IRV_AUTH_INGRESS_SECRET", SECRET)
        .withProperty("IRV_AUTH_ENROLLMENT_ENABLED", "true")
        .withProperty("IRV_ZITADEL_ENROLLMENT_TOKEN", "test-only-token")
        .withProperty("IRV_AUTH_INVITE_DB", temp.resolve("auth/invites").toString())
        .withProperty("IRV_AUTH_INVITES_JSON", "{\"" + Enrollment.hash(CODE) + "\":\"2026-10-17T00:00:00Z\"}");
  }
  MockHttpServletRequest request() {
    MockHttpServletRequest r = new MockHttpServletRequest(); r.setLocalPort(8081); r.setRemoteAddr("127.0.0.1");
    r.addHeader("X-Irv-Ingress-Key", SECRET); r.addHeader("X-Irv-Cert-Verified", "true");
    r.addHeader("X-Irv-Cert-Revoked", "false"); r.addHeader("X-Irv-Cert-SHA256", FP); r.getSession(); return r;
  }
  static class Provider extends EnrollmentProvider {
    final List<String> paths = new ArrayList<>(); final List<JsonNode> bodies = new ArrayList<>();
    boolean fail;
    Provider(AuthSettings settings) { super(settings, JSON); }
    @Override protected JsonNode call(String path, Object body) {
      paths.add(path); bodies.add(JSON.valueToTree(body));
      if (fail) throw new IllegalStateException("test provider timeout");
      if (path.equals("/v2/users/new")) return JSON.valueToTree(Map.of("id", "123"));
      if (path.endsWith("registration_link")) return JSON.valueToTree(Map.of("code", Map.of("id", "code-id", "code", "secret+value")));
      return JSON.createObjectNode();
    }
  }
  Enrollment service(AuthSettings s, InviteLedger ledger, Provider p) {
    return new Enrollment(s, new CertificateGate(s), ledger, p, Clock.fixed(NOW, ZoneOffset.UTC));
  }
  String signup(Enrollment e, MockHttpServletRequest r) { return e.begin(r, CODE, "surya", "contact@example.com", "Given", "Family"); }

  @Test void auditRecordsEachProviderFailureAndNoEnrollmentCredentials() throws Exception {
    for (String stage : List.of("user_create", "enrollment_link", "role_grant")) {
      var configuration = env().withProperty("IRV_AUTH_INVITE_DB", temp.resolve(stage + "/invites").toString());
      AuthSettings settings = new AuthSettings(configuration, JSON);
      Provider provider = new Provider(settings) {
        @Override protected JsonNode call(String path, Object body) {
          if ((stage.equals("user_create") && path.endsWith("/new"))
              || (stage.equals("enrollment_link") && path.endsWith("registration_link"))
              || (stage.equals("role_grant") && path.endsWith("/grants")))
            throw new IllegalStateException("provider-exception-secret");
          return super.call(path, body);
        }
      };
      try (InviteLedger ledger = new InviteLedger(settings); AuditCapture logs = new AuditCapture()) {
        Enrollment e = service(settings, ledger, provider);
        var req = request(); req.setMethod("POST"); req.setRequestURI("/auth/signup");
        var response = new MockHttpServletResponse();
        new AuthAuditConfiguration.AuditFilter().doFilter(req, response, (r, res) -> {
          var error = assertThrows(ResponseStatusException.class, () -> signup(e, req));
          response.setStatus(error.getStatusCode().value());
        });
        assertEquals(502, response.getStatus());
        logs.has(stage, "failed", "provider_or_response_failure");
        logs.has("signup", "failed", "review_required");
        logs.excludes(CODE, SECRET, FP, "contact@example.com", "surya", "secret+value", "secret%2Bvalue", "provider-exception-secret");
      }
    }
  }

  @Test void auditDistinguishesRemovedCertificatePolicyFromFailedCertificate() throws Exception {
    AuthSettings settings = new AuthSettings(env().withProperty("IRV_AUTH_CERT_REQUIRED", "false"), JSON);
    try (AuditCapture logs = new AuditCapture()) {
      var req = request(); req.setMethod("GET"); req.setRequestURI("/auth/csrf");
      new AuthAuditConfiguration.AuditFilter().doFilter(req, new MockHttpServletResponse(), (r,s) -> {
        assertEquals("", new CertificateGate(settings).approved(req));
      });
      logs.has("certificate", "not_required", "policy_disabled");
      assertFalse(logs.rows().stream().anyMatch(r -> r.path("event").asText().equals("certificate") && r.path("outcome").asText().equals("passed")));
    }
  }

  @Test void provisionsPasswordlessAccountAndRoleThenResumesOnlySameSessionCertificate() throws Exception {
    verifyProvisioningAndReplay(env());
  }
  @Test void plaintextInviteProvisionsAndCannotReplayAfterRestart() throws Exception {
    verifyProvisioningAndReplay(env().withProperty("IRV_AUTH_INVITES_JSON", "{}")
        .withProperty("IRV_AUTH_INVITE_CODES_JSON", "[{\"code\":\"" + CODE + "\",\"expiresAt\":\"2026-10-17T00:00:00Z\"}]"));
  }
  @Test void singleSecretProvisionsAndCannotReplayAfterRestart() throws Exception {
    verifyProvisioningAndReplay(env().withProperty("IRV_AUTH_INVITES_JSON", "{}")
        .withProperty("IRV_AUTH_INVITE_CODE", CODE));
  }
  @Test void expiredSingleSecretNeverCallsProvider() throws Exception {
    AuthSettings s = new AuthSettings(env().withProperty("IRV_AUTH_INVITES_JSON", "{}")
        .withProperty("IRV_AUTH_INVITE_CODE", CODE), JSON);
    Provider provider = new Provider(s);
    try (InviteLedger ledger = new InviteLedger(s, Clock.fixed(NOW.minusSeconds(7200), ZoneOffset.UTC))) {
      assertThrows(ResponseStatusException.class, () -> signup(service(s, ledger, provider), request()));
      assertTrue(provider.paths.isEmpty());
    }
  }
  void verifyProvisioningAndReplay(MockEnvironment environment) throws Exception {
    AuthSettings s = new AuthSettings(environment, JSON); Provider p = new Provider(s);
    try (InviteLedger ledger = new InviteLedger(s, Clock.fixed(NOW, ZoneOffset.UTC))) {
      Enrollment e = service(s, ledger, p); MockHttpServletRequest r = request();
      String link = signup(e, r);
      assertTrue(link.startsWith(AuthSettings.ISSUER + "/ui/v2/login/passkey/set?"));
      assertTrue(link.contains("secret%2Bvalue")); assertEquals(link, e.resume(r));
      assertEquals(List.of("/v2/users/new", "/v2/users/123/passkeys/registration_link", "/management/v1/users/123/grants"), p.paths);
      assertEquals("surya", p.bodies.get(0).path("username").asText());
      assertFalse(p.bodies.get(0).path("human").has("password"));
      assertTrue(p.bodies.get(0).path("human").path("email").path("isVerified").asBoolean());
      assertEquals(AuthSettings.PROJECT, p.bodies.get(2).path("projectId").asText());
      assertNull(r.getSession().getAttribute(BoundSessions.KEY)); // Enrollment is not authentication.
      r.removeHeader("X-Irv-Cert-SHA256"); r.addHeader("X-Irv-Cert-SHA256", "c".repeat(64));
      assertThrows(ResponseStatusException.class, () -> e.resume(r));
      assertThrows(ResponseStatusException.class, () -> signup(e, request())); assertEquals(3, p.paths.size());
    }
    try (InviteLedger reopened = new InviteLedger(s)) {
      assertFalse(reopened.reserve(Enrollment.hash(CODE), NOW)); // Redeploy cannot replay invite.
    }
  }
  @Test void concurrentInviteClaimsHaveOneWinner() throws Exception {
    AuthSettings s = new AuthSettings(env(), JSON);
    try (InviteLedger ledger = new InviteLedger(s)) {
      ExecutorService pool = Executors.newFixedThreadPool(8);
      try {
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) calls.add(() -> ledger.reserve(Enrollment.hash(CODE), NOW));
        int winners = 0; for (Future<Boolean> f : pool.invokeAll(calls)) if (f.get()) winners++;
        assertEquals(1, winners);
      } finally { pool.shutdownNow(); }
    }
  }
  @Test void expiredAndInvalidInvitesNeverReachProviderAndAmbiguousFailureCannotReplay() throws Exception {
    AuthSettings s = new AuthSettings(env().withProperty("IRV_AUTH_INVITES_JSON", "{\"" + Enrollment.hash(CODE) + "\":\"2026-10-15T00:00:00Z\"}"), JSON); Provider p = new Provider(s);
    try (InviteLedger ledger = new InviteLedger(s)) {
      assertThrows(ResponseStatusException.class, () -> signup(service(s, ledger, p), request()));
      assertTrue(p.paths.isEmpty());
    }
    AuthSettings next = new AuthSettings(env(), JSON); Provider failing = new Provider(next); failing.fail = true;
    try (InviteLedger ledger = new InviteLedger(next)) {
      Enrollment e = service(next, ledger, failing);
      assertEquals(502, assertThrows(ResponseStatusException.class, () -> signup(e, request())).getStatusCode().value());
      assertThrows(ResponseStatusException.class, () -> signup(e, request())); assertEquals(1, failing.paths.size());
    }
  }
  @Test void certificatePolicyCanBeRemovedWithoutRemovingInvitesOrTrustedIngress() throws Exception {
    AuthSettings s = new AuthSettings(env().withProperty("IRV_AUTH_CERT_REQUIRED", "false"), JSON);
    CertificateGate gate = new CertificateGate(s); MockHttpServletRequest r = request();
    r.removeHeader("X-Irv-Cert-SHA256"); r.removeHeader("X-Irv-Cert-Verified"); r.removeHeader("X-Irv-Cert-Revoked");
    assertEquals("", gate.approved(r));
    try (InviteLedger ledger = new InviteLedger(s)) {
      Provider p = new Provider(s); Enrollment e = service(s, ledger, p);
      assertThrows(ResponseStatusException.class, () -> e.begin(r, "z".repeat(43), "surya", "c@example.com", "Given", "Family"));
      assertTrue(p.paths.isEmpty());
      assertNotNull(signup(e, r));
    }
    r.removeHeader("X-Irv-Ingress-Key"); assertNull(gate.approved(r));
  }
}
