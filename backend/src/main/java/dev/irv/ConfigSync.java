package dev.irv;

import static dev.irv.Util.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** Database-serialized, fenced edit sessions. Never extends a compute lease. */
@Component
public class ConfigSync {
  final Db db;

  public ConfigSync(Db db) {
    this.db = db;
  }

  Map<String, Object> locked(String id) {
    var s = db.one("SELECT * FROM sandboxes WHERE id=? FOR UPDATE", id);
    if (s == null) throw bad("Unknown sandbox");
    if (db.one("SELECT sandbox_id FROM config_leases WHERE sandbox_id=?", id) == null)
      db.exec("INSERT INTO config_leases(sandbox_id) VALUES(?)", id);
    if (db.one(
            "SELECT revision FROM config_versions WHERE sandbox_id=? AND revision=?",
            id,
            lng(s, "revision"))
        == null) {
      // Register legacy app-side helpers explicitly once, without losing their contents.
      if (!Config.parse(str(s, "config")).contains("helpers")) {
        var paths = new ArrayList<String>();
        node(str(s, "files")).fieldNames().forEachRemaining(paths::add);
        paths.remove(Config.parse(str(s, "config")).getString("entrypoint"));
        String normalized = "helpers = " + json(paths) + "\n" + str(s, "config");
        db.exec("UPDATE sandboxes SET config=? WHERE id=?", normalized, id);
        s.put("config", normalized);
      }
      String b = json(bundle(s));
      db.exec(
          "INSERT INTO config_versions VALUES(?,?,?,?,?)",
          id,
          lng(s, "revision"),
          b,
          hash(b),
          now());
    }
    return s;
  }

  public Map<String, Object> bundle(Map<String, Object> s) {
    return Map.of(
        "config", str(s, "config"), "files", node(str(s, "files")), "revision", lng(s, "revision"));
  }

  String editor(JsonNode b) {
    String e = b.path("editor").asText();
    if (!e.matches("[a-zA-Z0-9_-]{16,80}")) throw bad("Invalid editor session");
    return e;
  }

  Map<String, Object> lease(String id) {
    return db.one("SELECT * FROM config_leases WHERE sandbox_id=?", id);
  }

  void authorize(String id, JsonNode b, String origin) {
    var l = lease(id);
    if (!hash(b.path("lease_token").asText()).equals(str(l, "secret_hash"))
        || !editor(b).equals(str(l, "editor_id"))
        || !origin.equals(str(l, "origin"))
        || b.path("fence").asLong(-1) != lng(l, "fence")
        || lng(l, "expires") <= now())
      throw conflict(
          "Editing lease expired or belongs to another session. Your saved draft is retained.");
  }

  public Object status(String id) {
    return db.transaction(
        () -> {
          var s = locked(id);
          var l = lease(id);
          var out = new LinkedHashMap<String, Object>(bundle(s));
          out.put(
              "lease",
              Map.of(
                  "editor",
                  str(l, "editor_id"),
                  "origin",
                  str(l, "origin"),
                  "expires",
                  lng(l, "expires")));
          out.put("applied_revision", lng(s, "applied_revision"));
          out.put("error", str(s, "sync_error"));
          out.put(
              "drafts",
              db.all(
                  "SELECT editor_id,version,base_revision,validation_error,updated FROM"
                      + " config_drafts WHERE sandbox_id=? ORDER BY updated DESC",
                  id));
          return out;
        });
  }

  public Object act(String id, String op, JsonNode b, String origin) {
    return db.transaction(
        () -> {
          var s = locked(id);
          String e = editor(b);
          var l = lease(id);
          if (op.equals("acquire")) {
            boolean same =
                e.equals(str(l, "editor_id"))
                    && origin.equals(str(l, "origin"))
                    && hash(b.path("lease_token").asText()).equals(str(l, "secret_hash"));
            if (lng(l, "expires") > now() && !same && !b.path("takeover").asBoolean())
              throw conflict("Configuration is being edited in " + str(l, "origin"));
            long fence = lng(l, "fence") + (same && lng(l, "expires") > now() ? 0 : 1);
            String secret = token();
            db.exec(
                "UPDATE config_leases SET editor_id=?,origin=?,secret_hash=?,fence=?,expires=?"
                    + " WHERE sandbox_id=?",
                e,
                origin,
                hash(secret),
                fence,
                now() + 120000,
                id);
            var out = new LinkedHashMap<String, Object>(bundle(s));
            out.put("fence", fence);
            out.put("lease_token", secret);
            out.put("expires", now() + 120000);
            var draft =
                db.one("SELECT * FROM config_drafts WHERE sandbox_id=? AND editor_id=?", id, e);
            if (draft != null) out.put("draft", draft);
            return out;
          }
          // A committed request can be retried after release/expiry, with identical payload only.
          String req = b.path("request_id").asText();
          if (op.equals("commit")) {
            if (!req.matches("[a-zA-Z0-9_-]{16,80}")) throw bad("Commit request ID required");
            var prior =
                db.one(
                    "SELECT * FROM config_requests WHERE sandbox_id=? AND request_id=?", id, req);
            if (prior != null) {
              if (!hash(json(b)).equals(str(prior, "request_hash")))
                throw conflict("Request ID reused with different content");
              return node(str(prior, "response"));
            }
          }
          authorize(id, b, origin);
          if (op.equals("renew")) {
            db.exec("UPDATE config_leases SET expires=? WHERE sandbox_id=?", now() + 120000, id);
            return Map.of("expires", now() + 120000);
          }
          if (op.equals("release")) {
            db.exec(
                "UPDATE config_leases SET expires=0,editor_id=NULL,origin=NULL WHERE sandbox_id=?",
                id);
            return Map.of("ok", true);
          }
          if (op.equals("recover")) {
            var d =
                db.one(
                    "SELECT bundle,base_revision FROM config_drafts WHERE sandbox_id=? AND"
                        + " editor_id=?",
                    id,
                    b.path("draft_editor").asText());
            if (d == null) throw bad("Draft not found");
            return d;
          }
          if (!Set.of("draft", "commit").contains(op)) throw bad("Unknown sync operation");
          String config = b.path("config").asText();
          if (config.length() > 65536) throw bad("Configuration too large");
          var files = Config.files(b.path("files"));
          var deleted = new TreeSet<String>();
          for (var n : b.path("delete")) {
            Config.relative(n.asText());
            deleted.add(n.asText());
          }
          var content = Map.of("config", config, "files", files, "delete", deleted);
          long version = b.path("draft_version").asLong(-1);
          var d =
              db.one("SELECT version FROM config_drafts WHERE sandbox_id=? AND editor_id=?", id, e);
          if (d != null && version <= lng(d, "version"))
            throw conflict("A newer saved draft exists; reload it before saving");
          String error = "";
          try {
            var parsed = Config.parse(config);
            if (!Config.managed(config).equals(files.keySet()))
              throw bad("Files must exactly match entrypoint plus helpers");
            if (!Config.parse(str(s, "config"))
                    .getString("folder_name")
                    .equals(parsed.getString("folder_name"))
                && (s.get("machine_id") != null || s.get("snapshot_id") != null))
              throw bad("Folder name is fixed after first start");
            for (String p : deleted)
              if (files.containsKey(p) || p.equals("config.toml") || !node(str(s, "files")).has(p))
                throw bad("Delete only a previously managed, now unreferenced helper");
          } catch (Exception ex) {
            error = ex.getMessage();
          }
          if (d == null)
            db.exec(
                "INSERT INTO config_drafts VALUES(?,?,?,?,?,?,?)",
                id,
                e,
                version,
                b.path("revision").asLong(),
                json(content),
                error,
                now());
          else
            db.exec(
                "UPDATE config_drafts SET"
                    + " version=?,base_revision=?,bundle=?,validation_error=?,updated=? WHERE"
                    + " sandbox_id=? AND editor_id=?",
                version,
                b.path("revision").asLong(),
                json(content),
                error,
                now(),
                id,
                e);
          if (op.equals("draft") || !error.isEmpty())
            return Map.of(
                "saved", true, "committed", false, "error", error, "draft_version", version);
          if (b.path("revision").asLong() != lng(s, "revision"))
            return Map.of(
                "saved",
                true,
                "committed",
                false,
                "error",
                "Draft is based on an older revision. Review against latest before committing.",
                "draft_version",
                version);
          long rev = lng(s, "revision") + 1;
          var saved = new LinkedHashMap<String, Object>(content);
          saved.put("revision", rev);
          String serialized = json(saved);
          db.exec(
              "INSERT INTO config_versions VALUES(?,?,?,?,?)",
              id,
              rev,
              serialized,
              hash(serialized),
              now());
          db.exec(
              "UPDATE sandboxes SET config=?,files=?,revision=?,sync_error=NULL,updated=? WHERE"
                  + " id=?",
              config,
              json(files),
              rev,
              now(),
              id);
          var result =
              Map.of("saved", true, "committed", true, "revision", rev, "draft_version", version);
          db.exec(
              "INSERT INTO config_requests VALUES(?,?,?,?)", id, req, hash(json(b)), json(result));
          db.exec(
              "UPDATE config_drafts SET base_revision=? WHERE sandbox_id=? AND editor_id=?",
              rev,
              id,
              e);
          return result;
        });
  }

  public Object preserve(String id, JsonNode b) {
    String config = b.path("config").asText();
    if (config.length() > 65536) throw bad("Recovery config too large");
    var files = Config.files(b.path("files"));
    String content = json(Map.of("config", config, "files", files, "delete", List.of()));
    return db.transaction(
        () -> {
          var s = locked(id);
          String e = "recovery_" + hash(content).substring(0, 32);
          if (db.one(
                  "SELECT editor_id FROM config_drafts WHERE sandbox_id=? AND editor_id=?", id, e)
              == null)
            db.exec(
                "INSERT INTO config_drafts VALUES(?,?,?,?,?,?,?)",
                id,
                e,
                0,
                lng(s, "revision"),
                content,
                "Recovered machine copy; review before publishing",
                now());
          return Map.of("saved", true, "editor", e);
        });
  }

  public Object pending(String id, long after) {
    return db.transaction(
        () -> {
          var s = locked(id);
          var versions =
              db.all(
                  "SELECT bundle,content_hash FROM config_versions WHERE sandbox_id=? AND"
                      + " revision>? ORDER BY revision LIMIT 50",
                  id,
                  after);
          return Map.of("latest", lng(s, "revision"), "versions", versions);
        });
  }

  public Object ack(String id, long revision, String digest, String error) {
    return db.transaction(
        () -> {
          locked(id);
          if (!error.isEmpty()) {
            db.exec(
                "UPDATE sandboxes SET sync_error=? WHERE id=?",
                error.substring(0, Math.min(2000, error.length())),
                id);
            return Map.of("ok", true);
          }
          var v =
              db.one(
                  "SELECT content_hash FROM config_versions WHERE sandbox_id=? AND revision=?",
                  id,
                  revision);
          if (v == null || !digest.equals(str(v, "content_hash")))
            throw bad("Unknown config revision/hash");
          db.exec(
              "UPDATE sandboxes SET applied_revision=?,sync_error=NULL WHERE id=? AND"
                  + " applied_revision<=?",
              revision,
              id,
              revision);
          return Map.of("ok", true);
        });
  }
}
