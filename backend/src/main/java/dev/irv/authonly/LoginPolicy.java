package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/** Additional checks AFTER Spring verifies signature, issuer/audience, expiry and OIDC nonce. */
final class LoginPolicy {
  static OAuth2AuthenticationException failure(String code) {
    switch (code) {
      case "passkey_evidence_missing", "passkey_evidence_rejected" -> audit(Stage.PASSKEY, Outcome.FAILED, Reason.PASSKEY_EVIDENCE_REJECTED);
      case "fresh_authentication_required" -> audit(Stage.FRESHNESS, Outcome.FAILED, Reason.FRESH_AUTHENTICATION_REQUIRED);
      case "required_role_missing" -> audit(Stage.ROLE, Outcome.FAILED, Reason.REQUIRED_ROLE_MISSING);
      case "invalid_subject" -> audit(Stage.SUBJECT, Outcome.FAILED, Reason.INVALID_SUBJECT);
      case "login_transaction_missing" -> audit(Stage.TRANSACTION, Outcome.FAILED, Reason.TRANSACTION_MISSING);
      default -> { } // Never emit a provider-supplied error string.
    }
    return new OAuth2AuthenticationException(new OAuth2Error(code));
  }
  static void validate(OidcIdToken token, Instant started, Instant now) {
    List<String> amr;
    try { amr = token.getAuthenticationMethods(); }
    catch (Exception ex) { throw failure("passkey_evidence_missing"); }
    // ZITADEL Login V2: verified WebAuthn => user + mfa. Plain U2F => user only.
    // Password/OTP/unknown extras are rejected; external IdPs must remain disabled in org policy.
    if (amr == null || amr.size() != 2 || !new HashSet<>(amr).equals(Set.of("user", "mfa")))
      throw failure("passkey_evidence_rejected");
    audit(Stage.PASSKEY, Outcome.PASSED, Reason.VALID);
    Instant authTime = token.getAuthenticatedAt();
    if (authTime == null || authTime.isBefore(started.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        || authTime.isAfter(now.plusSeconds(30))) throw failure("fresh_authentication_required");
    audit(Stage.FRESHNESS, Outcome.PASSED, Reason.VALID);
    Object raw = token.getClaims().get("urn:zitadel:iam:org:project:roles");
    if (!(raw instanceof Map<?, ?> roles) || !(roles.get(AuthSettings.ROLE) instanceof Map<?, ?> owners)
        || !owners.containsKey(AuthSettings.ORG)) throw failure("required_role_missing");
    audit(Stage.ROLE, Outcome.PASSED, Reason.VALID);
    if (token.getSubject() == null || !token.getSubject().matches("[0-9]{1,30}"))
      throw failure("invalid_subject");
    audit(Stage.SUBJECT, Outcome.PASSED, Reason.VALID);
  }
}
