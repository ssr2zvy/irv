package dev.irv;

import static dev.irv.Util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:irvtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
      "spring.task.scheduling.enabled=false"
    })
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired Db db;
  String invite;

  String content(Object b) {
    return json(b);
  }

  Cookie cookie(MvcResult r) {
    String h = r.getResponse().getHeader("Set-Cookie");
    return new Cookie("irv_session", h.split(";", 2)[0].split("=", 2)[1]);
  }

  @BeforeEach
  void seed() {
    invite = token();
    db.exec("INSERT INTO invites VALUES(?,?,?,?,?)", hash(invite), now() + 60000, 1, "user", now());
  }

  @Test
  void registrationMfaInviteConsumptionAndLogout() throws Exception {
    String user = "u" + id().replace("-", "").substring(0, 15);
    var r =
        mvc.perform(
                post("/api/auth/register")
                    .header("X-Irv-Request", "1")
                    .contentType("application/json")
                    .content(
                        content(
                            Map.of(
                                "username",
                                user,
                                "password",
                                "long-test-password",
                                "invite",
                                invite))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie pending = cookie(r);
    mvc.perform(get("/api/sandboxes").cookie(pending)).andExpect(status().isUnauthorized());
    var setup =
        mvc.perform(
                post("/api/auth/enroll")
                    .cookie(pending)
                    .header("X-Irv-Request", "1")
                    .contentType("application/json")
                    .content("{\"factor\":\"totp\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String secret = node(setup.getResponse().getContentAsString()).path("secret").asText();
    var verified =
        mvc.perform(
                post("/api/auth/verify")
                    .cookie(pending)
                    .header("X-Irv-Request", "1")
                    .contentType("application/json")
                    .content(content(Map.of("code", Crypto.otp(secret, now() / 30000)))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie full = cookie(verified);
    assertEquals(
        10, node(verified.getResponse().getContentAsString()).path("recoveryCodes").size());
    mvc.perform(get("/api/sandboxes").cookie(full)).andExpect(status().isOk());
    mvc.perform(get("/api/sandboxes").cookie(pending)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/register")
                .header("X-Irv-Request", "1")
                .contentType("application/json")
                .content(
                    content(
                        Map.of(
                            "username",
                            user + "x",
                            "password",
                            "long-test-password",
                            "invite",
                            invite))))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/logout").cookie(full).header("X-Irv-Request", "1"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/sandboxes").cookie(full)).andExpect(status().isUnauthorized());
  }

  @Test
  void crossOriginMutationDenied() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .header("X-Irv-Request", "1")
                .header("Origin", "https://attacker.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void missingCsrfHeaderDenied() throws Exception {
    mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }
}
