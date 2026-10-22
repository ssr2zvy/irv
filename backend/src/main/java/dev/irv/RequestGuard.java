package dev.irv;


import static dev.irv.Util.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.*;
import org.springframework.web.filter.OncePerRequestFilter;

@DeferredFeature
@Component
public class RequestGuard extends OncePerRequestFilter {
  final Auth auth;

  public RequestGuard(Auth a) {
    auth = a;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws IOException, ServletException {
    s.setHeader("X-Content-Type-Options", "nosniff");
    s.setHeader("Referrer-Policy", "no-referrer");
    String path = r.getRequestURI();
    if (path.startsWith("/api")) s.setHeader("Cache-Control", "no-store");
    if (path.startsWith("/api") && !Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod())) {
      String origin = r.getHeader("Origin");
      String expected = env("IRV_PUBLIC_ORIGIN", "http://localhost:8080");
      if (!"1".equals(r.getHeader("X-Irv-Request"))
          || (origin != null && !origin.equals(expected))) {
        s.sendError(403);
        return;
      }
    }
    if (r.getContentLengthLong() > 8 * 1024 * 1024) {
      s.sendError(413);
      return;
    }
    chain.doFilter(r, s);
  }
}
