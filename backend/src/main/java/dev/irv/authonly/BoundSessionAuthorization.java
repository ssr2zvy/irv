package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Default for every application route: Spring authentication alone is insufficient. */
final class BoundSessionAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
  private final BoundSessions sessions;

  BoundSessionAuthorization(BoundSessions sessions) { this.sessions = sessions; }

  @Override
  public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
    var request = context.getRequest();
    Authentication auth = authentication.get();
    var bound = sessions.current(request); // Rechecks trusted ingress, exact certificate and both expiry limits.
    boolean allowed = bound != null && auth != null && auth.isAuthenticated()
        && auth.getPrincipal() instanceof OidcUser user
        && bound.subject().equals(user.getSubject())
        && AuthSettings.ISSUER.equals(user.getIssuer().toString());
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
      boolean origin = AuthSettings.ORIGIN.equals(CertificateGate.header(request, "Origin"));
      audit(Stage.ORIGIN, origin ? Outcome.PASSED : Outcome.FAILED, origin ? Reason.VALID : Reason.INVALID_ORIGIN);
      allowed &= origin;
    }
    audit(Stage.AUTHORIZATION, allowed ? Outcome.PASSED : Outcome.FAILED, allowed ? Reason.VALID : Reason.ACCESS_DENIED);
    return new AuthorizationDecision(allowed);
  }
}
