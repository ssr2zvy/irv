package dev.irv;

import static dev.irv.Util.*;

import java.nio.file.*;
import java.util.*;
import org.tomlj.*;

public final class Config {
  public static final String DEFAULT =
      """
      schema_version = 1
      folder_name = "workspace"
      entrypoint = "scripts/environment.sh"
      preview_ports = [8080, 5173]
      [keywords.work]
      root = "workspace"
      path = "."
      """;

  public static TomlParseResult parse(String text) {
    if (text.length() > 65536) throw bad("Configuration too large");
    var t = Toml.parse(text);
    if (t.hasErrors()) throw bad(t.errors().toString());
    Set<String> allowed =
        Set.of(
            "schema_version",
            "folder_name",
            "entrypoint",
            "preview_ports",
            "keywords",
            "processes");
    for (String k : t.keySet())
      if (!allowed.contains(k)) throw bad("Unsupported configuration key: " + k);
    if (t.getLong("schema_version") == null || t.getLong("schema_version") != 1)
      throw bad("schema_version must be 1");
    String folder = t.getString("folder_name");
    if (folder == null || !folder.matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}"))
      throw bad("folder_name must be a single simple directory name");
    relative(t.getString("entrypoint"));
    var kw = t.getTable("keywords");
    if (kw != null)
      for (String name : kw.keySet()) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}")
            || Set.of(
                    "cd", "exit", "exec", "source", "eval", "bash", "sh", "sudo", "export", "unset",
                    "command", "builtin")
                .contains(name)) throw bad("Invalid or reserved keyword");
        var k = kw.getTable(name);
        if (k == null) throw bad("Keyword must be a table");
        String root = k.getString("root"), path = k.getString("path");
        if ("workspace".equals(root)) relative(path);
        else if (!"image".equals(root)
            || path == null
            || !path.startsWith("/")
            || path.contains("..")
            || path.contains("\n")) throw bad("Invalid keyword path");
      }
    var ports = t.getArray("preview_ports");
    if (ports != null) {
      if (ports.size() > 10) throw bad("At most ten preview ports");
      for (int i = 0; i < ports.size(); i++) {
        long p = ports.getLong(i);
        if (p < 1024 || p > 65535) throw bad("Preview ports: 1024–65535");
      }
    }
    var ps = t.getTable("processes");
    if (ps != null) {
      if (ps.size() > 10) throw bad("At most ten managed processes");
      for (String name : ps.keySet()) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}")) throw bad("Invalid process name");
        var p = ps.getTable(name);
        if (p.getString("command") == null) throw bad("Process needs command");
        relative(p.getString("cwd", () -> "."));
        if (p.contains("log_file")) relative(p.getString("log_file"));
      }
    }
    return t;
  }

  public static void relative(String p) {
    if (p == null
        || p.length() > 256
        || p.contains("\\")
        || p.contains("\0")
        || p.contains("\n")
        || Path.of(p).isAbsolute()
        || Arrays.asList(p.split("/")).contains(".."))
      throw bad("Path must stay inside designated workspace");
  }

  public static Map<String, String> files(com.fasterxml.jackson.databind.JsonNode n) {
    Map<String, String> m = new TreeMap<>();
    if (!n.isObject() || n.size() > 32) throw bad("Provide up to 32 startup files");
    n.fields()
        .forEachRemaining(
            e -> {
              relative(e.getKey());
              if (e.getKey().equals("config.toml")
                  || !e.getValue().isTextual()
                  || e.getValue().asText().length() > 65536) throw bad("Invalid startup file");
              m.put(e.getKey(), e.getValue().asText());
            });
    return m;
  }

  public static boolean port(String conf, int port) {
    var a = parse(conf).getArray("preview_ports");
    if (a != null) for (int i = 0; i < a.size(); i++) if (a.getLong(i) == port) return true;
    return false;
  }
}
