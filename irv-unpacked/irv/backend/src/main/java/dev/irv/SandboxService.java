package dev.irv;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;

@Service
public class SandboxService {
  final Db db;
  final Fly fly;
  final Hub hub;
  final Optimizer optimizer;
  final ExecutorService work = Executors.newFixedThreadPool(4);
  final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
  final Set<String> busy = ConcurrentHashMap.newKeySet();

  public SandboxService(Db d, Fly f, Hub h, Optimizer o) {
    db = d;
    fly = f;
    hub = h;
    optimizer = o;
  }

  public Map<String, Object> get(String id) {
    var s = db.one("SELECT * FROM sandboxes WHERE id=?", id);
    if (s == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Sandbox not found");
    return s;
  }

  public Map<String, Object> own(String id, Map<String, Object> session) {
    var s = get(id);
    if (!s.get("owner_id").equals(session.get("user_id")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Sandbox not found");
    return s;
  }

  public Object lock(String id) {
    return locks.computeIfAbsent(id, k -> new Object());
  }

  public Map<String, Object> publicBox(Map<String, Object> s) {
    var p = new LinkedHashMap<>(s);
    p.remove("agent_hash");
    return p;
  }

  public Object config(
      String id, long revision, String config, Map<String, String> files, boolean push) {
    var parsed = Config.parse(config);
    if (!files.containsKey(parsed.getString("entrypoint")))
      throw bad("Entrypoint must be present in startup files");
    synchronized (lock("config:" + id)) {
      var s = get(id);
      String oldFolder = Config.parse(str(s, "config")).getString("folder_name");
      if (!oldFolder.equals(parsed.getString("folder_name"))
          && (s.get("snapshot_id") != null || s.get("machine_id") != null))
        throw conflict("Folder name is fixed after first start");
      if (db.exec(
              "UPDATE sandboxes SET config=?,files=?,revision=revision+1,updated=? WHERE id=? AND"
                  + " revision=?",
              config,
              json(files),
              now(),
              id,
              revision)
          != 1) throw conflict("Configuration changed on the other side; refresh and merge");
      if (push && hub.connected(id))
        work.submit(
            () -> {
              try {
                hub.call(
                    id,
                    "config",
                    Map.of("config", config, "files", files, "revision", revision + 1),
                    20);
              } catch (Exception e) {
                db.log(id, "sync", e.getMessage());
              }
            });
      return Map.of("revision", revision + 1);
    }
  }

  public void open(String id) {
    db.exec("UPDATE sandboxes SET lease_until=? WHERE id=?", now() + 60000, id);
    synchronized (lock(id)) {
      var s = get(id);
      db.exec(
          "UPDATE sandboxes SET lease_until=?,idle_since=NULL,next_action_at=NULL WHERE id=?",
          now() + 60000,
          id);
      db.exec(
          "UPDATE returns SET returned_at=? WHERE sandbox_id=? AND returned_at IS NULL", now(), id);
      if (str(s, "state").equals("running") && hub.connected(id)) return;
      if (str(s, "state").equals("starting") || busy.contains(id)) return;
      enqueue(id, () -> start(id));
    }
  }

  void enqueue(String id, Runnable fn) {
    if (!busy.add(id)) return;
    work.submit(
        () -> {
          synchronized (lock(id)) {
            try {
              fn.run();
            } catch (Exception e) {
              db.exec(
                  "UPDATE sandboxes SET error=?,next_action_at=? WHERE id=?",
                  e.getMessage(),
                  now() + 60000,
                  id);
              db.log(id, "platform", e.getMessage());
            } finally {
              busy.remove(id);
            }
          }
        });
  }

  void start(String id) {
    var s = get(id);
    String from = str(s, "state");
    if (from.equals("running")) from = "stopped";
    String session = id();
    db.exec(
        "UPDATE sandboxes SET"
            + " state='starting',started_at=?,session_id=?,next_action=?,error=NULL,updated=? WHERE"
            + " id=?",
        now(),
        session,
        from,
        now(),
        id);
    db.log(id, "platform", "Starting from " + from + "; deadline 180 seconds");
    if (!str(s, "machine_id").isEmpty()) {
      fly.action(s, "start");
      if (hub.connected(id)) {
        hub.call(id, "resume", Map.of("session", session), 15);
      }
      return;
    }
    var catalog = db.one("SELECT value FROM settings WHERE name=?", "image:" + s.get("image_kind"));
    if (catalog != null) {
      db.exec("UPDATE sandboxes SET image_ref=? WHERE id=?", catalog.get("value"), id);
      s = get(id);
    }
    String token = token();
    db.exec("UPDATE sandboxes SET agent_hash=? WHERE id=?", hash(token), id);
    String volume = str(s, "volume_id");
    fly.ensureApp(str(s, "app_name"));
    if (volume.isEmpty() && env("IRV_WORKING_STORAGE", "ephemeral").equals("volume")) {
      volume = fly.volume(s);
      db.exec("UPDATE sandboxes SET volume_id=? WHERE id=?", volume, id);
    }
    var m = fly.create(get(id), token, volume);
    db.exec(
        "UPDATE sandboxes SET machine_id=?,updated=? WHERE id=?", m.path("id").asText(), now(), id);
    db.log(id, "provider", json(m.path("events")));
  }

  public void save(String id) {
    enqueue(
        id,
        () -> {
          if (!hub.connected(id)) throw conflict("Start the sandbox to checkpoint it");
          hub.call(id, "save", Map.of("final", false), 180);
          db.log(id, "platform", "Checkpoint saved");
        });
  }

  void transition(String id, String target) {
    var s = get(id);
    if (lng(s, "lease_until") > now()) return;
    String current = str(s, "state");
    if (target.equals(current)) return;
    if (target.equals("running")) {
      start(id);
      return;
    }
    db.log(id, "optimizer", "Transition " + current + " → " + target);
    if (current.equals("running")) {
      db.exec("UPDATE sandboxes SET state='saving' WHERE id=?", id);
      try {
        var saved =
            hub.call(
                id, "save", Map.of("final", true, "shutdown", !target.equals("suspended")), 180);
        if (!saved.path("verified").asBoolean()
            || get(id).get("snapshot_id") == null
            || !saved.path("snapshot").asText().equals(str(get(id), "snapshot_id")))
          throw conflict("Save not verified; retaining environment");
      } catch (Exception e) {
        db.exec("UPDATE sandboxes SET state='running' WHERE id=?", id);
        try {
          hub.call(id, "unfreeze", Map.of(), 10);
        } catch (Exception ignored) {
        }
        throw e;
      }
      s = get(id);
      if (lng(s, "lease_until") > now()) {
        hub.call(id, "unfreeze", Map.of(), 20);
        db.exec("UPDATE sandboxes SET state='running' WHERE id=?", id);
        return;
      }
    }
    if (s.get("snapshot_id") == null)
      throw conflict("No verified snapshot; cannot discard machine state");
    if (target.equals("suspended") || target.equals("stopped")) {
      fly.action(s, target.equals("suspended") ? "suspend" : "stop");
    } else if (target.equals("volume") || target.equals("cold")) {
      if (!str(s, "machine_id").isEmpty()) {
        fly.destroy(s);
        db.exec("UPDATE sandboxes SET machine_id=NULL,agent_hash=NULL WHERE id=?", id);
      }
      if (target.equals("cold") && !str(s, "volume_id").isEmpty()) {
        fly.deleteVolume(s);
        db.exec("UPDATE sandboxes SET volume_id=NULL WHERE id=?", id);
      }
    }
    db.exec(
        "UPDATE sandboxes SET state=?,next_action_at=NULL,updated=? WHERE id=?", target, now(), id);
  }

  public void stop(String id) {
    db.exec(
        "UPDATE sandboxes SET lease_until=?,idle_since=COALESCE(idle_since,?) WHERE id=?",
        now(),
        now(),
        id);
    enqueue(id, () -> transition(id, "stopped"));
  }

  public void archive(String id) {
    db.exec("UPDATE sandboxes SET lease_until=0 WHERE id=?", id);
    work.submit(
        () -> {
          synchronized (lock(id)) {
            try {
              var s = get(id);
              if (!str(s, "state").equals("cold")) transition(id, "cold");
              db.exec("UPDATE sandboxes SET state='archived' WHERE id=?", id);
            } catch (Exception e) {
              db.log(id, "platform", "Archive failed: " + e.getMessage());
            }
          }
        });
  }

  @Scheduled(fixedRate = 1000)
  public void deadlines() {
    for (var s :
        db.all("SELECT * FROM sandboxes WHERE state='starting' AND started_at<?", now() - 180000)) {
      String id = str(s, "id");
      if (db.exec(
              "UPDATE sandboxes SET state='failed',error='Startup exceeded the hard 180-second"
                  + " deadline' WHERE id=? AND state='starting'",
              id)
          == 1) {
        db.log(id, "platform", "Startup deadline exceeded; inspect startup logs");
        work.submit(
            () -> {
              synchronized (lock(id)) {
                try {
                  var b = get(id);
                  if (!str(b, "machine_id").isEmpty()) fly.action(b, "stop");
                } catch (Exception e) {
                  db.log(id, "provider", e.getMessage());
                }
              }
            });
      }
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void tick() {
    for (var s : db.all("SELECT * FROM sandboxes")) {
      String id = str(s, "id"), state = str(s, "state");
      if (busy.contains(id)) continue;
      if (state.equals("starting")) continue;
      if (!Set.of("running", "suspended", "stopped", "volume").contains(state)) continue;
      if (lng(s, "lease_until") > now()) continue;
      if (s.get("idle_since") == null) {
        db.exec("UPDATE sandboxes SET idle_since=? WHERE id=?", now(), id);
        db.exec("INSERT INTO returns VALUES(?,?,?,?,NULL)", id(), id, s.get("owner_id"), now());
        s = get(id);
      }
      if (s.get("next_action_at") != null && lng(s, "next_action_at") > now()) continue;
      try {
        var choice = optimizer.decide(s);
        db.exec("INSERT INTO decisions VALUES(?,?,?,?)", id(), id, json(choice), now());
        db.exec(
            "UPDATE sandboxes SET next_action=?,next_action_at=? WHERE id=?",
            choice.action(),
            now() + Math.max(10, (long) choice.delay()) * 1000,
            id);
        if (choice.delay() <= 10 && !choice.action().equals(state)) {
          String action = choice.action();
          enqueue(id, () -> transition(id, action));
        }
      } catch (Exception e) {
        db.log(id, "optimizer", e.getMessage());
      }
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void reconcile() {
    for (var s : db.all("SELECT * FROM sandboxes WHERE machine_id IS NOT NULL")) {
      String id = str(s, "id");
      if (busy.contains(id)) continue;
      try {
        var m = fly.machine(s);
        String state = m.path("state").asText();
        String event = json(m.path("events"));
        String key = id + ":" + hash(event);
        try {
          db.exec(
              "INSERT INTO logs(sandbox_id,session_id,source,event_key,message,created)"
                  + " VALUES(?,?,?,?,?,?)",
              id,
              s.get("session_id"),
              "provider",
              key,
              event,
              now());
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
        }
        if (Set.of("stopped", "failed", "destroyed").contains(state)
            && "running".equals(s.get("state"))) {
          db.exec(
              "UPDATE sandboxes SET state=?,error=? WHERE id=?",
              state.equals("destroyed") ? "failed" : state,
              "Machine exited; inspect provider logs",
              id);
        }
      } catch (Exception e) {
        db.log(id, "provider", e.getMessage());
      }
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void meter() {
    long now = now();
    for (var s : db.all("SELECT * FROM sandboxes")) {
      String id = str(s, "id"), key = "meter:" + id;
      var prev = db.one("SELECT value FROM settings WHERE name=?", key);
      if (prev == null) {
        db.exec("INSERT INTO settings VALUES(?,?)", key, "" + now);
        continue;
      }
      double secs = Math.min(3600, (now - Long.parseLong(str(prev, "value"))) / 1000.0);
      db.exec(
          "INSERT INTO usage VALUES(?,?,?,?,?,?)",
          id(),
          id,
          s.get("state"),
          secs,
          secs * optimizer.rate(s, str(s, "state")),
          now);
      db.exec("UPDATE settings SET value=? WHERE name=?", "" + now, key);
    }
  }
}
