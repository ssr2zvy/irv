package dev.irv;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Runs with either JUnit or main(); no live provider calls and no clock sleeps of minutes. */
public final class FixedPolicyChecks {
  static int passed;

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static void fails(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      return;
    }
    throw new AssertionError("Expected operation to fail");
  }

  interface Scenario {
    void run(Fixture f) throws Exception;
  }

  static void scenario(String name, Scenario action) throws Exception {
    try (var f = new Fixture()) {
      action.run(f);
    }
    passed++;
    System.out.println("PASS " + name);
  }

  static class FakeFly extends Fly {
    final List<String> events;
    int creates, volumes, deletes, volumeDeletes;
    boolean failVolume;

    FakeFly(List<String> events) {
      this.events = events;
    }

    @Override
    public void ensureApp(String app) {}

    @Override
    public String volume(Map<String, Object> box) {
      volumes++;
      return "new-volume";
    }

    @Override
    public JsonNode create(Map<String, Object> box, String token, String volume) {
      check(!volume.isBlank(), "Every newly created machine must have a volume");
      creates++;
      return node("{\"id\":\"new-machine\"}");
    }

    @Override
    public void action(Map<String, Object> box, String action) {
      events.add(action);
    }

    @Override
    public void destroy(Map<String, Object> box) {
      deletes++;
      events.add("destroy");
    }

    @Override
    public void deleteVolume(Map<String, Object> box) {
      volumeDeletes++;
      events.add("delete-volume");
      if (failVolume) throw new IllegalStateException("volume busy");
    }
  }

  static class FakeHub extends Hub {
    final List<String> events;
    int saves, unfreezes;
    boolean failSave, wrongSnapshot, logsFlushed = true;
    Runnable duringSave = () -> {};

    FakeHub(Db db, List<String> events) {
      super(db);
      this.events = events;
    }

    @Override
    public boolean connected(String id) {
      return true;
    }

    @Override
    public JsonNode call(String box, String op, Object data, int timeout) {
      if (op.equals("save")) {
        saves++;
        events.add("save");
        check(
            Boolean.FALSE.equals(((Map<?, ?>) data).get("shutdown")),
            "Final save must not kill work before success");
        if (failSave) throw new IllegalStateException("storage unavailable");
        String snapshot = id();
        db.exec(
            "INSERT INTO snapshots VALUES(?,?,?,?,?)", snapshot, box, "{\"entries\":[]}", 0, now());
        db.exec("UPDATE sandboxes SET snapshot_id=? WHERE id=?", snapshot, box);
        duringSave.run();
        return node(
            json(
                Map.of(
                    "verified",
                    true,
                    "logs_flushed",
                    logsFlushed,
                    "snapshot",
                    wrongSnapshot ? "wrong" : snapshot)));
      }
      if (op.equals("unfreeze")) unfreezes++;
      return node("{\"ok\":true}");
    }
  }

  static class Fixture implements AutoCloseable {
    final Db db;
    final List<String> events = new CopyOnWriteArrayList<>();
    final FakeFly fly = new FakeFly(events);
    final FakeHub hub;
    final SandboxService svc;
    final String box = id();

    Fixture() {
      var ds = new JdbcDataSource();
      ds.setURL(
          "jdbc:h2:mem:"
              + box
              + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1");
      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      db = new Db(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
      hub = new FakeHub(db, events);
      var accounting = new CostAccounting(db);
      accounting.run(null);
      svc = new SandboxService(db, fly, hub, accounting, new FixedLifecyclePolicy(db));
      String user = id();
      db.exec(
          "INSERT INTO users(id,username,password_hash,role,created) VALUES(?,?,?,?,?)",
          user,
          user.substring(0, 20),
          "hash",
          "user",
          now());
      db.exec(
          "INSERT INTO"
              + " sandboxes(id,owner_id,name,image_kind,image_ref,state,config,files,created,updated,machine_id,volume_id,app_name,lease_until)"
              + " VALUES(?,?,?,?,?,'running',?,?,?,?,?,?,?,0)",
          box,
          user,
          "test",
          "blank",
          "image",
          Config.DEFAULT,
          "{}",
          now(),
          now(),
          "machine",
          "volume",
          "app");
    }

    Map<String, Object> box() {
      return svc.get(box);
    }

    void set(String assignments, Object... values) {
      Object[] args = Arrays.copyOf(values, values.length + 1);
      args[values.length] = box;
      db.exec("UPDATE sandboxes SET " + assignments + " WHERE id=?", args);
    }

    void due() {
      set("idle_since=?,lease_until=?", now() - 300001, now() - 300001);
    }

    void drain() throws Exception {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (svc.busy.contains(box) && System.nanoTime() < end) Thread.sleep(5);
      check(!svc.busy.contains(box), "Lifecycle worker did not finish");
    }

    @Override
    public void close() throws Exception {
      svc.work.shutdownNow();
      svc.work.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  public static void runAll() throws Exception {
    passed = 0;
    scenario(
        "Five minutes is an absolute deadline, independent of legacy plans",
        f -> {
          long left = now() - 120000;
          f.set("idle_since=?,next_action='suspended',next_action_at=?", left, now() + 86400000);
          f.svc.tick();
          f.drain();
          check(f.hub.saves == 0, "Saved before five minutes");
          check(lng(f.box(), "next_action_at") == left + 300000, "Wrong fixed deadline");
          check("cold".equals(f.box().get("next_action")), "Legacy optimizer action survived");
          f.svc.tick();
          check(f.db.all("SELECT * FROM decisions").size() == 1, "Unchanged plan duplicated");
        });
    scenario(
        "Expired heartbeat establishes idle at lease expiry",
        f -> {
          long expiry = now() - 100000;
          f.set("lease_until=?", expiry);
          f.svc.tick();
          f.drain();
          check(lng(f.box(), "idle_since") == expiry, "Idle duration started late");
          check(lng(f.box(), "next_action_at") == expiry + 300000, "Wrong expiry-based countdown");
        });
    scenario(
        "Due idle policy saves, destroys machine, then deletes volume",
        f -> {
          f.due();
          f.svc.tick();
          f.drain();
          check(
              f.events.equals(List.of("save", "destroy", "delete-volume")),
              "Wrong cleanup order " + f.events);
          check("cold".equals(f.box().get("state")), "Not cold");
          check(
              f.box().get("machine_id") == null && f.box().get("volume_id") == null,
              "Resources still recorded");
          check(f.box().get("snapshot_id") != null, "Snapshot lost");
        });
    scenario(
        "An active lease blocks cleanup",
        f -> {
          f.due();
          f.set("lease_until=?", now() + 60000);
          f.svc.tick();
          f.drain();
          f.svc.transition(f.box, "cold");
          check(f.events.isEmpty(), "Active work interrupted");
        });
    scenario(
        "Heartbeat clears stale absence and gives next absence a fresh five minutes",
        f -> {
          f.due();
          f.hub.touch(f.box);
          f.svc.tick();
          f.drain();
          check(
              f.box().get("idle_since") == null && f.box().get("next_action_at") == null,
              "Old timer retained");
          long expiry = now() - 1;
          f.set("lease_until=?", expiry);
          f.svc.tick();
          f.drain();
          check(lng(f.box(), "next_action_at") == expiry + 300000, "Old absence reused");
        });
    scenario(
        "Failed save retains work and schedules a one-minute retry",
        f -> {
          f.due();
          f.hub.failSave = true;
          f.svc.tick();
          f.drain();
          check(f.fly.deletes == 0 && f.fly.volumeDeletes == 0, "Resources deleted on failure");
          check(
              "running".equals(f.box().get("state")) && f.hub.unfreezes == 1,
              "Not resumed after failure");
          check(lng(f.box(), "policy_retry_at") > now(), "Retry not delayed");
          f.svc.tick();
          f.drain();
          check(f.hub.saves == 1, "Retry storm");
        });
    scenario(
        "Wrong final snapshot acknowledgement blocks deletion",
        f -> {
          f.hub.wrongSnapshot = true;
          fails(() -> f.svc.transition(f.box, "cold"));
          check(f.fly.deletes == 0, "Stale ack allowed deletion");
        });
    scenario(
        "Missing final log acknowledgement blocks deletion",
        f -> {
          f.hub.logsFlushed = false;
          fails(() -> f.svc.transition(f.box, "cold"));
          check(f.fly.deletes == 0, "Unflushed logs discarded");
        });
    scenario(
        "Return during save cancels cleanup",
        f -> {
          f.hub.duringSave = () -> f.hub.touch(f.box);
          f.svc.transition(f.box, "cold");
          check(f.fly.deletes == 0 && f.hub.unfreezes == 1, "Return did not cancel");
          check("running".equals(f.box().get("state")), "Return not running");
        });
    scenario(
        "Actual concurrent open cancels an in-flight save",
        f -> {
          var saveEntered = new CountDownLatch(1);
          var release = new CountDownLatch(1);
          f.hub.duringSave =
              () -> {
                saveEntered.countDown();
                try {
                  check(release.await(3, TimeUnit.SECONDS), "save release timeout");
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              };
          f.due();
          f.svc.tick();
          check(saveEntered.await(3, TimeUnit.SECONDS), "save never entered");
          var opener = CompletableFuture.runAsync(() -> f.svc.open(f.box));
          long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
          while (lng(f.box(), "lease_until") <= now() && System.nanoTime() < limit) Thread.sleep(5);
          check(lng(f.box(), "lease_until") > now(), "Open blocked before publishing return");
          release.countDown();
          opener.get(3, TimeUnit.SECONDS);
          f.drain();
          check(f.fly.deletes == 0, "Concurrent return deleted machine");
        });
    scenario(
        "Workspace growth at final save enforces recovery retention",
        f -> {
          f.hub.duringSave = () -> f.set("bytes=?", 500L * 1048576);
          fails(() -> f.svc.transition(f.box, "cold"));
          check(f.fly.deletes == 0 && f.hub.unfreezes == 1, "Oversized workspace deleted");
        });
    scenario(
        "A measured cold-start timeout blocks automatic cold deletion",
        f -> {
          f.db.exec(
              "INSERT INTO measurements VALUES(?,?,?,?,?,?)",
              id(),
              f.box,
              "cold_failed",
              181,
              0,
              now());
          fails(() -> f.svc.transition(f.box, "cold"));
          check(f.hub.saves == 0 && f.fly.deletes == 0, "Known slow recovery ignored");
        });
    scenario(
        "Failed volume delete can be retried after backend restart",
        f -> {
          f.fly.failVolume = true;
          fails(() -> f.svc.transition(f.box, "cold"));
          check("deleting".equals(f.box().get("state")), "Cleanup commit not persisted");
          check(
              f.box().get("machine_id") == null && f.box().get("volume_id") != null,
              "Partial cleanup state lost");
          f.fly.failVolume = false;
          var restarted =
              new SandboxService(
                  f.db, f.fly, f.hub, new CostAccounting(f.db), new FixedLifecyclePolicy(f.db));
          try {
            restarted.finishCleanup(f.box);
          } finally {
            restarted.work.shutdownNow();
          }
          check(
              f.fly.deletes == 1 && f.fly.volumeDeletes == 2,
              "Retry repeated machine deletion or skipped volume");
          check("cold".equals(f.box().get("state")), "Retry did not complete");
        });
    scenario(
        "Old stopped machine with periodic snapshot is retained",
        f -> {
          f.set("state='stopped',snapshot_id='old-periodic'");
          fails(() -> f.svc.transition(f.box, "cold"));
          check(f.fly.deletes == 0, "Old periodic snapshot treated as final save");
        });
    scenario(
        "Interrupted uncommitted save is unfrozen rather than deleted",
        f -> {
          f.set("state='saving'");
          f.svc.tick();
          f.drain();
          check(f.hub.unfreezes == 1 && f.fly.deletes == 0, "Unsafe interrupted-save recovery");
        });
    scenario(
        "Every cold create provisions and attaches a working volume",
        f -> {
          f.set("state='cold',machine_id=NULL,volume_id=NULL");
          long requested = now() - 1500;
          f.svc.start(f.box, requested);
          check(f.fly.volumes == 1 && f.fly.creates == 1, "Volume was optional");
          check(
              lng(f.box(), "started_at") == requested,
              "Request time was replaced by worker-start time");
        });
    scenario(
        "Provider not-found delete is idempotent; other errors still fail",
        f -> {
          Fly alreadyGone =
              new Fly() {
                @Override
                public JsonNode request(String m, String p, Object b) {
                  throw new IllegalStateException("Fly DELETE returned 404");
                }
              };
          alreadyGone.destroy(f.box());
          alreadyGone.deleteVolume(f.box());
          Fly denied =
              new Fly() {
                @Override
                public JsonNode request(String m, String p, Object b) {
                  throw new IllegalStateException("Fly DELETE returned 403");
                }
              };
          fails(() -> denied.destroy(f.box()));
        });
    scenario(
        "Final usage interval is recorded before resources disappear",
        f -> {
          f.db.exec("INSERT INTO settings VALUES(?,?)", "meter:" + f.box, "" + (now() - 30000));
          f.svc.transition(f.box, "cold");
          var usage =
              f.db.one("SELECT SUM(estimated_usd) AS cost FROM usage WHERE sandbox_id=?", f.box);
          check(((Number) usage.get("cost")).doubleValue() > 0, "Final running interval was lost");
          check(
              f.svc.accounting.rate(f.box(), "cold") == 0,
              "Cold machine still incurs compute/volume estimate");
        });
    scenario(
        "Expired queued startup cannot create provider resources",
        f -> {
          f.set("state='starting',next_action='cold',machine_id=NULL,volume_id=NULL");
          fails(() -> f.svc.start(f.box, now() - 180001));
          check(
              f.fly.creates == 0 && f.fly.volumes == 0,
              "Expired queued startup still provisioned resources");
        });
    scenario(
        "Open publishes its startup deadline before executor work begins",
        f -> {
          f.set("state='cold',machine_id=NULL,volume_id=NULL");
          var gate = new CountDownLatch(1);
          for (int i = 0; i < 4; i++)
            f.svc.work.submit(
                () -> {
                  try {
                    gate.await();
                  } catch (InterruptedException ignored) {
                  }
                });
          try {
            f.svc.open(f.box);
            check(
                "starting".equals(f.box().get("state")), "Queued request is invisible to watchdog");
            check(lng(f.box(), "started_at") > 0, "No queued startup timestamp");
            check(f.fly.creates == 0, "Executor was not blocked");
          } finally {
            gate.countDown();
            f.drain();
          }
        });
    System.out.println("Fixed policy checks passed: " + passed);
  }

  public static void main(String[] args) throws Exception {
    runAll();
  }
}
