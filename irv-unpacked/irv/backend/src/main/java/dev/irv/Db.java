package dev.irv;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.support.*;

@Component
public class Db {
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;

  public Db(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  public int exec(String s, Object... args) {
    return jdbc.update(s, args);
  }

  public List<Map<String, Object>> all(String s, Object... a) {
    return jdbc.queryForList(s, a);
  }

  public Map<String, Object> one(String s, Object... a) {
    var l = all(s, a);
    return l.isEmpty() ? null : l.get(0);
  }

  public <T> T transaction(Supplier<T> f) {
    return tx.execute(st -> f.get());
  }

  public void log(String sid, String source, String message) {
    var s = one("SELECT session_id FROM sandboxes WHERE id=?", sid);
    if (s != null)
      exec(
          "INSERT INTO logs(sandbox_id,session_id,source,message,created) VALUES(?,?,?,?,?)",
          sid,
          s.get("session_id"),
          source,
          message,
          Util.now());
  }
}
