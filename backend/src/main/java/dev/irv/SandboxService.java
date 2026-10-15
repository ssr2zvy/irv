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
  final CostAccounting accounting;
  final FixedLifecyclePolicy policy;
  final ExecutorService work = Executors.newFixedThreadPool(4);
  final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
  final Set<String> busy = ConcurrentHashMap.newKeySet();

  public SandboxService(Db d, Fly f, Hub h, CostAccounting a, FixedLifecyclePolicy p) {
    db = d;
    fly = f;
    hub = h;
    accounting = a;
    policy = p;
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
    long requestedAt = now();
    // Publish the return before waiting for the lifecycle lock, so an in-flight save cancels.
    db.exec(
        "UPDATE sandboxes SET"
            + " lease_until=?,idle_since=NULL,next_action_at=NULL,policy_retry_at=NULL WHERE id=?",
        requestedAt + 60000,
        id);
    synchronized (lock(id)) {
      var s = get(id);
      // Once deletion is committed, finish that operation before recreating. It is no
      // longer safe to reuse a machine whose provider DELETE might already have succeeded.
      if (str(s, "state").equals("deleting")) {
        finishCleanup(id);
        s = get(id);
      }
      db.exec(
          "UPDATE sandboxes SET lease_until=?,idle_since=NULL,next_action_at=NULL WHERE id=?",
          now() + 60000,
          id);
      db.exec(
          "UPDATE returns SET returned_at=? WHERE sandbox_id=? AND returned_at IS NULL", now(), id);
      if (str(s, "state").equals("running") && hub.connected(id)) return;
      if (str(s, "state").equals("starting") || busy.contains(id)) return;
      String source = str(s, "machine_id").isEmpty() ? "cold" : str(s, "state");
      if (source.equals("running")) source = "stopped";
      meterOne(s, now());
      // Publish the deadline before queueing so the watchdog covers executor wait time.
      db.exec(
          "UPDATE sandboxes SET state='starting',started_at=?,next_action=?,error=NULL WHERE id=?",
          requestedAt,
          source,
          id);
      enqueue(id, () -> start(id, requestedAt));
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
                  "UPDATE sandboxes SET error=?,policy_retry_at=?,next_action_at=? WHERE id=?",
                  e.getMessage(),
                  now() + FixedLifecyclePolicy.RETRY_MILLIS,
                  now() + FixedLifecyclePolicy.RETRY_MILLIS,
                  id);
              db.log(id, "platform", e.getMessage());
            } finally {
              busy.remove(id);
            }
          }
        });
  }

  void start(String id) {
    start(id, now());
  }

  void start(String id, long requestedAt) {
    var s = get(id);
    if (now() - requestedAt >= FixedLifecyclePolicy.STARTUP_MILLIS)
      throw conflict("Startup deadline elapsed before resource creation; reopen to retry");
    String from =
        str(s, "state").equals("starting")
            ? str(s, "next_action")
            : (str(s, "machine_id").isEmpty() ? "cold" : str(s, "state"));
    if (from.equals("running")) from = "stopped";
    meterOne(s, now());
    String session = id();
    db.exec(
        "UPDATE sandboxes SET"
            + " state='starting',started_at=?,session_id=?,next_action=?,next_action_at=NULL,policy_retry_at=NULL,error=NULL,updated=?"
            + " WHERE id=?",
        requestedAt,
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
    if (volume.isEmpty()) {
      volume = fly.volume(s);
      if (volume.isBlank()) throw conflict("Fly did not return a volume ID");
      meterOne(get(id), now());
      db.exec(
          "UPDATE sandboxes SET volume_id=?,volume_gb=? WHERE id=?",
          volume,
          num("IRV_VOLUME_GB", 10),
          id);
    }
    var m = fly.create(get(id), token, volume);
    meterOne(get(id), now());
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
    if (!Set.of("cold", "stopped").contains(target)) throw bad("Unsupported fixed-policy action");
    var s = get(id);
    if (str(s, "state").equals("deleting")) {
      finishCleanup(id);
      return;
    }
    if (lng(s, "lease_until") > now()) return;
    if (target.equals(str(s, "state"))) return;
    // Old stopped/suspended records might contain changes newer than their last snapshot.
    // Never infer final-save completion merely from the existence of any snapshot.
    if (!str(s, "state").equals("running"))
      throw conflict("Reopen the retained environment before saving and releasing its resources");
    if (target.equals("cold")) policy.requireColdRecovery(s);
    db.log(id, "lifecycle", "Fixed policy: final save before " + target);
    db.exec("UPDATE sandboxes SET state='saving' WHERE id=?", id);
    try {
      // Freeze, do not kill. If storage fails or the user returns, work can continue.
      var saved = hub.call(id, "save", Map.of("final", true, "shutdown", false), 180);
      s = get(id);
      if (!saved.path("verified").asBoolean()
          || !saved.path("logs_flushed").asBoolean()
          || str(s, "snapshot_id").isEmpty()
          || !saved.path("snapshot").asText().equals(str(s, "snapshot_id")))
        throw conflict(
            "Final save/log flush not verified; retaining environment (rebuild sandbox images if"
                + " needed)");
      if (lng(s, "lease_until") > now()) {
        cancelSave(id);
        return;
      }
      if (target.equals("cold"))
        policy.requireColdRecovery(s); // size may have grown since last checkpoint
      if (target.equals("stopped")) {
        fly.action(s, "stop");
        meterOne(s, now());
        db.exec(
            "UPDATE sandboxes SET"
                + " state='stopped',next_action=NULL,next_action_at=NULL,policy_retry_at=NULL,updated=?"
                + " WHERE id=?",
            now(),
            id);
        return;
      }
      // Atomic commit point: earlier returns cancel, later returns finish cleanup then recreate.
      if (db.exec(
              "UPDATE sandboxes SET"
                  + " state='deleting',cleanup_snapshot_id=?,cleanup_target='cold',next_action='cold',next_action_at=NULL"
                  + " WHERE id=? AND state='saving' AND lease_until<=?",
              str(s, "snapshot_id"),
              id,
              now())
          != 1) {
        cancelSave(id);
        return;
      }
    } catch (Exception e) {
      if (str(get(id), "state").equals("saving")) cancelSave(id);
      throw e;
    }
    finishCleanup(id);
  }

  void cancelSave(String id) {
    try {
      hub.call(id, "unfreeze", Map.of(), 20);
    } catch (Exception e) {
      db.exec(
          "UPDATE sandboxes SET state='failed',error=? WHERE id=?",
          "Unfreeze failed; reopen environment: " + e.getMessage(),
          id);
      throw e;
    }
    db.exec(
        "UPDATE sandboxes SET state='running',next_action=NULL,next_action_at=NULL WHERE id=?", id);
  }

  void finishCleanup(String id) {
    var s = get(id);
    if (!str(s, "state").equals("deleting")) return;
    if (str(s, "cleanup_snapshot_id").isEmpty()
        || db.one(
                "SELECT id FROM snapshots WHERE id=? AND sandbox_id=?",
                s.get("cleanup_snapshot_id"),
                id)
            == null) throw conflict("Cleanup snapshot missing; retaining remaining resources");
    if (!str(s, "machine_id").isEmpty()) {
      fly.destroy(s);
      meterOne(s, now());
      db.exec("UPDATE sandboxes SET machine_id=NULL,agent_hash=NULL WHERE id=?", id);
    }
    s = get(id);
    if (!str(s, "volume_id").isEmpty()) {
      fly.deleteVolume(s);
      meterOne(s, now());
      db.exec("UPDATE sandboxes SET volume_id=NULL,volume_gb=NULL WHERE id=?", id);
    }
    db.exec(
        "UPDATE sandboxes SET"
            + " state='cold',cleanup_snapshot_id=NULL,cleanup_target=NULL,next_action=NULL,next_action_at=NULL,policy_retry_at=NULL,error=NULL,updated=?"
            + " WHERE id=?",
        now(),
        id);
    db.log(id, "lifecycle", "Final snapshot retained; machine and working volume released");
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
              if (str(get(id), "state").equals("cold"))
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
        db.exec(
            "INSERT INTO measurements VALUES(?,?,?,?,?,?)",
            id(),
            id,
            str(s, "next_action") + "_failed",
            (now() - lng(s, "started_at")) / 1000.0,
            lng(s, "bytes"),
            now());
        db.log(id, "platform", "Startup deadline exceeded; inspect startup logs");
        work.submit(
            () -> {
              synchronized (lock(id)) {
                try {
                  var b = get(id);
                  if (str(b, "state").equals("failed") && !str(b, "machine_id").isEmpty())
                    fly.action(b, "stop");
                } catch (Exception e) {
                  db.log(id, "provider", e.getMessage());
                }
              }
            });
      }
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void tick() {
    for (var original : db.all("SELECT * FROM sandboxes")) {
      String id = str(original, "id");
      if (busy.contains(id)) continue;
      synchronized (lock(id)) {
        var s = get(id);
        String state = str(s, "state");
        if (lng(s, "policy_retry_at") > now()) continue;
        if (state.equals("deleting")) {
          enqueue(id, () -> finishCleanup(id));
          continue;
        }
        // Recover an interrupted checkpoint after a control-plane restart. Without a
        // persisted cleanup commit it must be unfrozen and saved again, never deleted.
        if (state.equals("saving")) {
          enqueue(id, () -> cancelSave(id));
          continue;
        }
        if (!state.equals("running") || lng(s, "lease_until") > now()) continue;
        if (s.get("idle_since") == null) {
          long leftAt = lng(s, "lease_until") > 0 ? lng(s, "lease_until") : now();
          if (db.exec(
                  "UPDATE sandboxes SET idle_since=? WHERE id=? AND idle_since IS NULL AND"
                      + " lease_until<=?",
                  leftAt,
                  id,
                  now())
              != 1) continue;
          db.exec("INSERT INTO returns VALUES(?,?,?,?,NULL)", id(), id, s.get("owner_id"), leftAt);
          s = get(id);
        }
        long due = policy.saveAt(s);
        if (!"cold".equals(s.get("next_action")) || lng(s, "next_action_at") != due) {
          var detail =
              Map.of(
                  "policy",
                  "fixed-five-minute-v1",
                  "action",
                  "cold",
                  "delay",
                  Math.max(0, (due - now()) / 1000.0),
                  "at",
                  due,
                  "reason",
                  "Five minutes idle, then verified save and resource release; adaptive optimizer"
                      + " deferred");
          db.exec("INSERT INTO decisions VALUES(?,?,?,?)", id(), id, json(detail), now());
          db.exec(
              "UPDATE sandboxes SET next_action='cold',next_action_at=? WHERE id=? AND idle_since"
                  + " IS NOT NULL AND lease_until<=?",
              due,
              id,
              now());
        }
        if (now() >= due)
          enqueue(
              id,
              () -> {
                var current = get(id);
                if (current.get("idle_since") != null && now() >= policy.saveAt(current))
                  transition(id, "cold");
              });
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
              "UPDATE sandboxes SET state=?,error=? WHERE id=? AND state='running'",
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
    for (var s : db.all("SELECT id FROM sandboxes")) {
      String id = str(s, "id");
      synchronized (lock(id)) {
        meterOne(get(id), now());
      }
    }
  }

  // Flush the final interval before releasing resources, as well as on periodic ticks.
  // Configured-rate estimates still exclude object requests, transfer and invoice adjustments.
  void meterOne(Map<String, Object> s, long time) {
    String id = str(s, "id"), key = "meter:" + id;
    var prev = db.one("SELECT value FROM settings WHERE name=?", key);
    if (prev == null) {
      db.exec("INSERT INTO settings VALUES(?,?)", key, "" + time);
      return;
    }
    double seconds = Math.max(0, (time - Long.parseLong(str(prev, "value"))) / 1000.0);
    if (seconds > 0)
      db.exec(
          "INSERT INTO usage VALUES(?,?,?,?,?,?)",
          id(),
          id,
          s.get("state"),
          seconds,
          seconds * accounting.rate(s, str(s, "state")),
          time);
    db.exec("UPDATE settings SET value=? WHERE name=?", "" + time, key);
  }
}
