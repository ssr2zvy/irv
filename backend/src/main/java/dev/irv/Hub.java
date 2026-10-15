package dev.irv;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class Hub extends org.springframework.web.socket.handler.AbstractWebSocketHandler
    implements org.springframework.web.socket.SubProtocolCapable {
  public java.util.List<String> getSubProtocols() {
    return java.util.List.of("vite-hmr");
  }

  final Db db;
  final ConcurrentMap<String, WebSocketSession> agents = new ConcurrentHashMap<>(),
      clients = new ConcurrentHashMap<>();
  final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  final ConcurrentMap<String, String> requestBox = new ConcurrentHashMap<>();

  public Hub(Db d) {
    db = d;
  }

  public boolean connected(String id) {
    return agents.containsKey(id);
  }

  public void send(WebSocketSession s, Object msg) throws Exception {
    s.sendMessage(new TextMessage(json(msg)));
  }

  public void agentSend(String id, Object msg) {
    try {
      var a = agents.get(id);
      if (a == null) throw conflict("Sandbox agent is offline");
      send(a, msg);
    } catch (Exception e) {
      throw new IllegalStateException("Agent connection unavailable", e);
    }
  }

  public JsonNode call(String box, String op, Object data, int timeout) {
    String req = id();
    CompletableFuture<JsonNode> f = new CompletableFuture<>();
    pending.put(req, f);
    requestBox.put(req, box);
    try {
      agentSend(box, Map.of("type", "rpc", "request", req, "op", op, "data", data));
      var n = f.get(timeout, TimeUnit.SECONDS);
      if (n.has("error")) throw conflict(n.path("error").asText());
      return n.path("data");
    } catch (TimeoutException e) {
      throw conflict("Agent operation timed out: " + op);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw conflict("Operation interrupted");
    } catch (ExecutionException e) {
      throw conflict("Agent disconnected");
    } finally {
      pending.remove(req);
      requestBox.remove(req);
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
    WebSocketSession s = new ConcurrentWebSocketSessionDecorator(raw, 10000, 8 * 1024 * 1024);
    String box = (String) s.getAttributes().get("box"),
        kind = (String) s.getAttributes().get("kind");
    s.setTextMessageSizeLimit(12 * 1024 * 1024);
    if (kind.equals("agent")) {
      var old = agents.put(box, s);
      if (old != null) old.close();
      db.log(box, "platform", "Agent connected");
      var b = db.one("SELECT state,next_action FROM sandboxes WHERE id=?", box);
      if (b != null
          && "starting".equals(b.get("state"))
          && "suspended".equals(b.get("next_action")))
        agentSend(box, Map.of("type", "rpc", "request", id(), "op", "resume", "data", Map.of()));
    } else {
      clients.put(s.getId(), s);
      touch(box);
      if (kind.equals("preview")) {
        Map<String, Object> packet = new HashMap<>();
        packet.put("type", "preview_open");
        packet.put("client", s.getId());
        packet.put("port", s.getAttributes().get("port"));
        packet.put("path", s.getAttributes().get("path"));
        packet.put(
            "protocols",
            Arrays.stream(Objects.toString(s.getAttributes().get("protocols"), "").split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .toList());
        agentSend(box, packet);
      } else agentSend(box, Map.of("type", "terminal_open", "client", s.getId()));
    }
  }

  void touch(String box) {
    db.exec(
        "UPDATE sandboxes SET"
            + " lease_until=?,idle_since=NULL,next_action_at=NULL,policy_retry_at=NULL WHERE id=?",
        now() + 45000,
        box);
    db.exec(
        "UPDATE returns SET returned_at=? WHERE sandbox_id=? AND returned_at IS NULL", now(), box);
  }

  @Override
  protected void handleTextMessage(WebSocketSession s, TextMessage msg) throws Exception {
    String mode = (String) s.getAttributes().get("kind");
    if (mode.equals("preview")) {
      touch((String) s.getAttributes().get("box"));
      agentSend(
          (String) s.getAttributes().get("box"),
          Map.of(
              "type",
              "preview_data",
              "client",
              s.getId(),
              "binary",
              false,
              "data",
              msg.getPayload()));
      return;
    }
    JsonNode n = node(msg.getPayload());
    String box = (String) s.getAttributes().get("box");
    if ("agent".equals(s.getAttributes().get("kind"))) {
      String type = n.path("type").asText();
      if (type.equals("rpc_result")) {
        String id = n.path("request").asText();
        var f = pending.get(id);
        if (f != null && box.equals(requestBox.get(id))) f.complete(n);
      } else if (type.equals("ready")) {
        var b = db.one("SELECT * FROM sandboxes WHERE id=?", box);
        if (b != null && "starting".equals(b.get("state"))) {
          long duration = now() - lng(b, "started_at");
          if (duration > 180000) {
            db.exec("UPDATE sandboxes SET error='Startup exceeded 180 seconds' WHERE id=?", box);
            return;
          }
          db.exec(
              "UPDATE sandboxes SET"
                  + " state='running',ready_at=?,error=NULL,lease_until=?,next_action=NULL,next_action_at=NULL,policy_retry_at=NULL"
                  + " WHERE id=?",
              now(),
              now() + 45000,
              box);
          db.exec(
              "INSERT INTO measurements VALUES(?,?,?,?,?,?)",
              id(),
              box,
              str(b, "next_action"),
              duration / 1000.0,
              lng(b, "bytes"),
              now());
          db.log(box, "platform", "Terminal ready after " + duration / 1000.0 + " seconds");
        }
      } else if (type.equals("terminal") || type.equals("terminal_exit")) {
        var c = clients.get(n.path("client").asText());
        if (c != null && box.equals(c.getAttributes().get("box"))) send(c, n);
      } else if (type.equals("preview_data") || type.equals("preview_close")) {
        var c = clients.get(n.path("client").asText());
        if (c != null && box.equals(c.getAttributes().get("box"))) {
          if (type.equals("preview_close")) c.close();
          else if (n.path("binary").asBoolean())
            c.sendMessage(new BinaryMessage(Base64.getDecoder().decode(n.path("data").asText())));
          else c.sendMessage(new TextMessage(n.path("data").asText()));
        }
      } else if (type.equals("error")) {
        db.log(box, "agent", n.path("message").asText());
        db.exec("UPDATE sandboxes SET error=? WHERE id=?", n.path("message").asText(), box);
      }
    } else {
      touch(box);
      String type = n.path("type").asText();
      if (Set.of("input", "resize").contains(type)) {
        var out = n.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) out).put("client", s.getId());
        agentSend(box, out);
      } else if (type.equals("heartbeat")) send(s, Map.of("type", "heartbeat"));
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession s, BinaryMessage msg) {
    if ("preview".equals(s.getAttributes().get("kind"))) {
      touch((String) s.getAttributes().get("box"));
      byte[] b = new byte[msg.getPayloadLength()];
      msg.getPayload().get(b);
      agentSend(
          (String) s.getAttributes().get("box"),
          Map.of(
              "type",
              "preview_data",
              "client",
              s.getId(),
              "binary",
              true,
              "data",
              Base64.getEncoder().encodeToString(b)));
    }
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
  public void expireClients() {
    for (var c : clients.values()) {
      Object exp = c.getAttributes().get("expires");
      boolean expired = exp instanceof Number n && n.longValue() < now();
      Object sess = c.getAttributes().get("session");
      if (sess != null
          && db.one("SELECT token_hash FROM sessions WHERE token_hash=? AND expires>?", sess, now())
              == null) expired = true;
      if (expired)
        try {
          c.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
        }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    String box = (String) s.getAttributes().get("box");
    if ("agent".equals(s.getAttributes().get("kind"))) {
      var a = agents.get(box);
      if (a != null && a.getId().equals(s.getId())) {
        agents.remove(box, a);
        pending.forEach(
            (k, f) -> {
              if (box.equals(requestBox.get(k)))
                f.completeExceptionally(new IllegalStateException("Disconnected"));
            });
      }
    } else {
      clients.remove(s.getId());
      try {
        agentSend(
            box,
            Map.of(
                "type",
                "preview".equals(s.getAttributes().get("kind"))
                    ? "preview_close"
                    : "terminal_close",
                "client",
                s.getId()));
      } catch (Exception ignored) {
      }
      if (clients.values().stream().noneMatch(c -> box.equals(c.getAttributes().get("box"))))
        db.log(
            box,
            "platform",
            "Last terminal/preview socket closed; idle starts when its usage lease expires");
    }
  }
}
