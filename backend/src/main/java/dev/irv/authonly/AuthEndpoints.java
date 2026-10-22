package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthEndpoints {
  private final BoundSessions sessions;
  private final AuthSettings settings;
  private final org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver resolver;
  private final BoundAuthorizationRequests transactions;
  private final java.net.URI loginDestination;
  AuthEndpoints(BoundSessions sessions, AuthSettings settings,
      org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver resolver,
      BoundAuthorizationRequests transactions,
      org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients) {
    this.sessions = sessions; this.settings = settings; this.resolver = resolver; this.transactions = transactions;
    this.loginDestination = java.net.URI.create(clients.findByRegistrationId("zitadel").getProviderDetails().getAuthorizationUri());
  }

  @PostMapping(value = "/auth/login", produces = "application/json")
  Map<String, String> login(HttpServletRequest request, HttpServletResponse response) {
    var authorization = resolver.resolve(request, "zitadel");
    if (authorization == null) throw new IllegalStateException("Login request unavailable");
    var destination = java.net.URI.create(authorization.getAuthorizationRequestUri());
    if (!loginDestination.getScheme().equals(destination.getScheme()) || !loginDestination.getHost().equals(destination.getHost())
        || destination.getPort() != loginDestination.getPort() || destination.getUserInfo() != null
        || !loginDestination.getPath().equals(destination.getPath()))
      throw new IllegalStateException("Unexpected login destination");
    transactions.saveAuthorizationRequest(authorization, request, response);
    return Map.of("authorizationUrl", destination.toString());
  }

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "parameterName", token.getParameterName(), "headerName", token.getHeaderName());
  }
  @GetMapping("/auth/protected")
  Map<String, Object> protectedProbe() {
    return Map.of("authenticated", true, "certificateBound", settings.certificateRequired, "sandboxAccessEnabled", false);
  }
  @PostMapping("/auth/activity")
  void activity(HttpServletRequest request, HttpServletResponse response) {
    boolean accepted = sessions.activity(request);
    audit(Stage.ACTIVITY, accepted ? Outcome.PASSED : Outcome.FAILED, accepted ? Reason.VALID : Reason.MISSING_BINDING);
    response.setStatus(accepted ? 204 : 401);
  }
  @PostMapping("/auth/logout")
  void logout(HttpServletRequest request, HttpServletResponse response) {
    audit(Stage.LOGOUT, Outcome.COMPLETED, Reason.USER_LOGOUT);
    SecurityContextHolder.clearContext();
    BoundSessions.clear(request, response);
    response.setStatus(204);
  }
}
