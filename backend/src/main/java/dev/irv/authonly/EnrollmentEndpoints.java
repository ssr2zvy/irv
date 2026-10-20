package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("auth-only")
class EnrollmentEndpoints {
  private final Enrollment enrollment;
  EnrollmentEndpoints(Enrollment enrollment) { this.enrollment = enrollment; }
  @PostMapping(value = "/auth/signup", consumes = "application/x-www-form-urlencoded")
  Map<String, String> signup(HttpServletRequest request, @RequestParam String invite, @RequestParam String username,
      @RequestParam String email, @RequestParam String givenName, @RequestParam String familyName) {
    return Map.of("enrollmentUrl", enrollment.begin(request, invite, username, email, givenName, familyName));
  }
  @PostMapping("/auth/signup/resume")
  Map<String, String> resume(HttpServletRequest request) { return Map.of("enrollmentUrl", enrollment.resume(request)); }
  @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
  ResponseEntity<Map<String, String>> invalidForm() {
    audit(Stage.INPUT, Outcome.FAILED, Reason.INVALID_INPUT);
    return ResponseEntity.badRequest().body(Map.of("error", "Required form fields are missing or invalid."));
  }
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, String>> rejected(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason()));
  }
}
