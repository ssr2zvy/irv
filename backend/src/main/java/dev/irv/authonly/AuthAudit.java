package dev.irv.authonly;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;

/** Closed-schema events: no credentials, arbitrary strings, exception text or request payloads. */
final class AuthAudit {
  enum Stage { REQUEST, ROUTE, INGRESS, CERTIFICATE, ORIGIN, CSRF, LOGIN, CALLBACK,
    TRANSACTION, TOKEN_EXCHANGE, ID_TOKEN, OIDC, PASSKEY, FRESHNESS, ROLE, SUBJECT,
    SESSION, AUTHORIZATION, ACTIVITY, LOGOUT, SIGNUP, INPUT, INVITE, USER_CREATE,
    ENROLLMENT_LINK, ROLE_GRANT, ENROLLMENT_RESUME }
  enum Outcome { STARTED, PASSED, FAILED, NOT_REQUIRED, COMPLETED }
  enum Reason { NONE, VALID, NOT_CONFIGURED, UNTRUSTED_CONNECTION, INVALID_SECRET,
    NOT_VERIFIED, REVOKED_OR_UNKNOWN, INVALID_FINGERPRINT, POLICY_DISABLED,
    METHOD_NOT_ALLOWED, ROUTE_NOT_EXPOSED, FIREWALL_REJECTED, INVALID_ORIGIN,
    CSRF_REJECTED, MISSING_SESSION, MISSING_BINDING, CERTIFICATE_MISMATCH,
    IDLE_EXPIRED, ABSOLUTE_EXPIRED, INVALID_IDENTITY, ACCESS_DENIED, INVALID_STATE,
    TRANSACTION_MISSING, TRANSACTION_EXPIRED, PROVIDER_OR_RESPONSE_FAILURE,
    TOKEN_VALIDATION_FAILED, LOGIN_VALIDATION_FAILED, PASSKEY_EVIDENCE_REJECTED,
    FRESH_AUTHENTICATION_REQUIRED, REQUIRED_ROLE_MISSING, INVALID_SUBJECT,
    SESSION_CREATED, SESSION_INVALIDATED, SESSION_REPLACED, USER_LOGOUT,
    ENROLLMENT_DISABLED, ALREADY_SIGNED_IN, RATE_LIMITED, INVALID_INPUT,
    INVITE_INVALID_OR_EXPIRED, INVITE_ALREADY_RESERVED, LEDGER_FAILURE,
    INVITE_RESERVED, REVIEW_REQUIRED, RESUME_UNAVAILABLE, UNEXPECTED_ERROR,
    HTTP_REJECTED, HTTP_COMPLETED, ASYNC_TIMEOUT }
  static final String FLOW = AuthAudit.class.getName() + ".flow";
  static final String SESSION = AuthAudit.class.getName() + ".session";
  static final String CONTEXT = AuthAudit.class.getName() + ".context";
  static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("irv.auth.audit");
  private static final Set<String> ROUTES = Set.of("/", "/api/health", "/auth/status", "/auth/diagnostics", "/auth/app.js",
      "/auth/style.css", "/auth/csrf", "/auth/protected", "/auth/activity", "/auth/logout",
      "/auth/signup", "/auth/signup/resume", "/oauth2/authorization/zitadel", "/login/oauth2/code/zitadel");

  static final class Context {
    final String requestId = UUID.randomUUID().toString();
    final long started = System.nanoTime();
    final String method;
    String route, flow, session;
    volatile Reason asyncFailure;
    final AtomicBoolean completed = new AtomicBoolean();
    final Set<String> emitted = new HashSet<>();
    Context(HttpServletRequest request) {
      method = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE").contains(request.getMethod()) ? request.getMethod() : "OTHER";
      route = ROUTES.contains(request.getRequestURI()) ? request.getRequestURI() : "<unmapped>";
      HttpSession s = request.getSession(false);
      try { if (s != null) { flow = reference(s.getAttribute(FLOW)); session = reference(s.getAttribute(SESSION)); } }
      catch (IllegalStateException invalidatedConcurrently) { /* No authenticated logging reference. */ }
    }
  }
  // Only server-generated UUID logging references; never servlet session IDs.
  private static String reference(Object value) {
    return value instanceof UUID id ? id.toString() : null;
  }
  static void newFlow(HttpServletRequest request) {
    UUID id = UUID.randomUUID(); request.getSession().setAttribute(FLOW, id);
    Context c = CURRENT.get(); if (c != null) { c.flow = id.toString(); c.session = reference(request.getSession().getAttribute(SESSION)); }
  }
  static void newSession(HttpServletRequest request) {
    UUID id = UUID.randomUUID(); request.getSession().setAttribute(SESSION, id);
    Context c = CURRENT.get(); if (c != null) c.session = id.toString();
  }
  static void audit(Stage stage, Outcome outcome, Reason reason) {
    Context c = CURRENT.get();
    if (c != null) write(c, stage, outcome, reason, null, null);
  }
  static void finish(Context c, HttpServletRequest request, HttpServletResponse response, Reason failure) {
    if (!c.completed.compareAndSet(false, true)) return;
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    // Framework route template only; never URI/query/header fallbacks for unmatched requests.
    if (pattern instanceof String s && s.length() <= 256 && s.startsWith("/")
        && s.chars().noneMatch(Character::isISOControl)) c.route = s;
    int status = failure != null && response.getStatus() < 400 ? 500 : response.getStatus();
    write(c, Stage.REQUEST, status >= 400 ? Outcome.FAILED : Outcome.COMPLETED,
        failure != null ? failure : status >= 400 ? Reason.HTTP_REJECTED : Reason.HTTP_COMPLETED,
        status, (System.nanoTime() - c.started) / 1_000_000);
  }
  private static void write(Context c, Stage stage, Outcome outcome, Reason reason, Integer status, Long duration) {
    synchronized (c) {
      // Gate/session helpers may run several times for one request; keep each distinct result once.
      if (!c.emitted.add(stage + ":" + outcome + ":" + reason)) return;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("schema", "irv.auth.v1"); row.put("timestamp", Instant.now().toString());
      row.put("event", stage.name().toLowerCase(Locale.ROOT));
      row.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
      row.put("reason", reason.name().toLowerCase(Locale.ROOT));
      row.put("request_id", c.requestId); row.put("method", c.method); row.put("route", c.route);
      if (c.flow != null) row.put("flow_id", c.flow);
      if (c.session != null) row.put("session_ref", c.session);
      if (status != null) row.put("status", status);
      if (duration != null) row.put("duration_ms", duration);
      try { LOG.info(JSON.writeValueAsString(row)); }
      catch (Exception ignored) { /* Logging must not alter authentication or expose serialization input. */ }
    }
  }
}
