package dev.irv;


import static dev.irv.Util.*;

import jakarta.servlet.http.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.*;

@DeferredFeature
@Component
public class Auth implements ApplicationRunner {
  final Db db;
  final Crypto crypto;
  final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  public Auth(Db d, Crypto c) {
    db = d;
    crypto = c;
  }

  public void run(ApplicationArguments a) {
    String code = env("IRV_BOOTSTRAP_INVITE", "");
    if (!code.isEmpty()
        && db.one("SELECT id FROM users WHERE role='admin'") == null
        && db.one("SELECT code_hash FROM invites WHERE role='admin'") == null)
      db.exec(
          "INSERT INTO invites VALUES(?,?,?,?,?)", hash(code), now() + 86400000, 1, "admin", now());
  }

  public synchronized void rate(String key, int max) {
    var row = db.one("SELECT * FROM attempts WHERE bucket=?", key);
    if (row == null) {
      db.exec("INSERT INTO attempts VALUES(?,?,?)", key, now(), 1);
      return;
    }
    if (lng(row, "started") < now() - 900000) {
      db.exec("UPDATE attempts SET count=1,started=? WHERE bucket=?", now(), key);
      return;
    }
    if (lng(row, "count") >= max)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "Too many attempts; retry in 15 minutes");
    db.exec("UPDATE attempts SET count=count+1 WHERE bucket=?", key);
  }

  public Map<String, Object> session(HttpServletRequest r, boolean full) {
    String token = null;
    if (r.getCookies() != null)
      for (var c : r.getCookies()) if (c.getName().equals("irv_session")) token = c.getValue();
    if (token == null) throw deny();
    return sessionToken(token, full);
  }

  public Map<String, Object> sessionToken(String token, boolean full) {
    var s =
        db.one(
            "SELECT s.*,u.username,u.role,u.factor FROM sessions s JOIN users u ON s.user_id=u.id"
                + " WHERE s.token_hash=? AND s.expires>?",
            hash(token),
            now());
    if (s == null || (full && !Boolean.TRUE.equals(s.get("verified")))) throw deny();
    return s;
  }

  public void cookie(HttpServletResponse r, String value, int seconds) {
    r.addHeader(
        "Set-Cookie",
        "irv_session="
            + value
            + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
            + seconds
            + (env("IRV_SECURE_COOKIES", "true").equals("true") ? "; Secure" : ""));
  }

  public String issue(String uid, boolean full, HttpServletResponse r) {
    String t = token();
    db.exec(
        "INSERT INTO sessions VALUES(?,?,?,?)",
        hash(t),
        uid,
        full,
        now() + (full ? 43200000 : 600000));
    cookie(r, t, full ? 43200 : 600);
    return t;
  }

  public void admin(Map<String, Object> s) {
    if (!"admin".equals(s.get("role")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "Administrator required");
  }

  public void password(String p) {
    int bytes = p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (p.length() < 12 || bytes > 72)
      throw bad("Password must be at least 12 characters and at most 72 UTF-8 bytes");
  }

  public boolean sms(String phone, String code) {
    String service = env("TWILIO_VERIFY_SERVICE", "");
    if (service.isEmpty()) throw bad("SMS is not configured; use an authenticator app");
    try {
      String body =
          "To="
              + URLEncoder.encode(phone, java.nio.charset.StandardCharsets.UTF_8)
              + (code == null
                  ? "&Channel=sms"
                  : "&Code=" + URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8));
      var req =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://verify.twilio.com/v2/Services/"
                          + service
                          + (code == null ? "/Verifications" : "/VerificationCheck")))
              .timeout(Duration.ofSeconds(15))
              .header(
                  "Authorization",
                  "Basic "
                      + Base64.getEncoder()
                          .encodeToString(
                              (env("TWILIO_ACCOUNT_SID", "") + ":" + env("TWILIO_AUTH_TOKEN", ""))
                                  .getBytes()))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() / 100 != 2) throw bad("SMS provider rejected the request");
      return code == null || "approved".equals(node(res.body()).path("status").asText());
    } catch (java.io.IOException | InterruptedException e) {
      throw bad("SMS service unavailable");
    }
  }
}
