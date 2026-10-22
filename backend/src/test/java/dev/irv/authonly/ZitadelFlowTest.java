package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
    "IRV_AUTH_INGRESS_SECRET=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "IRV_TUNNEL_ENABLED=false", "IRV_AUTH_ENROLLMENT_ENABLED=true", "IRV_ZITADEL_ENROLLMENT_TOKEN=test-token"})
@AutoConfigureMockMvc
@Import(ZitadelFlowTest.ProviderConfig.class)
class ZitadelFlowTest {
  static final String SECRET = "a".repeat(64), FP = "b".repeat(64), OTHER = "c".repeat(64);
  static final ObjectMapper JSON = new ObjectMapper();
  @org.springframework.test.context.DynamicPropertySource
  static void registrationProperties(org.springframework.test.context.DynamicPropertyRegistry r) throws Exception {
    String db = java.nio.file.Files.createTempDirectory("irv-signup-test-").resolve("auth/invites").toString();
    r.add("IRV_AUTH_INVITE_DB", () -> db);
    r.add("IRV_AUTH_INVITES_JSON", () -> "{\"" + Enrollment.hash("i".repeat(43)) + "\":\"2099-01-01T00:00:00Z\"}");
  }
  static final RSAKey KEY;
  static final HttpServer PROVIDER;
  static volatile String nonce, challenge, mode = "valid";
  static volatile boolean pkceVerified;
  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      PROVIDER.createContext("/keys", exchange -> {
        byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
      });
      PROVIDER.createContext("/token", exchange -> {
        try {
          Map<String, String> form = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String verifier = form.get("code_verifier");
          pkceVerified = verifier != null && Base64.getUrlEncoder().withoutPadding().encodeToString(
              MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))).equals(challenge)
              && !form.containsKey("client_secret") && "authorization_code".equals(form.get("grant_type"))
              && AuthSettings.CLIENT.equals(form.get("client_id"));
          if (!pkceVerified) throw new IllegalStateException("PKCE failed");
          Instant now = Instant.now();
          var claims = new JWTClaimsSet.Builder().issuer(mode.equals("issuer") ? "https://wrong.invalid" : AuthSettings.ISSUER)
              .subject(mode.equals("subject") ? "invalid" : mode.equals("another") ? "456" : "123")
              .audience(mode.equals("audience") ? "wrong-client" : AuthSettings.CLIENT)
              .issueTime(Date.from(now)).expirationTime(Date.from(mode.equals("expired") ? now.minusSeconds(120) : now.plusSeconds(300)))
              .claim("nonce", mode.equals("nonce") ? "wrong-nonce" : nonce)
              .claim("auth_time", now.minusSeconds(mode.equals("stale") ? 120 : 0).getEpochSecond())
              .claim("amr", mode.equals("password") ? List.of("pwd", "mfa", "user") : mode.equals("u2f") ? List.of("user") : List.of("user", "mfa"))
              .claim("urn:zitadel:iam:org:project:roles", mode.equals("role") ? Map.of() : Map.of(AuthSettings.ROLE, Map.of(AuthSettings.ORG, "irv-users"))).build();
          SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
          jwt.sign(new RSASSASigner(mode.equals("signature") ? new RSAKeyGenerator(2048).generate() : KEY));
          byte[] bytes = JSON.writeValueAsBytes(Map.of("access_token", "test-access-token", "token_type", "Bearer", "expires_in", 300, "id_token", jwt.serialize()));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        } catch (Exception ex) { exchange.sendResponseHeaders(400, -1); }
        finally { exchange.close(); }
      });
      PROVIDER.start();
    } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
  }

  @TestConfiguration
  static class ProviderConfig {
    @Bean @Primary EnrollmentProvider fakeEnrollment(AuthSettings settings) {
      return new EnrollmentTest.Provider(settings);
    }
    @Bean @Primary ClientRegistrationRepository testClients() {
      String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
      return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("zitadel")
          .clientId(AuthSettings.CLIENT).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri(AuthSettings.ORIGIN + "/login/oauth2/code/zitadel").scope("openid", "profile")
          .authorizationUri(base + "/authorize").tokenUri(base + "/token").jwkSetUri(base + "/keys")
          .issuerUri(AuthSettings.ISSUER).userNameAttributeName("sub").build());
    }
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.context.ApplicationContext context;
  @BeforeEach void reset() { mode = "valid"; pkceVerified = false; }
  @AfterAll static void stop() { PROVIDER.stop(0); }

  static Map<String, String> params(String query) {
    Map<String, String> result = new HashMap<>();
    for (String part : query.split("&")) {
      String[] kv = part.split("=", 2);
      result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
    }
    return result;
  }
  MockHttpServletRequestBuilder req(MockHttpServletRequestBuilder builder) {
    return builder.secure(true).with(r -> { r.setLocalPort(8081); r.setRemoteAddr("127.0.0.1"); return r; })
        .header("X-Irv-Ingress-Key", SECRET).header("X-Irv-Cert-Verified", "true")
        .header("X-Irv-Cert-Revoked", "false").header("X-Irv-Cert-SHA256", FP)
        .header("Origin", AuthSettings.ORIGIN);
  }
  record Start(MockHttpSession session, String state) {}
  Start start() throws Exception {
    MvcResult csrf = mvc.perform(req(get("/auth/csrf"))).andReturn();
    assertEquals(200, csrf.getResponse().getStatus());
    MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
    String token = mapper.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    MvcResult redirect = mvc.perform(req(post("/oauth2/authorization/zitadel")).session(session).param("_csrf", token)).andReturn();
    assertEquals(302, redirect.getResponse().getStatus());
    Map<String, String> query = params(URI.create(redirect.getResponse().getHeader("Location")).getRawQuery());
    assertEquals("S256", query.get("code_challenge_method"));
    assertEquals("login", query.get("prompt")); assertEquals("0", query.get("max_age"));
    assertNotNull(query.get("nonce")); assertNotNull(query.get("state"));
    challenge = query.get("code_challenge"); nonce = query.get("nonce");
    return new Start((MockHttpSession) redirect.getRequest().getSession(false), query.get("state"));
  }
  MvcResult finish(Start start, String state) throws Exception {
    return mvc.perform(req(get("/login/oauth2/code/zitadel")).session(start.session()).param("code", "test-code").param("state", state)).andReturn();
  }
  MockHttpSession login() throws Exception {
    Start start = start(); String before = start.session().getId();
    MvcResult result = finish(start, start.state());
    assertEquals(303, result.getResponse().getStatus()); assertEquals("/", result.getResponse().getHeader("Location"));
    assertTrue(pkceVerified);
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    assertNotEquals(before, session.getId()); // Fixation protection.
    return session;
  }

  // Exercise the real security chain without the feature boundary.
  // No production matcher names this controller's routes.
  @org.springframework.web.bind.annotation.RestController
  static class FutureEndpoint {
    @org.springframework.web.bind.annotation.RequestMapping("/future/operation")
    String operation() { return "reached"; }
  }

  @Test void unnamedFutureEndpointRequiresBoundSessionByDefault() throws Exception {
    MockMvc future = org.springframework.test.web.servlet.setup.MockMvcBuilders
        .standaloneSetup(new FutureEndpoint())
        .addFilters(context.getBean(org.springframework.security.web.FilterChainProxy.class)).build();
    assertEquals(401, future.perform(req(get("/future/operation"))).andReturn().getResponse().getStatus());
    MockHttpSession valid = login();
    assertEquals(200, future.perform(req(get("/future/operation")).session(valid)).andReturn().getResponse().getStatus());
    // The unchanged runtime boundary must still reject it, even after a successful login.
    assertEquals(404, mvc.perform(req(get("/future/operation")).session(valid)).andReturn().getResponse().getStatus());
    assertEquals(403, future.perform(req(post("/future/operation")).session(valid)).andReturn().getResponse().getStatus());
    MvcResult csrf = mvc.perform(req(get("/auth/csrf")).session(valid)).andReturn();
    String token = mapper.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
    assertEquals(200, future.perform(req(post("/future/operation")).session(valid).header("X-CSRF-TOKEN", token)).andReturn().getResponse().getStatus());
    assertEquals(403, future.perform(req(post("/future/operation")).session(valid).header("X-CSRF-TOKEN", token)
        .with(r -> { r.removeHeader("Origin"); return r; })).andReturn().getResponse().getStatus());
    assertEquals(403, future.perform(req(get("/future/operation")).session(valid)
        .with(r -> { r.removeHeader("X-Irv-Cert-SHA256"); r.addHeader("X-Irv-Cert-SHA256", OTHER); return r; })).andReturn().getResponse().getStatus());
    assertTrue(valid.isInvalid());
    for (String fault : List.of("idle", "absolute", "missing-binding", "subject", "ingress", "revoked")) {
      MockHttpSession session = login();
      var b = (BoundSessions.Bound) session.getAttribute(BoundSessions.KEY);
      if (fault.equals("missing-binding")) session.removeAttribute(BoundSessions.KEY);
      if (fault.equals("idle")) session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound(b.subject(), FP, b.created(), Instant.now().minusSeconds(301)));
      if (fault.equals("absolute")) session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound(b.subject(), FP, Instant.now().minusSeconds(28801), b.activity()));
      if (fault.equals("subject")) session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound("456", FP, b.created(), b.activity()));
      assertEquals(403, future.perform(req(get("/future/operation")).session(session).with(r -> {
        if (fault.equals("ingress")) r.removeHeader("X-Irv-Ingress-Key");
        if (fault.equals("revoked")) { r.removeHeader("X-Irv-Cert-Revoked"); r.addHeader("X-Irv-Cert-Revoked", "true"); }
        return r;
      })).andReturn().getResponse().getStatus(), fault);
    }
  }

  @Test void auditCorrelatesLoginAndSessionWithoutCredentials() throws Exception {
    try (AuditCapture logs = new AuditCapture()) {
      MockHttpSession session = login();
      var probe = mvc.perform(req(get("/auth/protected")).session(session)
          .header("X-Irv-Request-Id", "attacker-correlation-secret")).andReturn();
      assertEquals(200, probe.getResponse().getStatus());
      logs.has("transaction", "passed", "valid");
      logs.has("token_exchange", "passed", "valid");
      logs.has("id_token", "passed", "valid");
      logs.has("oidc", "passed", "valid");
      for (String stage : List.of("passkey", "freshness", "role", "subject")) logs.has(stage, "passed", "valid");
      logs.has("session", "completed", "session_created");
      logs.has("request", "completed", "http_completed");
      var rows = logs.rows();
      String flow = rows.stream().filter(r -> r.path("event").asText().equals("login")
          && r.path("outcome").asText().equals("started")).findFirst().orElseThrow().path("flow_id").asText();
      assertFalse(flow.isEmpty());
      assertTrue(rows.stream().anyMatch(r -> r.path("event").asText().equals("callback") && r.path("flow_id").asText().equals(flow)));
      String ref = session.getAttribute(AuthAudit.SESSION).toString();
      assertNotEquals(session.getId(), ref);
      assertTrue(rows.stream().anyMatch(r -> r.path("route").asText().equals("/auth/protected")
          && r.path("session_ref").asText().equals(ref)));
      String requestId = probe.getResponse().getHeader("X-Irv-Request-Id");
      assertNotNull(requestId); assertNotEquals("attacker-correlation-secret", requestId);
      assertEquals(1, rows.stream().filter(r -> r.path("request_id").asText().equals(requestId)
          && r.has("status")).count());
      logs.excludes(SECRET, FP, "test-access-token", "test-code", nonce, challenge, "attacker-correlation-secret");
      // Mock servlet IDs are short counters, which can occur inside timestamps/UUIDs.
      for (var row : rows) row.elements().forEachRemaining(value -> { if (value.isTextual()) assertNotEquals(session.getId(), value.asText()); });
    }
  }

  @Test void auditCoversEarlyRejectionsAndRealValidationFailures() throws Exception {
    try (AuditCapture logs = new AuditCapture()) {
      mvc.perform(req(get("/unmapped-path-secret")).queryParam("code", "query-secret"));
      logs.has("route", "failed", "route_not_exposed");
      mvc.perform(req(get("/auth/signup")));
      logs.has("route", "failed", "method_not_allowed");
      mvc.perform(req(post("/auth/signup")));
      logs.has("csrf", "failed", "csrf_rejected");
      mvc.perform(req(post("/auth/signup")).with(r -> { r.removeHeader("Origin"); return r; }));
      logs.has("origin", "failed", "invalid_origin");
      mvc.perform(req(get("/auth/protected")).with(r -> { r.removeHeader("X-Irv-Ingress-Key"); return r; }));
      logs.has("ingress", "failed", "invalid_secret");
      mvc.perform(req(get("/auth/protected")).with(r -> { r.removeHeader("X-Irv-Cert-Revoked"); r.addHeader("X-Irv-Cert-Revoked", "true"); return r; }));
      logs.has("certificate", "failed", "revoked_or_unknown");
      for (String bad : List.of("signature", "nonce", "password", "stale", "role")) {
        mode = bad; Start start = start(); finish(start, start.state());
      }
      logs.has("id_token", "failed", "token_validation_failed");
      logs.has("oidc", "failed", "login_validation_failed");
      logs.has("passkey", "failed", "passkey_evidence_rejected");
      logs.has("freshness", "failed", "fresh_authentication_required");
      logs.has("role", "failed", "required_role_missing");
      logs.excludes(SECRET, FP, "unmapped-path-secret", "query-secret", "test-access-token", "test-code");
      for (var row : logs.rows()) if (row.has("status")) assertTrue(row.path("duration_ms").asLong() >= 0);
    }
  }

  @Test void auditRecordsTransactionExpirySessionExpiryActivityAndLogout() throws Exception {
    try (AuditCapture logs = new AuditCapture()) {
      Start invalid = start(); finish(invalid, "incorrect-state");
      logs.has("transaction", "failed", "invalid_state");
      Start expired = start();
      var pending = (BoundAuthorizationRequests.Pending) expired.session().getAttribute(BoundAuthorizationRequests.KEY);
      expired.session().setAttribute(BoundAuthorizationRequests.KEY, new BoundAuthorizationRequests.Pending(
          pending.request(), pending.fingerprint(), Instant.now().minusSeconds(301)));
      finish(expired, expired.state());
      logs.has("transaction", "failed", "transaction_expired");
      MockHttpSession session = login();
      var bound = (BoundSessions.Bound) session.getAttribute(BoundSessions.KEY);
      session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound(bound.subject(), FP, bound.created(), Instant.now().minusSeconds(301)));
      mvc.perform(req(get("/auth/protected")).session(session));
      logs.has("session", "failed", "idle_expired");
      session = login();
      session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound("123", FP, Instant.now().minusSeconds(28801), Instant.now()));
      mvc.perform(req(get("/auth/protected")).session(session));
      logs.has("session", "failed", "absolute_expired");
      session = login();
      MvcResult csrf = mvc.perform(req(get("/auth/csrf")).session(session)).andReturn();
      String token = mapper.readTree(csrf.getResponse().getContentAsString()).get("token").asText();
      mvc.perform(req(post("/auth/activity")).session(session).header("X-CSRF-TOKEN", token));
      logs.has("activity", "passed", "valid");
      mvc.perform(req(post("/auth/logout")).session(session).header("X-CSRF-TOKEN", token));
      logs.has("logout", "completed", "user_logout");
      logs.has("session", "completed", "session_invalidated");
      logs.excludes(token, SECRET, FP, "test-code", "test-access-token");
    }
  }

  @Test void validLoginProtectsBackendAndNeverDisplaysTokens() throws Exception {
    MockHttpSession session = login();
    assertEquals(200, mvc.perform(req(get("/auth/protected")).session(session)).andReturn().getResponse().getStatus());
    String report = mvc.perform(req(get("/auth/status")).session(session)).andReturn().getResponse().getContentAsString();
    assertTrue(mapper.readTree(report).get("authenticated").asBoolean());
    for (String secret : List.of(FP, SECRET, "test-access-token", "id_token", "test-code")) assertFalse(report.contains(secret));
  }
  @Test void sameCertificateCanStartSessionsForDifferentAccounts() throws Exception {
    MockHttpSession first = login();
    mode = "another";
    MockHttpSession second = login();
    assertEquals("123", ((BoundSessions.Bound) first.getAttribute(BoundSessions.KEY)).subject());
    assertEquals("456", ((BoundSessions.Bound) second.getAttribute(BoundSessions.KEY)).subject());
    assertEquals(200, mvc.perform(req(get("/auth/protected")).session(second)).andReturn().getResponse().getStatus());
  }
  @Test void signupRequiresCsrfAndTrustedOrigin() throws Exception {
    assertEquals(403, mvc.perform(req(post("/auth/signup")).contentType("application/x-www-form-urlencoded")).andReturn().getResponse().getStatus());
    assertEquals(405, mvc.perform(req(get("/auth/signup"))).andReturn().getResponse().getStatus());
    assertEquals(404, mvc.perform(req(post("/auth/signup")).with(r -> { r.setLocalPort(8080); return r; })).andReturn().getResponse().getStatus());
  }
  @Test void signupEndpointProvisionsAndKeepsUserUnauthenticatedUntilLogin() throws Exception {
    try (AuditCapture logs = new AuditCapture()) {
    MvcResult c = mvc.perform(req(get("/auth/csrf"))).andReturn();
    MockHttpSession session = (MockHttpSession) c.getRequest().getSession(false);
    String token = mapper.readTree(c.getResponse().getContentAsString()).get("token").asText();
    MvcResult created = mvc.perform(req(post("/auth/signup")).session(session).header("X-CSRF-TOKEN", token)
        .contentType("application/x-www-form-urlencoded").param("invite", "i".repeat(43))
        .param("username", "newuser").param("email", "new@example.com").param("givenName", "New").param("familyName", "User")).andReturn();
    assertEquals(200, created.getResponse().getStatus());
    assertTrue(mapper.readTree(created.getResponse().getContentAsString()).get("enrollmentUrl").asText().startsWith(AuthSettings.ISSUER));
    assertEquals(401, mvc.perform(req(get("/auth/protected")).session(session)).andReturn().getResponse().getStatus());
    assertEquals(200, mvc.perform(req(post("/auth/signup/resume")).session(session).header("X-CSRF-TOKEN", token)).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(req(post("/auth/signup/resume")).session(session).header("X-CSRF-TOKEN", token)
        .with(r -> { r.removeHeader("Origin"); r.addHeader("Origin", "https://wrong.invalid"); return r; })).andReturn().getResponse().getStatus());
    logs.has("invite", "passed", "invite_reserved");
    for (String stage : List.of("user_create", "enrollment_link", "role_grant")) logs.has(stage, "passed", "valid");
    logs.has("signup", "completed", "valid");
    logs.has("enrollment_resume", "passed", "valid");
    logs.excludes("i".repeat(43), "newuser", "new@example.com", "secret%2Bvalue", "secret+value", "test-token", SECRET, FP);
    }
  }
  @Test void rejectsBadClaimsAndSignature() throws Exception {
    for (String invalid : List.of("password", "u2f", "stale", "role", "subject", "issuer", "audience", "expired", "nonce", "signature")) {
      mode = invalid;
      Start start = start(); MvcResult result = finish(start, start.state());
      assertTrue(result.getResponse().getHeader("Location").startsWith("/?auth_error="), invalid);
      assertTrue(start.session().isInvalid(), invalid);
    }
  }
  @Test void wrongStateAndReplayedCallbackDenied() throws Exception {
    Start start = start(); MvcResult wrong = finish(start, "wrong");
    assertTrue(wrong.getResponse().getHeader("Location").contains("auth_error")); assertFalse(pkceVerified);
    MockHttpSession session = login();
    MvcResult replay = mvc.perform(req(get("/login/oauth2/code/zitadel")).session(session).param("code", "test-code").param("state", "old-state")).andReturn();
    assertTrue(replay.getResponse().getHeader("Location").contains("auth_error"));
  }
  @Test void callbackRequiresOriginalCertificate() throws Exception {
    Start start = start();
    MvcResult result = mvc.perform(req(get("/login/oauth2/code/zitadel")).session(start.session()).param("state", start.state()).param("code", "test-code")
        .with(r -> { r.removeHeader("X-Irv-Cert-SHA256"); r.addHeader("X-Irv-Cert-SHA256", OTHER); return r; })).andReturn();
    assertTrue(result.getResponse().getHeader("Location").contains("auth_error")); assertFalse(pkceVerified);
  }
  @Test void cookieWithDifferentApprovedCertificateIsDenied() throws Exception {
    MockHttpSession session = login();
    int status = mvc.perform(req(get("/auth/protected")).session(session)
        .with(r -> { r.removeHeader("X-Irv-Cert-SHA256"); r.addHeader("X-Irv-Cert-SHA256", OTHER); return r; })).andReturn().getResponse().getStatus();
    assertEquals(401, status); assertTrue(session.isInvalid());
  }
  @Test void forgedIngressRevokedCertAndBearerAloneDenied() throws Exception {
    assertEquals(401, mvc.perform(req(get("/auth/protected")).header("Authorization", "Bearer test-access-token")).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(req(get("/auth/protected")).with(r -> { r.removeHeader("X-Irv-Ingress-Key"); return r; })).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(req(get("/auth/csrf")).header("X-Irv-Cert-Verified", "true")).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(req(get("/auth/csrf")).with(r -> { r.removeHeader("X-Irv-Cert-Revoked"); r.addHeader("X-Irv-Cert-Revoked", "true"); return r; })).andReturn().getResponse().getStatus());
  }
  @Test void pollingDoesNotExtendExpiryAndBothLimitsEnforced() throws Exception {
    MockHttpSession session = login();
    var bound = (BoundSessions.Bound) session.getAttribute(BoundSessions.KEY);
    mvc.perform(req(get("/auth/status")).session(session));
    assertEquals(bound.activity(), ((BoundSessions.Bound) session.getAttribute(BoundSessions.KEY)).activity());
    session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound("123", FP, bound.created(), Instant.now().minusSeconds(301)));
    assertEquals(401, mvc.perform(req(get("/auth/protected")).session(session)).andReturn().getResponse().getStatus());
    session = login();
    session.setAttribute(BoundSessions.KEY, new BoundSessions.Bound("123", FP, Instant.now().minusSeconds(28801), Instant.now()));
    assertEquals(401, mvc.perform(req(get("/auth/protected")).session(session)).andReturn().getResponse().getStatus());
  }
  @Test void csrfAndOriginProtectLogoutAndActivity() throws Exception {
    MockHttpSession session = login();
    assertEquals(403, mvc.perform(req(post("/auth/logout")).session(session)).andReturn().getResponse().getStatus());
    MvcResult c = mvc.perform(req(get("/auth/csrf")).session(session)).andReturn();
    String token = mapper.readTree(c.getResponse().getContentAsString()).get("token").asText();
    assertEquals(403, mvc.perform(req(post("/auth/activity")).session(session).header("X-CSRF-TOKEN", token)
        .with(r -> { r.removeHeader("Origin"); r.addHeader("Origin", "https://evil.invalid"); return r; })).andReturn().getResponse().getStatus());
    assertEquals(204, mvc.perform(req(post("/auth/activity")).session(session).header("X-CSRF-TOKEN", token)).andReturn().getResponse().getStatus());
    assertEquals(204, mvc.perform(req(post("/auth/logout")).session(session).header("X-CSRF-TOKEN", token)).andReturn().getResponse().getStatus());
    assertTrue(session.isInvalid());
  }
  @Test void legacySurfacesAndHealthPortStayIsolated() throws Exception {
    for (String path : List.of("/api/sandboxes", "/api/login", "/ws", "/agent", "/api/health", "/error"))
      assertEquals(404, mvc.perform(req(get(path))).andReturn().getResponse().getStatus());
    assertEquals(404, mvc.perform(req(get("/auth/protected")).with(r -> { r.setLocalPort(8080); return r; })).andReturn().getResponse().getStatus());
    for (String name : List.of("auth", "authApi", "sandboxApi", "db", "fly", "maintenance")) assertFalse(context.containsBean(name));
  }
}
