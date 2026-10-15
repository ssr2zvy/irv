package dev.irv;

import static dev.irv.Util.*;

import jakarta.servlet.http.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;

@Component
@RestController
public class Maintenance {
  final Db db;
  final Blobs blobs;
  final Fly fly;

  public Maintenance(Db d, Blobs b, Fly f) {
    db = d;
    blobs = b;
    fly = f;
  }

  @PostMapping("/maintenance/promote")
  public Object promote(@RequestBody Map<String, String> b, HttpServletRequest r) {
    String expected = env("IRV_MAINTENANCE_TOKEN", "");
    String actual = Objects.toString(r.getHeader("Authorization"), "");
    if (expected.isEmpty()
        || !java.security.MessageDigest.isEqual(
            actual.getBytes(), ("Bearer " + expected).getBytes())) throw deny();
    for (String kind : List.of("blank", "java")) {
      String image = b.get(kind);
      if (image == null || !image.matches("[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}"))
        throw bad("Pinned image digests required");
      String key = "image:" + kind;
      db.transaction(
          () -> {
            if (db.exec("UPDATE settings SET value=? WHERE name=?", image, key) == 0)
              db.exec("INSERT INTO settings VALUES(?,?)", key, image);
            return null;
          });
    }
    return Map.of("ok", true, "applies", "next cold creation");
  }

  @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
  public void cleanup() {
    long cutoff = now() - num("IRV_BACKUP_DAYS", 7) * 86400000L;
    for (var s : db.all("SELECT id,snapshot_id,cleanup_snapshot_id FROM sandboxes")) {
      String id = str(s, "id");
      synchronized (blobs.lock(id)) {
        db.exec(
            "DELETE FROM snapshots WHERE sandbox_id=? AND created<? AND id<>? AND id<>?",
            id,
            cutoff,
            str(s, "snapshot_id"),
            str(s, "cleanup_snapshot_id"));
        Set<String> refs = new HashSet<>();
        for (var snap : db.all("SELECT manifest FROM snapshots WHERE sandbox_id=?", id))
          for (var e : node(str(snap, "manifest")).path("entries"))
            for (var c : e.path("chunks")) refs.add(c.asText());
        for (var chunk :
            db.all(
                "SELECT hash FROM chunks WHERE sandbox_id=? AND created<?", id, now() - 86400000)) {
          String hash = str(chunk, "hash");
          if (!refs.contains(hash)) {
            try {
              blobs.delete(id, hash);
              db.exec("DELETE FROM chunks WHERE sandbox_id=? AND hash=?", id, hash);
            } catch (Exception e) {
              db.log(id, "maintenance", "Garbage collection failed: " + e.getMessage());
            }
          }
        }
      }
    }
    db.exec("DELETE FROM logs WHERE created<?", now() - num("IRV_LOG_DAYS", 30) * 86400000L);
    db.exec("DELETE FROM sessions WHERE expires<?", now());
    db.exec("DELETE FROM preview_tokens WHERE expires<?", now());
    db.exec("DELETE FROM attempts WHERE started<?", now() - 86400000);
    db.exec("DELETE FROM metrics WHERE created<?", now() - 30L * 86400000);
  }

  @Scheduled(fixedDelay = 300000)
  public void metrics() {
    String orgSlug = env("FLY_ORG", ""), token = env("FLY_API_TOKEN", "");
    if (orgSlug.isBlank() || token.isBlank()) return;
    String query = "sum by (app,instance) (rate(fly_instance_cpu{mode!=\"idle\"}[5m]))";
    try {
      var req =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://api.fly.io/prometheus/"
                          + orgSlug
                          + "/api/v1/query?query="
                          + URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();
      var res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200)
        db.exec("INSERT INTO metrics VALUES(?,?,?)", id(), res.body(), now());
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(Maintenance.class)
          .warn("Fly metric import failed: {}", e.getMessage());
    }
  }

  @Scheduled(fixedDelay = 30000)
  public void containerLogs() {
    if (!env("IRV_FLY_LOGS", "false").equals("true")) return;
    for (var s :
        db.all("SELECT id,app_name,session_id FROM sandboxes WHERE machine_id IS NOT NULL")) {
      String text = fly.logs(str(s, "app_name"));
      for (String line : text.split("\\n")) {
        if (line.isBlank()) continue;
        try {
          var event = node(line);
          String message = event.path("message").asText(line);
          db.exec(
              "INSERT INTO logs(sandbox_id,session_id,source,event_key,message,created)"
                  + " VALUES(?,?,?,?,?,?)",
              s.get("id"),
              s.get("session_id"),
              "container",
              str(s, "id") + ":" + hash(line),
              message.substring(0, Math.min(16000, message.length())),
              now());
        } catch (Exception ignored) {
        }
      }
    }
  }
}
