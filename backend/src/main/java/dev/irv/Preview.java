package dev.irv;

import org.springframework.context.annotation.Profile;

import static dev.irv.Util.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Profile("!auth-only")
@Component
@Order(-100)
public class Preview extends OncePerRequestFilter {
  final Db db;
  final Hub hub;

  public Preview(Db d, Hub h) {
    db = d;
    hub = h;
  }

  public Map<String, Object> grant(String host) {
    String suffix = "." + URI.create(env("IRV_PREVIEW_ORIGIN", "http://localhost:8080")).getHost();
    if (!host.endsWith(suffix)) return null;
    String token = host.substring(0, host.length() - suffix.length());
    if (!token.matches("[a-f0-9]{48}")) throw deny();
    var g = db.one("SELECT * FROM preview_tokens WHERE hash=? AND expires>?", hash(token), now());
    if (g == null) throw deny();
    return g;
  }

  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws IOException, ServletException {
    Map<String, Object> g;
    try {
      g = grant(r.getServerName());
    } catch (Exception e) {
      s.sendError(401);
      return;
    }
    if (g == null) {
      chain.doFilter(r, s);
      return;
    }
    if ("websocket".equalsIgnoreCase(r.getHeader("Upgrade"))) {
      chain.doFilter(r, s);
      return;
    }
    try {
      String box = str(g, "sandbox_id");
      if (!hub.connected(box)) {
        s.sendError(503, "Open the sandbox terminal first");
        return;
      }
      hub.touch(box);
      Map<String, String> headers = new HashMap<>();
      var names = r.getHeaderNames();
      while (names.hasMoreElements()) {
        String k = names.nextElement();
        if (!Set.of("host", "connection", "content-length", "authorization", "proxy-authorization")
            .contains(k.toLowerCase())) headers.put(k, r.getHeader(k));
      }
      byte[] body = r.getInputStream().readNBytes(4194305);
      if (body.length > 4194304) {
        s.sendError(413);
        return;
      }
      String path =
          r.getRequestURI() + (r.getQueryString() == null ? "" : "?" + r.getQueryString());
      var result =
          hub.call(
              box,
              "http",
              Map.of(
                  "port",
                  g.get("port"),
                  "method",
                  r.getMethod(),
                  "path",
                  path,
                  "headers",
                  headers,
                  "body",
                  Base64.getEncoder().encodeToString(body)),
              40);
      s.setStatus(result.path("status").asInt(502));
      result
          .path("headers")
          .fields()
          .forEachRemaining(
              e -> {
                if (!Set.of(
                        "connection",
                        "content-length",
                        "transfer-encoding",
                        "content-encoding",
                        "fly-replay",
                        "clear-site-data")
                    .contains(e.getKey().toLowerCase()))
                  s.setHeader(e.getKey(), e.getValue().asText());
              });
      s.setHeader("Referrer-Policy", "no-referrer");
      s.setHeader("X-Content-Type-Options", "nosniff");
      s.getOutputStream().write(Base64.getDecoder().decode(result.path("body").asText()));
    } catch (Exception e) {
      s.sendError(502, "Preview unavailable");
    }
  }
}
