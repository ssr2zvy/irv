package dev.irv;

import org.springframework.context.annotation.Profile;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@Profile("!auth-only")
@RestController
@RequestMapping("/agent")
public class AgentApi {
  final Db db;
  final Blobs blobs;
  final SandboxService svc;

  public AgentApi(Db d, Blobs b, SandboxService s) {
    db = d;
    blobs = b;
    svc = s;
  }

  public String agent(HttpServletRequest r) {
    String h = r.getHeader("Authorization");
    if (h == null || !h.startsWith("Bearer ")) throw deny();
    var s = db.one("SELECT id FROM sandboxes WHERE agent_hash=?", hash(h.substring(7)));
    if (s == null) throw deny();
    return str(s, "id");
  }

  @GetMapping("/bootstrap")
  public Object bootstrap(HttpServletRequest r) {
    String id = agent(r);
    var s = svc.get(id);
    return Map.of(
        "id",
        id,
        "config",
        s.get("config"),
        "files",
        node(str(s, "files")),
        "revision",
        s.get("revision"),
        "session",
        str(s, "session_id"),
        "snapshot",
        snapshotNode(s));
  }

  Object snapshotNode(Map<String, Object> s) {
    if (s.get("snapshot_id") == null) return Map.of();
    var snap = db.one("SELECT manifest FROM snapshots WHERE id=?", s.get("snapshot_id"));
    return snap == null ? Map.of() : node(str(snap, "manifest"));
  }

  @GetMapping("/chunks/{hash}")
  public byte[] chunk(@PathVariable String hash, HttpServletRequest r) {
    return blobs.get(agent(r), hash);
  }

  @PostMapping("/chunks/missing")
  public Object missing(@RequestBody List<String> hashes, HttpServletRequest r) {
    String id = agent(r);
    synchronized (blobs.lock(id)) {
      if (hashes.size() > 1000) throw bad("Batch too large");
      hashes.forEach(
          h -> db.exec("UPDATE chunks SET created=? WHERE sandbox_id=? AND hash=?", now(), id, h));
      return hashes.stream()
          .filter(
              h -> db.one("SELECT hash FROM chunks WHERE sandbox_id=? AND hash=?", id, h) == null)
          .toList();
    }
  }

  @PutMapping("/chunks/{hash}")
  public Object put(@PathVariable String hash, HttpServletRequest r) throws IOException {
    String id = agent(r);
    synchronized (blobs.lock(id)) {
      byte[] b = r.getInputStream().readNBytes(4194305);
      if (b.length > 4194304) throw bad("Chunk too large");
      if (db.one("SELECT hash FROM chunks WHERE sandbox_id=? AND hash=?", id, hash) == null) {
        long used =
            db.jdbc.queryForObject(
                "SELECT COALESCE(SUM(bytes),0) FROM chunks WHERE sandbox_id=?", Long.class, id);
        if (used + b.length > num("IRV_STORAGE_LIMIT_GB", 20) * 1073741824L)
          throw bad("Storage quota exceeded");
        blobs.put(id, hash, b);
        try {
          db.exec("INSERT INTO chunks VALUES(?,?,?,?)", id, hash, b.length, now());
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
        }
      }
      return Map.of("ok", true);
    }
  }

  @PostMapping("/snapshots")
  public Object commit(@RequestBody JsonNode body, HttpServletRequest r) {
    String id = agent(r);
    synchronized (blobs.lock(id)) {
      if (!body.path("entries").isArray() || body.path("entries").size() > 100000)
        throw bad("Invalid manifest");
      long size = 0;
      Set<String> names = new HashSet<>();
      for (var entry : body.path("entries")) {
        String p = entry.path("path").asText();
        Config.relative(p);
        if (p.equals(".") || !names.add(p)) throw bad("Duplicate or invalid snapshot path");
        String type = entry.path("type").asText();
        if (!Set.of("file", "dir", "link").contains(type)) throw bad("Unknown entry type");
        if (type.equals("file")) {
          long bytes = 0;
          for (var chunk : entry.path("chunks")) {
            var found =
                db.one(
                    "SELECT bytes FROM chunks WHERE sandbox_id=? AND hash=?", id, chunk.asText());
            if (found == null) throw bad("Missing snapshot chunk");
            bytes += lng(found, "bytes");
          }
          if (bytes != entry.path("size").asLong(-1)) throw bad("Invalid file size");
          size += bytes;
        }
      }
      String sid = id();
      long total = size;
      db.transaction(
          () -> {
            db.exec("INSERT INTO snapshots VALUES(?,?,?,?,?)", sid, id, json(body), total, now());
            db.exec(
                "UPDATE sandboxes SET snapshot_id=?,bytes=?,updated=? WHERE id=?",
                sid,
                total,
                now(),
                id);
            return null;
          });
      return Map.of("snapshot", sid);
    }
  }

  @PostMapping("/config")
  public Object config(@RequestBody JsonNode body, HttpServletRequest r) {
    String id = agent(r);
    throw conflict("Agent must use guarded edit sessions");
  }

  @PostMapping("/events")
  public Object events(@RequestBody JsonNode body, HttpServletRequest r) {
    String id = agent(r);
    if (!body.isArray() || body.size() > 200) throw bad("Expected at most 200 log events");
    return db.transaction(
        () -> {
          var box = db.one("SELECT session_id FROM sandboxes WHERE id=? FOR UPDATE", id);
          var ack = new ArrayList<String>();
          for (var e : body) {
            String eid = e.path("id").asText(),
                source = e.path("source").asText(),
                message = e.path("message").asText();
            String stream = e.path("stream").asText("system"),
                producer = e.path("producer").asText("");
            if (!eid.matches("[a-zA-Z0-9_-]{1,60}")
                || source.isBlank()
                || source.length() > 100
                || message.length() > 16384
                || producer.length() > 80
                || !Set.of("stdout", "stderr", "system", "file").contains(stream))
              throw bad("Invalid log event");
            String key = id + ":" + eid;
            var prior = db.one("SELECT message,source,stream FROM logs WHERE event_key=?", key);
            if (prior != null) {
              if (!message.equals(str(prior, "message"))
                  || !source.equals(str(prior, "source"))
                  || !stream.equals(str(prior, "stream")))
                throw bad("Log ID reused with different content");
            } else {
              long timestamp = e.path("timestamp").asLong(now());
              if (timestamp < 0 || timestamp > now() + 86400000L)
                throw bad("Invalid log timestamp");
              db.exec(
                  "INSERT INTO"
                      + " logs(sandbox_id,session_id,source,event_key,message,created,stream,producer,sequence_no,received_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                  id,
                  e.path("session").asText(str(box, "session_id")),
                  source,
                  key,
                  message,
                  timestamp,
                  stream,
                  producer,
                  e.path("sequence").asLong(),
                  now());
            }
            ack.add(eid);
          }
          db.exec("UPDATE sandboxes SET logs_ack_at=? WHERE id=?", now(), id);
          return Map.of("ack", ack);
        });
  }

  @PostMapping("/telemetry")
  public Object telemetry(@RequestBody JsonNode body, HttpServletRequest r) {
    String id = agent(r);
    if (body.has("logs_pending"))
      db.exec(
          "UPDATE sandboxes SET logs_pending=? WHERE id=?",
          Math.max(0, body.path("logs_pending").asLong()),
          id);
    if (body.has("tree"))
      db.exec(
          "UPDATE sandboxes SET tree=?,updated=? WHERE id=?", json(body.path("tree")), now(), id);
    String kind = body.path("kind").asText();
    if (Set.of("save", "restore", "setup").contains(kind))
      db.exec(
          "INSERT INTO measurements VALUES(?,?,?,?,?,?)",
          id(),
          id,
          kind,
          Math.max(0, body.path("seconds").asDouble()),
          Math.max(0, body.path("bytes").asLong()),
          now());
    return Map.of("ok", true);
  }
}
