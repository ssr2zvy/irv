package dev.irv.authonly;

import static org.junit.jupiter.api.Assertions.*;
import static dev.irv.authonly.AuthAudit.*;
import jakarta.servlet.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class AuthAuditTest {
  @Test void arbitraryFutureRequestsExceptionsAndAsyncAreLoggedAndContextCleared() throws Exception {
    var filter = new AuthAuditConfiguration.AuditFilter();
    try (AuditCapture logs = new AuditCapture()) {
      var req = new MockHttpServletRequest("GET", "/future-secret-path");
      req.setQueryString("token=secret-query");
      req.addHeader("Authorization", "Bearer credential-secret");
      var response = new MockHttpServletResponse();
      assertThrows(ServletException.class, () -> filter.doFilter(req, response, (r,s) -> {
        throw new ServletException("exception-secret");
      }));
      logs.has("request", "failed", "unexpected_error");
      assertNull(CURRENT.get());
      assertEquals(500, logs.rows().get(logs.rows().size()-1).path("status").asInt());
      var async = new MockHttpServletRequest("GET", "/future-async-secret");
      async.setAsyncSupported(true);
      var asyncResponse = new MockHttpServletResponse();
      filter.doFilter(async, asyncResponse, (r,s) -> r.startAsync());
      String id = asyncResponse.getHeader("X-Irv-Request-Id");
      assertFalse(logs.rows().stream().anyMatch(r -> r.path("request_id").asText().equals(id) && r.has("status")));
      ((MockAsyncContext)async.getAsyncContext()).complete();
      assertEquals(1, logs.rows().stream().filter(r -> r.path("request_id").asText().equals(id) && r.has("status")).count());
      assertNull(CURRENT.get());
      logs.excludes("secret-path", "secret-query", "credential-secret", "exception-secret", "future-async-secret");
    }
  }

  @Test void loggingSinkFailureCannotChangeRequestOutcome() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("irv.auth.audit");
    var broken = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
      protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) { throw new IllegalStateException("sink unavailable"); }
    };
    broken.start(); logger.addAppender(broken);
    try {
      var response = new MockHttpServletResponse();
      new AuthAuditConfiguration.AuditFilter().doFilter(new MockHttpServletRequest("GET", "/auth/protected"), response,
          (r,s) -> ((jakarta.servlet.http.HttpServletResponse)s).setStatus(401));
      assertEquals(401, response.getStatus()); assertNull(CURRENT.get());
    } finally { logger.detachAppender(broken); broken.stop(); }
  }
}
