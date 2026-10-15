package dev.irv;

import static dev.irv.Util.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Standalone harness also run through SyncTest under Maven. */
public class SyncChecks {
  static int checks = 0;

  static void ok(boolean v, String message) {
    if (!v) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }

  static void rejected(Runnable r, String m) {
    try {
      r.run();
      throw new AssertionError(m);
    } catch (org.springframework.web.server.ResponseStatusException expected) {
      ok(true, m);
    }
  }

  static Map<String, Object> map(Object o) {
    return JSON.convertValue(o, Map.class);
  }

  static long number(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).longValue();
  }

  public static void main(String[] args) throws Exception {
    var ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:sync"
            + id()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1");
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    var db = new Db(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
    String box = id(), user = id();
    db.exec(
        "INSERT INTO users(id,username,password_hash,role,created) VALUES(?,?,?,'user',?)",
        user,
        "test",
        "hash",
        now());
    db.exec(
        "INSERT INTO"
            + " sandboxes(id,owner_id,name,image_kind,image_ref,state,config,files,created,updated,agent_hash)"
            + " VALUES(?,?,?,'blank','image','cold',?,?,?,?,?)",
        box,
        user,
        "test",
        Config.DEFAULT,
        json(Map.of("scripts/environment.sh", "export A=1\n")),
        now(),
        now(),
        hash("test-agent"));
    var sync = new ConfigSync(db);
    String a = "aaaaaaaaaaaaaaaa", b = "bbbbbbbbbbbbbbbb";
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    var futures = new ArrayList<Future<Boolean>>();
    for (String e : List.of(a, b))
      futures.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  new ConfigSync(db).act(box, "acquire", node(json(Map.of("editor", e))), "app");
                  return true;
                } catch (Exception x) {
                  return false;
                }
              }));
    start.countDown();
    int won = 0;
    for (var f : futures) if (f.get()) won++;
    pool.shutdown();
    ok(won == 1, "two backend instances grant exactly one edit lease");
    var held = db.one("SELECT * FROM config_leases WHERE sandbox_id=?", box);
    String owner = str(held, "editor_id"), other = owner.equals(a) ? b : a;
    var owning =
        map(sync.act(box, "acquire", node(json(Map.of("editor", owner, "takeover", true))), "app"));
    var auth =
        new LinkedHashMap<String, Object>(
            Map.of(
                "editor",
                owner,
                "fence",
                number(owning, "fence"),
                "lease_token",
                owning.get("lease_token")));
    rejected(
        () -> sync.act(box, "renew", node(json(auth)), "sandbox"),
        "other editing surface cannot renew app lease");
    var takeover =
        map(
            sync.act(
                box, "acquire", node(json(Map.of("editor", other, "takeover", true))), "sandbox"));
    rejected(() -> sync.act(box, "renew", node(json(auth)), "app"), "takeover fences old editor");
    var body =
        new LinkedHashMap<String, Object>(
            Map.of(
                "editor",
                other,
                "fence",
                number(takeover, "fence"),
                "lease_token",
                takeover.get("lease_token"),
                "revision",
                1,
                "draft_version",
                1,
                "config",
                "invalid toml = [",
                "files",
                Map.of("scripts/environment.sh", "echo draft"),
                "request_id",
                "request_0000000001"));
    var invalid = map(sync.act(box, "commit", node(json(body)), "sandbox"));
    ok(
        !Boolean.TRUE.equals(invalid.get("committed")),
        "invalid draft is preserved without activation");
    ok(
        lng(db.one("SELECT revision FROM sandboxes WHERE id=?", box), "revision") == 1,
        "invalid draft leaves committed revision intact");
    body.put("config", Config.DEFAULT);
    body.put("draft_version", 2);
    var committed = map(sync.act(box, "commit", node(json(body)), "sandbox"));
    ok(number(committed, "revision") == 2, "valid bundle commits new revision");
    sync.act(box, "release", node(json(body)), "sandbox");
    ok(
        number(map(sync.act(box, "commit", node(json(body)), "sandbox")), "revision") == 2,
        "lost commit response is idempotent after release");
    var different = new LinkedHashMap<>(body);
    different.put("config", Config.DEFAULT + "\n# different");
    rejected(
        () -> sync.act(box, "commit", node(json(different)), "sandbox"),
        "request ID cannot be reused for different content");
    var again =
        map(new ConfigSync(db).act(box, "acquire", node(json(Map.of("editor", owner))), "app"));
    var recovered =
        map(
            sync.act(
                box,
                "recover",
                node(
                    json(
                        Map.of(
                            "editor",
                            owner,
                            "fence",
                            number(again, "fence"),
                            "lease_token",
                            again.get("lease_token"),
                            "draft_editor",
                            other))),
                "app"));
    ok(recovered.containsKey("bundle"), "draft recovery survives service recreation");
    db.exec("UPDATE config_leases SET expires=0 WHERE sandbox_id=?", box);
    rejected(
        () ->
            sync.act(
                box,
                "renew",
                node(
                    json(
                        Map.of(
                            "editor",
                            owner,
                            "fence",
                            number(again, "fence"),
                            "lease_token",
                            again.get("lease_token")))),
                "app"),
        "expired lease cannot renew itself");
    var changes = node(json(sync.pending(box, 0)));
    ok(changes.path("versions").size() == 2, "pending revisions are durable and ordered");
    var v = changes.path("versions").get(1);
    sync.ack(box, 2, v.path("content_hash").asText(), "");
    ok(
        lng(db.one("SELECT applied_revision FROM sandboxes WHERE id=?", box), "applied_revision")
            == 2,
        "machine acknowledgement advances applied revision");
    rejected(
        () -> sync.ack(box, 2, "wrong", ""), "incorrect revision hash cannot acknowledge delivery");
    var agent = new AgentApi(db, null, null);
    HttpServletRequest req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                SyncChecks.class.getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, x) -> m.getName().equals("getHeader") ? "Bearer test-agent" : null);
    var event =
        Map.of(
            "id",
            "log_event_0000001",
            "source",
            "process:java",
            "stream",
            "stderr",
            "message",
            "failure",
            "session",
            "past-session",
            "timestamp",
            1234567,
            "sequence",
            4,
            "producer",
            "producer-one");
    agent.events(node(json(List.of(event))), req);
    var ack = node(json(agent.events(node(json(List.of(event))), req)));
    ok(
        ack.path("ack").get(0).asText().equals("log_event_0000001"),
        "log replay explicitly acknowledges original event ID");
    var row = db.one("SELECT * FROM logs WHERE event_key=?", box + ":log_event_0000001");
    ok(
        lng(row, "created") == 1234567
            && str(row, "session_id").equals("past-session")
            && str(row, "stream").equals("stderr"),
        "delayed logs retain occurrence time, original session and stream");
    ok(
        db.jdbc.queryForObject("SELECT COUNT(*) FROM logs", Integer.class) == 1,
        "log replay does not duplicate rows");
    var bad = new HashMap<String, Object>(event);
    bad.put("id", "bad event !");
    var valid = new HashMap<String, Object>(event);
    valid.put("id", "log_event_0000002");
    rejected(() -> agent.events(node(json(List.of(valid, bad))), req), "malformed batch rejected");
    ok(
        db.jdbc.queryForObject("SELECT COUNT(*) FROM logs", Integer.class) == 1,
        "failed batch rolls back without partial acknowledgement");
    final int[] removed = {0};
    var fly =
        new Fly() {
          @Override
          public com.fasterxml.jackson.databind.JsonNode machine(Map<String, Object> s) {
            return node("{\"state\":\"stopped\"}");
          }

          @Override
          public void destroy(Map<String, Object> s) {
            removed[0]++;
          }

          @Override
          public void deleteVolume(Map<String, Object> s) {
            throw new AssertionError("Upgrade must retain volume");
          }
        };
    var svc = new SandboxService(db, fly, null, null, null);
    db.exec("INSERT INTO settings(name,value) VALUES(?,?)", "image:blank", "image:new");
    db.exec(
        "UPDATE sandboxes SET"
            + " state='running',machine_id='m',volume_id='v',snapshot_id='saved',lease_until=0"
            + " WHERE id=?",
        box);
    rejected(() -> svc.rebuildStopped(box), "image migration refuses a running environment");
    db.exec("UPDATE sandboxes SET state='stopped' WHERE id=?", box);
    svc.rebuildStopped(box);
    var migrated = svc.get(box);
    ok(
        removed[0] == 1
            && str(migrated, "volume_id").equals("v")
            && migrated.get("machine_id") == null,
        "image migration removes only Machine and retains working volume");
    ok(
        str(migrated, "image_ref").equals("image:new") && str(migrated, "state").equals("cold"),
        "next start uses the selected image on retained data");
    System.out.println("Sync checks passed: " + checks);
  }
}
