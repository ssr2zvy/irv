package dev.irv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Legacy password/TOTP login is no longer an exposed fallback to the passkey runtime. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:irvtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
    "spring.task.scheduling.enabled=false"})
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;

  @Test void legacyMutationsDeniedEvenWithCsrf() throws Exception {
    for (String action : List.of("register", "login", "enroll", "verify", "logout"))
      mvc.perform(post("/api/auth/" + action).with(csrf()).with(r -> {r.setLocalPort(8081); return r;})
          .header("X-Irv-Request", "1").contentType("application/json").content("{}"))
          .andExpect(status().isNotFound());
  }

  @Test void legacyCookieAndBearerCannotExposeResources() throws Exception {
    for (String path : List.of("/api/sandboxes", "/api/health", "/future/operation"))
      mvc.perform(get(path).with(r -> {r.setLocalPort(8081); return r;}).cookie(new Cookie("irv_session", "old-session"))
          .header("Authorization", "Bearer old-token"))
          .andExpect(status().isNotFound());
  }

  @Test void missingCsrfDenied() throws Exception {
    mvc.perform(post("/api/auth/login").with(r -> {r.setLocalPort(8081); return r;}).contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }
}
