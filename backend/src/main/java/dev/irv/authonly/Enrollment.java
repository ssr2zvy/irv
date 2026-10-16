package dev.irv.authonly;

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
    if (++attempts > 30) throw error(HttpStatus.TOO_MANY_REQUESTS, "Try again later.");
  }
  String begin(HttpServletRequest request, String invite, String username, String email, String given, String family) {
    if (!settings.enrollmentEnabled) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Account creation is not configured.");
    String fp = gate.approved(request);
    if (fp == null) throw error(HttpStatus.FORBIDDEN, "Admission required.");
    if (request.getSession(false) != null && request.getSession(false).getAttribute(BoundSessions.KEY) != null)
      throw error(HttpStatus.CONFLICT, "Sign out before creating another account.");
    rate();
    if (invite == null || !invite.matches("[A-Za-z0-9_-]{43,128}") || username == null
        || !username.matches("[a-z0-9][a-z0-9_.-]{2,31}") || email == null || email.length() > 200
        || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || !name(given) || !name(family))
      throw error(HttpStatus.BAD_REQUEST, "Check the invite, username, email and name fields.");
    String hash = hash(invite);
    if (!clock.instant().isBefore(settings.invites.getOrDefault(hash, Instant.EPOCH)))
      throw error(HttpStatus.FORBIDDEN, "Invite is invalid, expired or already used.");
    if (!ledger.reserve(hash, clock.instant()))
      throw error(HttpStatus.FORBIDDEN, "Invite is invalid, expired or already used.");
    // Persist reservation BEFORE any remote mutation. Never release on ambiguous timeout or restart.
    String subject = null;
    try {
      subject = provider.create(username, email, given, family);
      ledger.record(hash, "USER_CREATED", subject);
      String link = provider.registrationLink(subject);
      provider.grant(subject);
      ledger.record(hash, "ISSUED", subject);
      HttpSession session = request.getSession(true);
      request.changeSessionId();
      session.setAttribute(KEY, new Pending(fp, link, clock.instant().plusSeconds(900)));
      session.setMaxInactiveInterval(900);
      return link;
    } catch (Exception ex) {
      ledger.record(hash, "REVIEW_REQUIRED", subject);
      throw error(HttpStatus.BAD_GATEWAY, "Enrollment did not complete. The invite remains reserved; ask the operator to review it before using a new invite.");
    }
  }
  String resume(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(KEY) instanceof Pending pending
        && pending.fingerprint().equals(gate.approved(request)) && clock.instant().isBefore(pending.expires()))
      return pending.url();
    throw error(HttpStatus.NOT_FOUND, "No resumable enrollment in this browser session.");
  }
  private static boolean name(String value) { return value != null && !value.isBlank() && value.length() <= 100 && value.chars().noneMatch(Character::isISOControl); }
  static String hash(String code) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception ex) { throw new IllegalStateException(); }
  }
  private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
