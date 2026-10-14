package dev.irv;

import static dev.irv.Util.*;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthApi {
  final Auth auth;
  final Db db;
  final Crypto crypto;

  public AuthApi(Auth a, Db d, Crypto c) {
    auth = a;
    db = d;
    crypto = c;
  }

  @PostMapping("/auth/register")
  public Object register(
      @RequestBody Map<String, String> b, HttpServletRequest req, HttpServletResponse res) {
    auth.rate("register:" + req.getRemoteAddr(), 10);
    String user = b.getOrDefault("username", "").toLowerCase(Locale.ROOT);
    if (!user.matches("[a-z0-9_]{3,32}"))
      throw bad("Username: 3–32 lowercase letters, digits, underscores");
    String pass = b.getOrDefault("password", "");
    auth.password(pass);
    String uid =
        db.transaction(
            () -> {
              var i =
                  db.one(
                      "SELECT * FROM invites WHERE code_hash=? FOR UPDATE",
                      hash(b.getOrDefault("invite", "")));
              if (i == null || lng(i, "expires") < now() || lng(i, "remaining") < 1)
                throw bad("Invalid or expired invite");
              String id = id();
              db.exec(
                  "INSERT INTO users(id,username,password_hash,role,created) VALUES(?,?,?,?,?)",
                  id,
                  user,
                  auth.passwords.encode(pass),
                  i.get("role"),
                  now());
              db.exec(
                  "UPDATE invites SET remaining=remaining-1 WHERE code_hash=?", i.get("code_hash"));
              return id;
            });
    auth.issue(uid, false, res);
    return Map.of("enroll", true);
  }

  @PostMapping("/auth/login")
  public Object login(
      @RequestBody Map<String, String> b, HttpServletRequest req, HttpServletResponse res) {
    String user = b.getOrDefault("username", "").toLowerCase(Locale.ROOT);
    auth.rate("login-ip:" + req.getRemoteAddr(), 30);
    auth.rate("login-user:" + hash(user), 10);
    var u = db.one("SELECT * FROM users WHERE username=?", user);
    String password = b.getOrDefault("password", "");
    if (password.length() > 72
        || u == null
        || !auth.passwords.matches(password, str(u, "password_hash"))) throw deny();
    auth.issue(str(u, "id"), false, res);
    return Map.of("enroll", u.get("factor") == null, "factor", str(u, "factor"));
  }

  @GetMapping("/auth/me")
  public Object me(HttpServletRequest req) {
    var s = auth.session(req, false);
    return Map.of(
        "username",
        s.get("username"),
        "role",
        s.get("role"),
        "verified",
        s.get("verified"),
        "enroll",
        s.get("factor") == null,
        "factor",
        str(s, "factor"),
        "smsAvailable",
        !env("TWILIO_VERIFY_SERVICE", "").isEmpty());
  }

  @PostMapping("/auth/enroll")
  public Object enroll(@RequestBody Map<String, String> b, HttpServletRequest req) {
    var s = auth.session(req, false);
    String uid = str(s, "user_id");
    return db.transaction(
        () -> {
          var u = db.one("SELECT * FROM users WHERE id=? FOR UPDATE", uid);
          if (u.get("factor") != null) throw conflict("Already enrolled");
          String kind = b.getOrDefault("factor", "totp");
          if (kind.equals("totp")) {
            String secret = Crypto.secret();
            db.exec("UPDATE users SET secret=?,phone=NULL WHERE id=?", crypto.seal(secret), uid);
            return Map.of(
                "secret",
                secret,
                "uri",
                "otpauth://totp/irv:"
                    + s.get("username")
                    + "?secret="
                    + secret
                    + "&issuer=irv&algorithm=SHA1&digits=6&period=30");
          }
          if (!kind.equals("sms")) throw bad("Unknown factor");
          String phone = b.getOrDefault("phone", "");
          if (!phone.matches("\\+[1-9][0-9]{7,14}"))
            throw bad("Use an international phone number, e.g. +15551234567");
          auth.rate("sms:" + uid, 5);
          auth.sms(phone, null);
          db.exec("UPDATE users SET phone=?,secret=NULL WHERE id=?", crypto.seal(phone), uid);
          return Map.of("sent", true);
        });
  }

  @PostMapping("/auth/sms")
  public Object sms(HttpServletRequest req) {
    var s = auth.session(req, false);
    String uid = str(s, "user_id");
    auth.rate("sms:" + uid, 5);
    var u = db.one("SELECT * FROM users WHERE id=?", uid);
    if (u.get("phone") == null) throw bad("No phone enrolled");
    auth.sms(crypto.open(str(u, "phone")), null);
    return Map.of("sent", true);
  }

  @PostMapping("/auth/verify")
  public Object verify(
      @RequestBody Map<String, String> b, HttpServletRequest req, HttpServletResponse res) {
    var s = auth.session(req, false);
    String uid = str(s, "user_id"), code = b.getOrDefault("code", "").trim();
    auth.rate("mfa:" + uid, 10);
    return db.transaction(
        () -> {
          var u = db.one("SELECT * FROM users WHERE id=? FOR UPDATE", uid);
          boolean initial = u.get("factor") == null;
          boolean valid = false;
          String factor = str(u, "factor");
          if (!initial
              && db.exec(
                      "DELETE FROM recovery_codes WHERE user_id=? AND code_hash=?", uid, hash(code))
                  == 1) valid = true;
          if (!valid && u.get("secret") != null) {
            long step =
                Crypto.verify(crypto.open(str(u, "secret")), code, lng(u, "last_otp"), now());
            if (step >= 0) {
              valid = true;
              factor = "totp";
              db.exec("UPDATE users SET last_otp=? WHERE id=?", step, uid);
            }
          }
          if (!valid && u.get("phone") != null) {
            valid = auth.sms(crypto.open(str(u, "phone")), code);
            factor = "sms";
          }
          if (!valid) throw deny();
          List<String> recovery = new ArrayList<>();
          if (initial) {
            db.exec("UPDATE users SET factor=? WHERE id=?", factor, uid);
            for (int i = 0; i < 10; i++) {
              String c = token().substring(0, 20);
              recovery.add(c);
              db.exec("INSERT INTO recovery_codes VALUES(?,?)", uid, hash(c));
            }
          }
          db.exec("DELETE FROM sessions WHERE token_hash=?", s.get("token_hash"));
          auth.issue(uid, true, res);
          return Map.of("verified", true, "recoveryCodes", recovery);
        });
  }

  @PostMapping("/auth/logout")
  public Object logout(HttpServletRequest req, HttpServletResponse res) {
    var s = auth.session(req, false);
    db.exec("DELETE FROM sessions WHERE token_hash=?", s.get("token_hash"));
    auth.cookie(res, "", 0);
    return Map.of("ok", true);
  }

  @PostMapping("/admin/invites")
  public Object invite(@RequestBody Map<String, Integer> b, HttpServletRequest req) {
    auth.admin(auth.session(req, true));
    int hours = Math.min(168, Math.max(1, b.getOrDefault("hours", 24)));
    String code = token();
    db.exec(
        "INSERT INTO invites VALUES(?,?,?,?,?)",
        hash(code),
        now() + hours * 3600000L,
        1,
        "user",
        now());
    return Map.of("code", code, "expires", now() + hours * 3600000L);
  }

  @GetMapping("/admin/invites")
  public Object invites(HttpServletRequest r) {
    auth.admin(auth.session(r, true));
    return db.all("SELECT code_hash,expires,remaining,created FROM invites ORDER BY created DESC");
  }

  @DeleteMapping("/admin/invites/{hash}")
  public Object revoke(@PathVariable String hash, HttpServletRequest r) {
    auth.admin(auth.session(r, true));
    db.exec("UPDATE invites SET remaining=0 WHERE code_hash=?", hash);
    return Map.of("ok", true);
  }
}
