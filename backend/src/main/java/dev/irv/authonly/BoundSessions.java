package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.*;

/** In-memory servlet sessions. Restart/deploy revokes every session. Never persist tokens to disk. */
final class BoundSessions {
  static final String KEY = BoundSessions.class.getName();
  static final Duration IDLE = Duration.ofMinutes(5), ABSOLUTE = Duration.ofHours(8);
  record Bound(String subject, String fingerprint, Instant created, Instant activity) {}
  private final CertificateGate gate;
  private final Clock clock;
  BoundSessions(AuthSettings settings, CertificateGate gate, Clock clock) {
    this.gate = gate; this.clock = clock;
  }
  Bound current(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) { audit(Stage.SESSION, Outcome.FAILED, Reason.MISSING_SESSION); return null; }
    synchronized (session) {
      Object value = session.getAttribute(KEY);
      if (!(value instanceof Bound bound)) { audit(Stage.SESSION, Outcome.FAILED, Reason.MISSING_BINDING); return null; }
      Instant now = clock.instant();
      Reason reason = !bound.fingerprint().equals(gate.approved(request)) ? Reason.CERTIFICATE_MISMATCH
          : !now.isBefore(bound.created().plus(ABSOLUTE)) ? Reason.ABSOLUTE_EXPIRED
          : !now.isBefore(bound.activity().plus(IDLE)) ? Reason.IDLE_EXPIRED : Reason.VALID;
      audit(Stage.SESSION, reason == Reason.VALID ? Outcome.PASSED : Outcome.FAILED, reason);
      if (reason == Reason.VALID) return bound;
      audit(Stage.SESSION, Outcome.COMPLETED, Reason.SESSION_INVALIDATED);
      session.invalidate();
      return null;
    }
  }
  void create(HttpServletRequest request, String subject) {
    String fp = gate.approved(request);
    if (fp == null) throw LoginPolicy.failure("certificate_required");
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval((int) ABSOLUTE.toSeconds());
    Instant now = clock.instant();
    session.setAttribute(KEY, new Bound(subject, fp, now, now));
    AuthAudit.newSession(request);
    audit(Stage.SESSION, Outcome.COMPLETED, Reason.SESSION_CREATED);
  }
  boolean activity(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) return false;
    synchronized (session) {
      Bound bound = current(request);
      if (bound == null) return false;
      session.setAttribute(KEY, new Bound(bound.subject(), bound.fingerprint(), bound.created(), clock.instant()));
      return true;
    }
  }
  static void clear(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) { session.invalidate(); audit(Stage.SESSION, Outcome.COMPLETED, Reason.SESSION_INVALIDATED); }
    response.addHeader("Set-Cookie", "__Host-irv=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
  }
}
