package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Single-use login transaction bound to the pre-login session and exact certificate. */
final class BoundAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
  static final String KEY = BoundAuthorizationRequests.class.getName(), STARTED = KEY + ".started";
  record Pending(OAuth2AuthorizationRequest request, String fingerprint, Instant started) {}
  private final CertificateGate gate;
  private final Clock clock;
  BoundAuthorizationRequests(CertificateGate gate, Clock clock) { this.gate = gate; this.clock = clock; }

  @Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest auth, HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (auth == null) { if (session != null) session.removeAttribute(KEY); return; }
    String fp = gate.approved(request);
    if (fp == null) throw LoginPolicy.failure("certificate_required");
    // Starting another login cancels the old transaction/session; no silent account switch.
    if (session != null) { audit(Stage.SESSION, Outcome.COMPLETED, Reason.SESSION_REPLACED); session.invalidate(); }
    session = request.getSession(true);
    AuthAudit.newFlow(request);
    audit(Stage.LOGIN, Outcome.STARTED, Reason.NONE);
    session.setMaxInactiveInterval(300);
    session.setAttribute(KEY, new Pending(auth, fp, clock.instant()));
  }
  @Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) { audit(Stage.TRANSACTION, Outcome.FAILED, Reason.TRANSACTION_MISSING); return null; }
    synchronized (session) { return matching(request, session); }
  }
  private OAuth2AuthorizationRequest matching(HttpServletRequest request, HttpSession session) {
    Object value = session.getAttribute(KEY);
    if (!(value instanceof Pending p)) { audit(Stage.TRANSACTION, Outcome.FAILED, Reason.TRANSACTION_MISSING); return null; }
    String[] states = request.getParameterValues("state");
    Reason reason = states == null || states.length != 1 || states[0].length() > 1024
        || !MessageDigest.isEqual(p.request().getState().getBytes(StandardCharsets.UTF_8), states[0].getBytes(StandardCharsets.UTF_8))
        ? Reason.INVALID_STATE : !p.fingerprint().equals(gate.approved(request)) ? Reason.CERTIFICATE_MISMATCH
        : !clock.instant().isBefore(p.started().plusSeconds(300)) ? Reason.TRANSACTION_EXPIRED : Reason.VALID;
    audit(Stage.TRANSACTION, reason == Reason.VALID ? Outcome.PASSED : Outcome.FAILED, reason);
    if (reason != Reason.VALID) return null;
    request.setAttribute(STARTED, p.started());
    return p.request();
  }
  @Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session == null) { audit(Stage.TRANSACTION, Outcome.FAILED, Reason.TRANSACTION_MISSING); return null; }
    synchronized (session) {
      OAuth2AuthorizationRequest found = matching(request, session);
      session.removeAttribute(KEY); // Consume even invalid attempts; no callback replay.
      return found;
    }
  }
}
