package dev.irv;


import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@DeferredFeature
@RestController
@RequestMapping("/api")
public class SandboxApi {
  final Auth auth;
  final Db db;
  final SandboxService svc;
  final Hub hub;

  public SandboxApi(Auth a, Db d, SandboxService s, Hub h) {
    auth = a;
    db = d;
    svc = s;
    hub = h;
  }

  Map<String, Object> own(String id, HttpServletRequest r) {
    return svc.own(id, auth.session(r, true));
  }

  @GetMapping("/sandboxes")
  public Object list(HttpServletRequest r) {
    return db
        .all(
            "SELECT * FROM sandboxes WHERE owner_id=? AND state<>'archived' ORDER BY created",
            auth.session(r, true).get("user_id"))
        .stream()
        .map(svc::publicBox)
        .toList();
  }

  @PostMapping("/sandboxes")
  public Object create(@RequestBody Map<String, String> b, HttpServletRequest r) {
    var session = auth.session(r, true);
    long count =
        db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sandboxes WHERE owner_id=? AND state<>'archived'",
            Long.class,
            session.get("user_id"));
    if (count >= num("IRV_MAX_SANDBOXES_PER_USER", 5)) throw bad("Sandbox limit reached");
    String name = b.getOrDefault("name", "Sandbox").trim(), kind = b.getOrDefault("image", "java");
    if (name.isEmpty() || name.length() > 80 || !Set.of("java", "blank").contains(kind))
      throw bad("Invalid sandbox name or image");
    String image = env(kind.equals("java") ? "IRV_JAVA_IMAGE" : "IRV_BLANK_IMAGE", "");
    if (image.isEmpty()) throw bad("Administrator must configure sandbox image references");
    String id = id(), app = "irv-" + id.substring(0, 18);
    String config = Config.DEFAULT;
    Map<String, String> files =
        Map.of(
            "scripts/environment.sh",
            "#!/usr/bin/env bash\nexport PATH=\"$WORKSPACE_DIR/bin:$PATH\"\n");
    db.exec(
        "INSERT INTO"
            + " sandboxes(id,owner_id,name,image_kind,image_ref,state,app_name,config,files,created,updated)"
            + " VALUES(?,?,?,?,?,'cold',?,?,?,?,?)",
        id,
        session.get("user_id"),
        name,
        kind,
        image,
        app,
        config,
        json(files),
        now(),
        now());
    return svc.publicBox(svc.get(id));
  }

  @GetMapping("/sandboxes/{id}")
  public Object get(@PathVariable String id, HttpServletRequest r) {
    return svc.publicBox(own(id, r));
  }

  @PostMapping("/sandboxes/{id}/open")
  public Object open(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.open(id);
    return Map.of("ok", true);
  }

  @PostMapping("/sandboxes/{id}/rebuild-agent")
  public Object rebuildAgent(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.rebuildAgent(id);
    return Map.of("ok", true);
  }

  @PostMapping("/sandboxes/{id}/apply")
  public Object apply(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.enqueue(
        id,
        () -> {
          if (!hub.connected(id)) throw conflict("Start the sandbox before applying setup");
          hub.call(id, "apply", Map.of(), 180);
          db.log(id, "platform", "Applied environment setup and restarted user processes");
        });
    return Map.of("ok", true);
  }

  @PostMapping("/sandboxes/{id}/stop")
  public Object stop(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.stop(id);
    return Map.of("ok", true);
  }

  @PostMapping("/sandboxes/{id}/save")
  public Object save(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.save(id);
    return Map.of("ok", true);
  }

  @PutMapping("/sandboxes/{id}/config")
  public Object config(@PathVariable String id, @RequestBody JsonNode b, HttpServletRequest r) {
    own(id, r);
    throw conflict("Use an exclusive editing session through /sync");
  }

  @GetMapping("/sandboxes/{id}/logs")
  public Object logs(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "") String session,
      HttpServletRequest r) {
    own(id, r);
    return logRows(id, after, session);
  }

  public Object logRows(String id, long after, String session) {
    return session.isEmpty()
        ? db.all("SELECT * FROM logs WHERE sandbox_id=? AND id>? ORDER BY id LIMIT 500", id, after)
        : db.all(
            "SELECT * FROM logs WHERE sandbox_id=? AND session_id=? AND id>? ORDER BY id LIMIT 500",
            id,
            session,
            after);
  }

  @GetMapping("/sandboxes/{id}/sessions")
  public Object sessions(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    return db.all(
        "SELECT session_id,MIN(started) AS started FROM (SELECT session_id,started FROM"
            + " sandbox_runs WHERE sandbox_id=? UNION ALL SELECT session_id,created AS started FROM"
            + " logs WHERE sandbox_id=?) x GROUP BY session_id ORDER BY started DESC",
        id,
        id);
  }

  @GetMapping("/sandboxes/{id}/decisions")
  public Object decisions(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    return db.all("SELECT * FROM decisions WHERE sandbox_id=? ORDER BY created DESC LIMIT 100", id);
  }

  @GetMapping("/sandboxes/{id}/usage")
  public Object usage(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    return db.all(
        "SELECT state,SUM(seconds) AS seconds,SUM(estimated_usd) AS estimated_usd FROM usage WHERE"
            + " sandbox_id=? GROUP BY state",
        id);
  }

  @GetMapping("/sandboxes/{id}/snapshots")
  public Object snapshots(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    return db.all(
        "SELECT id,bytes,created FROM snapshots WHERE sandbox_id=? ORDER BY created DESC", id);
  }

  @PostMapping("/sandboxes/{id}/restore/{snapshot}")
  public Object restore(
      @PathVariable String id, @PathVariable String snapshot, HttpServletRequest r) {
    own(id, r);
    synchronized (svc.lock(id)) {
      var s = svc.get(id);
      if (!str(s, "state").equals("cold")
          || s.get("machine_id") != null
          || s.get("volume_id") != null) throw conflict("Restore requires a fully cold sandbox");
      if (db.one("SELECT id FROM snapshots WHERE id=? AND sandbox_id=?", snapshot, id) == null)
        throw bad("Snapshot not found");
      db.exec("UPDATE sandboxes SET snapshot_id=? WHERE id=?", snapshot, id);
      db.log(
          id,
          "platform",
          "Selected backup " + snapshot + "; current config will overlay it on start");
    }
    return Map.of("ok", true);
  }

  @PostMapping("/sandboxes/{id}/preview")
  public Object preview(
      @PathVariable String id, @RequestBody Map<String, Integer> b, HttpServletRequest r) {
    var box = own(id, r);
    int port = b.getOrDefault("port", 0);
    if (!Config.port(str(box, "config"), port)) throw bad("Port is not declared in config.toml");
    String token = hash(token()).substring(0, 48);
    db.exec("INSERT INTO preview_tokens VALUES(?,?,?,?)", hash(token), id, port, now() + 3600000);
    String origin = env("IRV_PREVIEW_ORIGIN", "http://localhost:8080"),
        url = origin.replace("://", "://" + token + ".");
    return Map.of("url", url, "expires", now() + 3600000);
  }

  @PostMapping("/sandboxes/{id}/archive")
  public Object archive(@PathVariable String id, HttpServletRequest r) {
    own(id, r);
    svc.archive(id);
    return Map.of("ok", true);
  }

  @GetMapping("/admin/prices")
  public Object prices(HttpServletRequest r) {
    auth.admin(auth.session(r, true));
    return db.all("SELECT * FROM prices");
  }

  @PutMapping("/admin/prices")
  public Object prices(@RequestBody Map<String, Double> b, HttpServletRequest r) {
    auth.admin(auth.session(r, true));
    b.forEach(
        (k, v) -> {
          if (v == null || !Double.isFinite(v) || v < 0) throw bad("Invalid price");
          db.exec("UPDATE prices SET value=?,updated=? WHERE name=?", v, now(), k);
        });

    return Map.of("ok", true);
  }

  @GetMapping("/health")
  public Object health() {
    if ("true".equals(env("IRV_TUNNEL_ENABLED", "false"))) {
      java.net.HttpURLConnection connection = null;
      try {
        connection = (java.net.HttpURLConnection)
            new java.net.URL("http://127.0.0.1:2000/ready").openConnection(java.net.Proxy.NO_PROXY);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setInstanceFollowRedirects(false);
        if (connection.getResponseCode() != 200) throw new java.io.IOException();
      } catch (java.io.IOException unavailable) {
        return org.springframework.http.ResponseEntity.status(503)
            .body(Map.of("status", "unavailable", "name", "irv", "tunnel", "unavailable"));
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
    return Map.of("status", "ok", "name", "irv");
  }
}
