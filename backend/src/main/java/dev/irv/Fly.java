package dev.irv;

import org.springframework.context.annotation.Profile;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.*;

@Profile("!auth-only")
@Component
public class Fly {
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  final String host = env("FLY_API_URL", "https://api.machines.dev/v1");

  public JsonNode request(String method, String path, Object body) {
    String token = env("FLY_API_TOKEN", "");
    if (token.isEmpty()) throw bad("Configure FLY_API_TOKEN before creating a sandbox");
    try {
      var b =
          HttpRequest.newBuilder(URI.create(host + path))
              .timeout(Duration.ofSeconds(30))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json");
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json(body)));
      var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2)
        throw new IllegalStateException(
            "Fly "
                + method
                + " "
                + path
                + " returned "
                + res.statusCode()
                + ": "
                + res.body().substring(0, Math.min(1000, res.body().length())));
      return res.body().isBlank() ? node("{}") : node(res.body());
    } catch (java.io.IOException | InterruptedException e) {
      throw new IllegalStateException("Fly API unavailable", e);
    }
  }

  public void ensureApp(String name) {
    try {
      request("GET", "/apps/" + name, null);
    } catch (IllegalStateException e) {
      if (!e.getMessage().contains("returned 404")) throw e;
      request(
          "POST",
          "/apps",
          Map.of("app_name", name, "org_slug", env("FLY_ORG", ""), "network", name));
    }
  }

  public JsonNode machine(Map<String, Object> s) {
    return request("GET", "/apps/" + s.get("app_name") + "/machines/" + s.get("machine_id"), null);
  }

  public JsonNode create(Map<String, Object> s, String token, String volume) {
    String app = str(s, "app_name");
    ensureApp(app);
    Map<String, Object> config = new HashMap<>();
    config.put("image", s.get("image_ref"));
    config.put("auto_destroy", false);
    config.put("restart", Map.of("policy", "no"));
    config.put(
        "guest",
        Map.of("cpu_kind", "shared", "cpus", 2, "memory_mb", num("IRV_MACHINE_MEMORY_MB", 4096)));
    config.put(
        "env",
        Map.of(
            "IRV_API",
            env("IRV_PUBLIC_ORIGIN", "http://localhost:8080"),
            "IRV_AGENT_TOKEN",
            token,
            "IRV_WORKSPACE_BASE",
            volume.isEmpty() ? "/home/dev" : "/persist"));
    if (!volume.isEmpty())
      config.put("mounts", List.of(Map.of("volume", volume, "path", "/persist")));
    return request(
        "POST",
        "/apps/" + app + "/machines",
        Map.of(
            "name",
            "irv-" + str(s, "id").substring(0, 8),
            "region",
            env("FLY_REGION", "iad"),
            "config",
            config));
  }

  public String volume(Map<String, Object> s) {
    return request(
            "POST",
            "/apps/" + s.get("app_name") + "/volumes",
            Map.of(
                "name",
                "workspace",
                "region",
                env("FLY_REGION", "iad"),
                "size_gb",
                num("IRV_VOLUME_GB", 10),
                "encrypted",
                true,
                "snapshot_retention",
                0))
        .path("id")
        .asText();
  }

  public void action(Map<String, Object> s, String action) {
    request(
        "POST",
        "/apps/" + s.get("app_name") + "/machines/" + s.get("machine_id") + "/" + action,
        action.equals("stop") ? Map.of("signal", "SIGTERM", "timeout", "30s") : Map.of());
  }

  void deleteIfPresent(String path) {
    try {
      request("DELETE", path, null);
    } catch (IllegalStateException e) {
      // A previous DELETE may have succeeded before the connection/backend failed.
      if (!e.getMessage().contains("returned 404")) throw e;
    }
  }

  public void destroy(Map<String, Object> s) {
    deleteIfPresent(
        "/apps/" + s.get("app_name") + "/machines/" + s.get("machine_id") + "?force=true");
  }

  public void deleteVolume(Map<String, Object> s) {
    deleteIfPresent("/apps/" + s.get("app_name") + "/volumes/" + s.get("volume_id"));
  }

  public String logs(String app) {
    if (!env("IRV_FLY_LOGS", "false").equals("true")) return "";
    try {
      Process p =
          new ProcessBuilder(
                  env("FLYCTL_PATH", "flyctl"), "logs", "--app", app, "--json", "--no-tail")
              .redirectErrorStream(true)
              .start();
      var read =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return new String(
                      p.getInputStream().readNBytes(512 * 1024),
                      java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                  throw new IllegalStateException("Fly log collector unavailable", e);
                }
              });
      if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("Fly container log collection timed out");
      }
      if (p.exitValue() != 0)
        throw new IllegalStateException("Fly container log collector exited " + p.exitValue());
      return read.get(2, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("Fly log collector unavailable", e);
    }
  }
}
