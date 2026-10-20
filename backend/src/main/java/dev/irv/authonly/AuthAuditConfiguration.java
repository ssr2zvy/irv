package dev.irv.authonly;

import static dev.irv.authonly.AuthAudit.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

/** Outer request logger in every profile, including early boundary/security rejection. */
@Configuration(proxyBeanMethods = false)
public class AuthAuditConfiguration {
  @Bean FilterRegistrationBean<Filter> authAuditBoundary() {
    var registration = new FilterRegistrationBean<Filter>();
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*"); registration.setAsyncSupported(true);
    registration.setDispatcherTypes(java.util.EnumSet.allOf(DispatcherType.class));
    registration.setFilter(new AuditFilter());
    return registration;
  }
  static final class AuditFilter implements Filter {
    @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
      var request = (HttpServletRequest) req;
      var response = (HttpServletResponse) res;
      Context c = (Context) request.getAttribute(CONTEXT);
      boolean first = c == null;
      if (first) { c = new Context(request); request.setAttribute(CONTEXT, c); }
      Context previous = CURRENT.get(); CURRENT.set(c);
      Reason failure = null;
      try {
        if (first) {
          response.setHeader("X-Irv-Request-Id", c.requestId);
          audit(Stage.REQUEST, Outcome.STARTED, Reason.NONE);
          if ("/login/oauth2/code/zitadel".equals(request.getRequestURI()))
            audit(Stage.CALLBACK, Outcome.STARTED, Reason.NONE);
        }
        chain.doFilter(req, res);
      } catch (IOException | ServletException | RuntimeException ex) {
        failure = Reason.UNEXPECTED_ERROR;
        // No exception message, cause, stacktrace, URL or provider response in audit logs.
        throw ex;
      } finally {
        try {
          if (first && request.isAsyncStarted() && failure == null) {
            Context captured = c;
            AsyncListener listener = new AsyncListener() {
              public void onComplete(AsyncEvent e) { finish(captured, request, response, captured.asyncFailure); }
              public void onTimeout(AsyncEvent e) { captured.asyncFailure = Reason.ASYNC_TIMEOUT; }
              public void onError(AsyncEvent e) { captured.asyncFailure = Reason.UNEXPECTED_ERROR; }
              public void onStartAsync(AsyncEvent e) { e.getAsyncContext().addListener(this); }
            };
            try { request.getAsyncContext().addListener(listener); }
            catch (IllegalStateException completed) { finish(c, request, response, null); }
          } else if (first) finish(c, request, response, failure);
        } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
      }
    }
  }
}
