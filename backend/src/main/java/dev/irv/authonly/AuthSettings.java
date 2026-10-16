package dev.irv.authonly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.core.env.Environment;

/** Operator-controlled admissions, deliberately separate from provider account existence. */
public final class AuthSettings {
  public static final String ISSUER = "https://irv-instance-4ftbgu.us1.zitadel.cloud";
  public static final String ORIGIN = "https://iruvu.space";
  public static final String CLIENT = "395286007051839802";
  public static final String PROJECT = "395285999653022010";
  public static final String ORG = "395285989452532411";
  public static final String ROLE = "irv_user";
  final String ingressSecret;
  final Map<String, Set<String>> admissions;
  final Set<String> certificates;

  AuthSettings(Environment env, ObjectMapper mapper) {
    ingressSecret = env.getProperty("IRV_AUTH_INGRESS_SECRET", "");
    if (!ingressSecret.isEmpty() && !ingressSecret.matches("[a-fA-F0-9]{64}"))
      throw new IllegalStateException("IRV_AUTH_INGRESS_SECRET must be 64 hex characters");
    try {
      Map<String, List<String>> input = mapper.readValue(
          env.getProperty("IRV_AUTH_ADMISSIONS_JSON", "{}"), new TypeReference<>() {});
      if (input == null || input.size() > 100) throw new IllegalArgumentException();
      Map<String, Set<String>> copy = new HashMap<>();
      Set<String> all = new HashSet<>();
      for (var entry : input.entrySet()) {
        if (!entry.getKey().matches("[0-9]{1,30}") || entry.getValue() == null || entry.getValue().isEmpty())
          throw new IllegalArgumentException();
        Set<String> fps = new HashSet<>();
        for (String fp : entry.getValue()) {
          if (fp == null || !fp.matches("[a-f0-9]{64}") || !all.add(fp))
            throw new IllegalArgumentException(); // One certificate cannot belong to two accounts.
          fps.add(fp);
        }
        copy.put(entry.getKey(), Set.copyOf(fps));
      }
      admissions = Map.copyOf(copy);
      // The earlier allowlist permits starting diagnostics/login, never account admission.
      for (String fp : env.getProperty("IRV_AUTH_ALLOWED_CERT_SHA256", "").split(",")) {
        if (fp.isBlank()) continue;
        fp = fp.trim().toLowerCase(Locale.ROOT);
        if (!fp.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        all.add(fp);
      }
      certificates = Set.copyOf(all);
    } catch (Exception ex) {
      // Do not include configuration values in exceptions or logs.
      throw new IllegalStateException("Invalid auth admissions/certificate configuration");
    }
  }

  boolean admits(String subject, String fingerprint) {
    return admissions.getOrDefault(subject, Set.of()).contains(fingerprint);
  }
}
