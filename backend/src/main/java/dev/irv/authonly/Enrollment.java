package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Signup only. Never adds passkeys to an existing user or provides a recovery route. */
final class Enrollment {
  static final String KEY = Enrollment.class.getName();
  record Pending(String fingerprint, String url, Instant expires) {}
  private final AuthSettings settings;
  private final CertificateGate gate;
  private final InviteLedger ledger;
  private final EnrollmentProvider provider;
  private final Clock clock;
  private Instant window = Instant.EPOCH;
  private int attempts;
  Enrollment(AuthSettings settings, CertificateGate gate, InviteLedger ledger, EnrollmentProvider provider, Clock clock) {
    this.settings = settings; this.gate = gate; this.ledger = ledger; this.provider = provider; this.clock = clock;
  }
  // A global cap deliberately bounds provider traffic even if clients change certificates/cookies.
  private synchronized void rate() {
    Instant now = clock.instant();
    if (!now.isBefore(window.plusSeconds(900))) { window = now; attempts = 0; }
    if (++attempts > 30) { audit(Stage.SIGNUP, Outcome.FAILED, Reason.RATE_LIMITED); throw error(HttpStatus.TOO_MANY_REQUESTS, "Try again later."); }
  }
  String begin(HttpServletRequest request, String invite, String username, String email, String given, String family) {
    AuthAudit.newFlow(request);
    audit(Stage.SIGNUP, Outcome.STARTED, Reason.NONE);
    if (!settings.enrollmentEnabled) { audit(Stage.SIGNUP, Outcome.FAILED, Reason.ENROLLMENT_DISABLED); throw error(HttpStatus.SERVICE_UNAVAILABLE, "Account creation is not configured."); }
    String fp = gate.approved(request);
    if (fp == null) { audit(Stage.SIGNUP, Outcome.FAILED, Reason.UNTRUSTED_CONNECTION); throw error(HttpStatus.FORBIDDEN, "Admission required."); }
    if (request.getSession(false) != null && request.getSession(false).getAttribute(BoundSessions.KEY) != null)
      { audit(Stage.SIGNUP, Outcome.FAILED, Reason.ALREADY_SIGNED_IN); throw error(HttpStatus.CONFLICT, "Sign out before creating another account."); }
    rate();
    if (invite == null || !invite.matches("[A-Za-z0-9_-]{43,128}") || username == null
        || !username.matches("[a-z0-9][a-z0-9_.-]{2,31}") || !email(email)
        || !name(given) || !name(family))
      { audit(Stage.INPUT, Outcome.FAILED, Reason.INVALID_INPUT); throw error(HttpStatus.BAD_REQUEST, "Check the invite, username, email and name fields."); }
    audit(Stage.INPUT, Outcome.PASSED, Reason.VALID);
    String hash = hash(invite);
    if (!clock.instant().isBefore(settings.inviteExpiry(hash))) {
      audit(Stage.INVITE, Outcome.FAILED, Reason.INVITE_INVALID_OR_EXPIRED);
      throw error(HttpStatus.FORBIDDEN, "Invite is invalid, expired or already used.");
    }
    boolean reserved;
    try { reserved = ledger.reserve(hash, clock.instant()); }
    catch (RuntimeException ex) { audit(Stage.INVITE, Outcome.FAILED, Reason.LEDGER_FAILURE); throw ex; }
    if (!reserved) {
      audit(Stage.INVITE, Outcome.FAILED, Reason.INVITE_ALREADY_RESERVED);
      throw error(HttpStatus.FORBIDDEN, "Invite is invalid, expired or already used.");
    }
    audit(Stage.INVITE, Outcome.PASSED, Reason.INVITE_RESERVED);
    // Persist reservation BEFORE any remote mutation. Never release on ambiguous timeout or restart.
    String subject = null;
    Stage stage = Stage.USER_CREATE;
    try {
      audit(stage, Outcome.STARTED, Reason.NONE);
      subject = provider.create(username, email, given, family);
      audit(stage, Outcome.PASSED, Reason.VALID);
      stage = Stage.INVITE;
      ledger.record(hash, "USER_CREATED", subject);
      stage = Stage.ENROLLMENT_LINK; audit(stage, Outcome.STARTED, Reason.NONE);
      String link = provider.registrationLink(subject);
      audit(stage, Outcome.PASSED, Reason.VALID);
      stage = Stage.ROLE_GRANT; audit(stage, Outcome.STARTED, Reason.NONE);
      provider.grant(subject);
      audit(stage, Outcome.PASSED, Reason.VALID);
      stage = Stage.INVITE;
      ledger.record(hash, "ISSUED", subject);
      stage = Stage.SESSION;
      HttpSession session = request.getSession(true);
      request.changeSessionId();
      session.setAttribute(KEY, new Pending(fp, link, clock.instant().plusSeconds(900)));
      session.setMaxInactiveInterval(900);
      audit(Stage.SIGNUP, Outcome.COMPLETED, Reason.VALID);
      return link;
    } catch (Exception ex) {
      audit(stage, Outcome.FAILED, stage == Stage.INVITE ? Reason.LEDGER_FAILURE : stage == Stage.SESSION ? Reason.UNEXPECTED_ERROR : Reason.PROVIDER_OR_RESPONSE_FAILURE);
      audit(Stage.SIGNUP, Outcome.FAILED, Reason.REVIEW_REQUIRED);
      ledger.record(hash, "REVIEW_REQUIRED", subject);
      throw error(HttpStatus.BAD_GATEWAY, "Enrollment did not complete. The invite remains reserved; ask the operator to review it before using a new invite.");
    }
  }
  String resume(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(KEY) instanceof Pending pending
        && pending.fingerprint().equals(gate.approved(request)) && clock.instant().isBefore(pending.expires()))
      { audit(Stage.ENROLLMENT_RESUME, Outcome.PASSED, Reason.VALID); return pending.url(); }
    audit(Stage.ENROLLMENT_RESUME, Outcome.FAILED, Reason.RESUME_UNAVAILABLE);
    throw error(HttpStatus.NOT_FOUND, "No resumable enrollment in this browser session.");
  }
  private static boolean email(String value) {
    if (value == null || value.length() > 200 || value.chars().anyMatch(Character::isWhitespace)) return false;
    int at = value.indexOf('@');
    return at > 0 && at == value.lastIndexOf('@') && value.indexOf('.', at + 2) > at + 1 && value.charAt(value.length() - 1) != '.';
  }
  private static boolean name(String value) { return value != null && !value.isBlank() && value.length() <= 100 && value.chars().noneMatch(Character::isISOControl); }
  static String hash(String code) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception ex) { throw new IllegalStateException(); }
  }
  private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
