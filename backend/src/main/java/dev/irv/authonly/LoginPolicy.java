package dev.irv.authonly;

import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/** Additional checks AFTER Spring verifies signature, issuer/audience, expiry and OIDC nonce. */
final class LoginPolicy {
  static OAuth2AuthenticationException failure(String code) {
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
    Instant authTime = token.getAuthenticatedAt();
    if (authTime == null || authTime.isBefore(started.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        || authTime.isAfter(now.plusSeconds(30))) throw failure("fresh_authentication_required");
    Object raw = token.getClaims().get("urn:zitadel:iam:org:project:roles");
    if (!(raw instanceof Map<?, ?> roles) || !(roles.get(AuthSettings.ROLE) instanceof Map<?, ?> owners)
        || !owners.containsKey(AuthSettings.ORG)) throw failure("required_role_missing");
    if (token.getSubject() == null || !token.getSubject().matches("[0-9]{1,30}"))
      throw failure("invalid_subject");
  }
}
