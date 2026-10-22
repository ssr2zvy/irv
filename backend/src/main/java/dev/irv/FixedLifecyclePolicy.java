package dev.irv;


import static dev.irv.Util.*;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Deterministic idle policy. It never trains a model or selects a cheaper lifecycle state. */
@DeferredFeature
@Component
public class FixedLifecyclePolicy {
  public static final long IDLE_MILLIS = 300_000;
  public static final long RETRY_MILLIS = 60_000;
  public static final long STARTUP_MILLIS = 180_000;
  private final Db db;

  public FixedLifecyclePolicy(Db db) {
    this.db = db;
  }

  public long saveAt(Map<String, Object> sandbox) {
    return lng(sandbox, "idle_since") + IDLE_MILLIS;
  }

  // A retention exception, not an optimizer: never knowingly choose a slow cold restore.
  // This empirical estimate is not a provider guarantee. See docs/ALGORITHM.md.
  public double coldRecoverySeconds(Map<String, Object> sandbox) {
    double seconds = 100 + lng(sandbox, "bytes") / (5.0 * 1048576);
    for (var row :
        db.all(
            "SELECT seconds FROM measurements WHERE sandbox_id=? AND kind IN ('cold','cold_failed')"
                + " ORDER BY created DESC LIMIT 30",
            sandbox.get("id"))) {
      seconds = Math.max(seconds, ((Number) row.get("seconds")).doubleValue() * 1.3 + 5);
    }
    return seconds;
  }

  public void requireColdRecovery(Map<String, Object> sandbox) {
    if (coldRecoverySeconds(sandbox) > STARTUP_MILLIS / 1000.0)
      throw conflict("Retaining environment: estimated cold recovery exceeds 180 seconds");
  }
}
