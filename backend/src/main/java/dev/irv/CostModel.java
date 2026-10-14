package dev.irv;

import java.util.*;

/**
 * Small Bayesian piecewise hazard model + finite-horizon predictive controller. All rates
 * USD/second.
 */
public final class CostModel {
  public static final double[] BINS = {
    0,
    60,
    180,
    600,
    1800,
    3600,
    14400,
    43200,
    86400,
    259200,
    604800,
    2592000,
    Double.POSITIVE_INFINITY
  };

  public record Absence(double seconds, boolean returned, double weight) {}

  public static double hazard(double age, List<Absence> history) {
    int bin = 0;
    while (bin + 1 < BINS.length && age >= BINS[bin + 1]) bin++;
    double events = .5, exposure = .5 * 21600; // weak six-hour prior
    for (var x : history) {
      double observed = Math.max(0, Math.min(x.seconds, BINS[bin + 1]) - BINS[bin]);
      exposure += observed * x.weight;
      if (x.returned && x.seconds >= BINS[bin] && x.seconds < BINS[bin + 1]) events += x.weight;
    }
    return events / Math.max(1, exposure);
  }

  public record Mode(
      String name, double rate, double restartUsd, double recoveryBound, boolean allowed) {}

  public record Choice(
      String action,
      double delay,
      double expectedUsd,
      Map<String, Double> alternatives,
      String reason) {}

  public static Choice optimize(
      List<Mode> modes,
      int current,
      double age,
      List<Absence> history,
      double[][] transition,
      double margin) {
    // Fine resolution early; coarser farther out. Tail keeps its holding/recovery cost.
    ArrayList<Double> times = new ArrayList<>();
    times.add(0.0);
    double t = 0;
    while (t < 604800) {
      double step = t < 1800 ? 30 : t < 21600 ? 300 : 3600;
      t = Math.min(604800, t + step);
      times.add(t);
    }
    int n = times.size(), m = modes.size();
    double[][] v = new double[n][m];
    int[][] policy = new int[n - 1][m];
    for (int s = 0; s < m; s++) {
      var mode = modes.get(s);
      v[n - 1][s] = mode.rate / Math.max(1e-8, hazard(age + 604800, history)) + mode.restartUsd;
    }
    for (int i = n - 2; i >= 0; i--) {
      double dt = times.get(i + 1) - times.get(i),
          h = hazard(age + times.get(i), history),
          q = -Math.expm1(-h * dt),
          hold = q / h;
      for (int s = 0; s < m; s++) {
        double best = Double.POSITIVE_INFINITY;
        int action = s;
        for (int a = 0; a < m; a++) {
          var mode = modes.get(a);
          if (!mode.allowed || mode.recoveryBound > 180 || !Double.isFinite(transition[s][a]))
            continue;
          double cost =
              transition[s][a] + mode.rate * hold + q * mode.restartUsd + (1 - q) * v[i + 1][a];
          if (cost < best) {
            best = cost;
            action = a;
          }
        }
        v[i][s] = best;
        policy[i][s] = action;
      }
    }
    Map<String, Double> alternatives = new LinkedHashMap<>();
    double dt = times.get(1), h = hazard(age, history), q = -Math.expm1(-h * dt);
    for (int a = 0; a < m; a++) {
      var mode = modes.get(a);
      if (mode.allowed && mode.recoveryBound <= 180 && Double.isFinite(transition[current][a]))
        alternatives.put(
            mode.name,
            transition[current][a] + mode.rate * q / h + q * mode.restartUsd + (1 - q) * v[1][a]);
    }
    int target = policy[0][current];
    if (target != current
        && alternatives.containsKey(modes.get(current).name)
        && alternatives.get(modes.get(current).name) - v[0][current] < margin) target = current;
    double delay = 0;
    if (target == current) {
      for (int i = 1; i < n - 1; i++) {
        if (policy[i][current] != current) {
          target = policy[i][current];
          delay = times.get(i);
          break;
        }
      }
      if (target == current) delay = 3600;
    }
    return new Choice(
        modes.get(target).name,
        delay,
        v[0][current],
        alternatives,
        "Minimum estimated future cost with recovery bound <=180s; censored return history and"
            + " transition costs included");
  }
}
