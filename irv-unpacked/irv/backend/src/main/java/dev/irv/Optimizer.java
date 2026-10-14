package dev.irv;

import static dev.irv.Util.*;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.stereotype.*;

@Component
public class Optimizer implements ApplicationRunner {
  final Db db;

  public Optimizer(Db d) {
    db = d;
  }

  public void run(ApplicationArguments a) {
    Map<String, Double> defaults =
        Map.of(
            "running_hour_usd",
            .036,
            "rootfs_gb_month_usd",
            .15,
            "volume_gb_month_usd",
            .15,
            "object_gb_month_usd",
            .02,
            "transfer_gb_usd",
            .02,
            "request_1000_usd",
            .005,
            "rootfs_gb",
            4.0);
    defaults.forEach(
        (k, v) -> {
          if (db.one("SELECT name FROM prices WHERE name=?", k) == null)
            db.exec("INSERT INTO prices VALUES(?,?,?)", k, v, now());
        });
  }

  public double price(String k) {
    return ((Number) db.one("SELECT value FROM prices WHERE name=?", k).get("value")).doubleValue();
  }

  public double bound(String id, String kind, double prior) {
    var rows =
        db.all(
            "SELECT seconds FROM measurements WHERE sandbox_id=? AND kind=? ORDER BY created DESC"
                + " LIMIT 30",
            id,
            kind);
    double max = prior;
    for (var row : rows) max = Math.max(max, ((Number) row.get("seconds")).doubleValue() * 1.3 + 5);
    return max;
  }

  public double rate(Map<String, Object> s, String state) {
    double volume =
        str(s, "volume_id").isEmpty()
            ? 0
            : num("IRV_VOLUME_GB", 10) * price("volume_gb_month_usd") / 2592000.0;
    return switch (state) {
      case "running", "starting", "saving" -> price("running_hour_usd") / 3600 + volume;
      case "stopped", "suspended" ->
          price("rootfs_gb") * price("rootfs_gb_month_usd") / 2592000 + volume;
      case "volume" -> volume;
      default -> 0;
    };
  }

  public CostModel.Choice decide(Map<String, Object> s) {
    String id = str(s, "id"), owner = str(s, "owner_id"), state = str(s, "state");
    double age = Math.max(0, (now() - lng(s, "idle_since")) / 1000.0),
        bytes = lng(s, "bytes"),
        run = price("running_hour_usd") / 3600;
    List<CostModel.Absence> history = new ArrayList<>();
    for (var row :
        db.all("SELECT * FROM returns WHERE owner_id=? ORDER BY left_at DESC LIMIT 500", owner)) {
      double secs =
          ((row.get("returned_at") == null ? now() : lng(row, "returned_at")) - lng(row, "left_at"))
              / 1000.0;
      double weight = Math.pow(.5, (now() - lng(row, "left_at")) / (30 * 86400000.0));
      if (!id.equals(row.get("sandbox_id"))) weight *= .25;
      history.add(new CostModel.Absence(secs, row.get("returned_at") != null, weight));
    }
    double save = bound(id, "save", 20 + bytes / (5 * 1048576)),
        cold = bound(id, "cold", 100 + bytes / (5 * 1048576)),
        volume = bound(id, "volume", 80),
        stop = bound(id, "stopped", 60),
        suspend = bound(id, "suspended", 30);
    boolean hasVolume = !str(s, "volume_id").isEmpty();
    boolean suspendOK = !env("IRV_SUSPEND_ENABLED", "true").equals("false");
    double restoreCost =
        cold * run
            + bytes / 1073741824.0 * price("transfer_gb_usd")
            + Math.ceil(bytes / 4194304) * price("request_1000_usd") / 1000;
    var delta =
        db.one(
            "SELECT bytes FROM measurements WHERE sandbox_id=? AND kind='save' ORDER BY created"
                + " DESC LIMIT 1",
            id);
    double changed = delta == null ? bytes : Math.min(bytes, lng(delta, "bytes"));
    var modes =
        List.of(
            new CostModel.Mode("running", rate(s, "running"), 0, 0, true),
            new CostModel.Mode(
                "suspended", rate(s, "suspended"), suspend * run, suspend, suspendOK),
            new CostModel.Mode("stopped", rate(s, "stopped"), stop * run, stop, true),
            new CostModel.Mode("volume", rate(s, "volume"), volume * run, volume, hasVolume),
            new CostModel.Mode("cold", 0, restoreCost, cold, true));
    int current =
        switch (state) {
          case "running" -> 0;
          case "suspended" -> 1;
          case "stopped" -> 2;
          case "volume" -> 3;
          default -> 4;
        };
    double[][] trans = new double[5][5];
    for (int a = 0; a < 5; a++)
      for (int b = 0; b < 5; b++) {
        trans[a][b] = a == b ? 0 : Double.POSITIVE_INFINITY;
        if (a == 0 && b > 0)
          trans[a][b] =
              save * run
                  + changed / 1073741824.0 * price("transfer_gb_usd")
                  + Math.ceil(changed / 4194304) * price("request_1000_usd") / 1000;
        if ((a == 1 || a == 2) && b >= 3) trans[a][b] = 0;
        if (a == 3 && b == 4) trans[a][b] = 0;
      }
    if (current > 0 && modes.get(current).recoveryBound() > 180)
      return new CostModel.Choice(
          "running",
          0,
          0,
          Map.of(),
          "Recovery bound exceeded; prewarm to protect the startup limit");
    var c = CostModel.optimize(modes, current, age, history, trans, .00001);
    // Three minutes is only the cold-start prior grace. It disappears with observed returns.
    long completed = history.stream().filter(CostModel.Absence::returned).count();
    if (completed < 5 && age < 180 && current == 0)
      return new CostModel.Choice(
          c.action(),
          180 - age,
          c.expectedUsd(),
          c.alternatives(),
          "Initial 180-second idle grace; startup deadline is separately 180 seconds");
    return c;
  }
}
