package dev.irv;

import org.springframework.context.annotation.Profile;

import static dev.irv.Util.*;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.*;

@Profile("!auth-only")
@Configuration
@EnableWebSocket
public class Sockets implements WebSocketConfigurer {
  final Hub hub;
  final Auth auth;
  final Db db;
  final Preview preview;

  public Sockets(Hub h, Auth a, Db d, Preview p) {
    hub = h;
    auth = a;
    db = d;
    preview = p;
  }

  public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
    r.addHandler(hub, "/ws/agent", "/ws/terminal/*")
        .addInterceptors(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest req,
                  ServerHttpResponse res,
                  WebSocketHandler handler,
                  Map<String, Object> attrs) {
                try {
                  var request = ((ServletServerHttpRequest) req).getServletRequest();
                  String path = request.getRequestURI();
                  var grant = preview.grant(request.getServerName());
                  if (grant != null) {
                    attrs.put("box", grant.get("sandbox_id"));
                    attrs.put("kind", "preview");
                    attrs.put("port", grant.get("port"));
                    attrs.put(
                        "path",
                        path
                            + (request.getQueryString() == null
                                ? ""
                                : "?" + request.getQueryString()));
                    return true;
                  }
                  if (path.equals("/ws/agent")) {
                    String h = request.getHeader("Authorization");
                    if (h == null || !h.startsWith("Bearer ")) return false;
                    var s =
                        db.one("SELECT id FROM sandboxes WHERE agent_hash=?", hash(h.substring(7)));
                    if (s == null) return false;
                    attrs.put("box", s.get("id"));
                    attrs.put("kind", "agent");
                    return true;
                  }
                  if (!env("IRV_PUBLIC_ORIGIN", "http://localhost:8080")
                      .equals(request.getHeader("Origin"))) return false;
                  var session = auth.session(request, true);
                  String box = path.substring(path.lastIndexOf('/') + 1);
                  var s =
                      db.one(
                          "SELECT id FROM sandboxes WHERE id=? AND owner_id=? AND state='running'",
                          box,
                          session.get("user_id"));
                  if (s == null) return false;
                  attrs.put("box", box);
                  attrs.put("kind", "terminal");
                  attrs.put("session", session.get("token_hash"));
                  attrs.put("expires", session.get("expires"));
                  return true;
                } catch (Exception e) {
                  return false;
                }
              }

              public void afterHandshake(
                  ServerHttpRequest r, ServerHttpResponse s, WebSocketHandler h, Exception e) {}
            })
        .setAllowedOriginPatterns("*");
  }

  @Bean
  public org.springframework.web.servlet.HandlerMapping previewSockets() {
    var handler =
        new org.springframework.web.socket.server.support.WebSocketHttpRequestHandler(hub);
    handler.setHandshakeInterceptors(
        java.util.List.of(
            new HandshakeInterceptor() {
              public boolean beforeHandshake(
                  ServerHttpRequest q,
                  ServerHttpResponse r,
                  WebSocketHandler h,
                  Map<String, Object> a) {
                var req = ((ServletServerHttpRequest) q).getServletRequest();
                var g = preview.grant(req.getServerName());
                if (g == null) return false;
                a.put("box", g.get("sandbox_id"));
                a.put("kind", "preview");
                a.put("expires", g.get("expires"));
                a.put("port", g.get("port"));
                a.put(
                    "path",
                    req.getRequestURI()
                        + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
                a.put("protocols", Objects.toString(req.getHeader("Sec-WebSocket-Protocol"), ""));
                return true;
              }

              public void afterHandshake(
                  ServerHttpRequest q, ServerHttpResponse r, WebSocketHandler h, Exception e) {}
            }));
    var mapping =
        new org.springframework.web.servlet.handler.AbstractHandlerMapping() {
          protected Object getHandlerInternal(jakarta.servlet.http.HttpServletRequest r) {
            if (!"websocket".equalsIgnoreCase(r.getHeader("Upgrade"))) return null;
            try {
              return preview.grant(r.getServerName()) == null ? null : handler;
            } catch (Exception e) {
              return null;
            }
          }
        };
    mapping.setOrder(-1);
    return mapping;
  }
}
