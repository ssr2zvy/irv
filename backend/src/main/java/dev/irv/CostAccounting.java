package dev.irv;


import static dev.irv.Util.*;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.stereotype.*;

@DeferredFeature
@Component
public class CostAccounting implements ApplicationRunner {
  final Db db;

  public CostAccounting(Db d) {
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

  public double rate(Map<String, Object> s, String state) {
    double volume =
        str(s, "volume_id").isEmpty()
            ? 0
            : (s.get("volume_gb") == null ? num("IRV_VOLUME_GB", 10) : lng(s, "volume_gb"))
                * price("volume_gb_month_usd")
                / 2592000.0;
    return switch (state) {
      case "running", "starting", "saving", "deleting", "failed" ->
          (str(s, "machine_id").isEmpty() ? 0 : price("running_hour_usd") / 3600) + volume;
      case "stopped", "suspended" ->
          price("rootfs_gb") * price("rootfs_gb_month_usd") / 2592000 + volume;
      case "volume" -> volume;
      default -> volume;
    };
  }
}
