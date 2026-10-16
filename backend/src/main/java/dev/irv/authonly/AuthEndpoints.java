package dev.irv.authonly;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("auth-only")
public class AuthEndpoints {
  private final BoundSessions sessions;
  private final AuthSettings settings;
  AuthEndpoints(BoundSessions sessions, AuthSettings settings) { this.sessions = sessions; this.settings = settings; }

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
    response.setStatus(sessions.activity(request) ? 204 : 401);
  }
  @PostMapping("/auth/logout")
  void logout(HttpServletRequest request, HttpServletResponse response) {
    SecurityContextHolder.clearContext();
    BoundSessions.clear(request, response);
    response.setStatus(204);
  }
}
