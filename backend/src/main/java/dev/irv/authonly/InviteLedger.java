package dev.irv.authonly;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;

/** Durable single-use reservations. Never stores certificates, raw invites, or enrollment links. */
final class InviteLedger implements AutoCloseable {
  private final Connection db;
  InviteLedger(AuthSettings settings) { this(settings, Clock.systemUTC()); }
  InviteLedger(AuthSettings settings, Clock clock) {
    if (!settings.enrollmentEnabled) { db = null; return; }
    try {
      Path directory = Path.of(settings.inviteDb).getParent();
      Files.createDirectories(directory);
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      db = DriverManager.getConnection("jdbc:h2:file:" + settings.inviteDb + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
      try (Statement s = db.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS invite_redemptions (code_hash VARCHAR(64) PRIMARY KEY, claimed_at BIGINT NOT NULL, status VARCHAR(20) NOT NULL, subject VARCHAR(200))");
        s.execute("CREATE TABLE IF NOT EXISTS invite_activation (code_hash VARCHAR(64) PRIMARY KEY, expires_at BIGINT NOT NULL, lifetime_hours INT NOT NULL DEFAULT 2)");
      }
      // Upgrade the previous 24-hour format once, preserving original activation time.
      try (Statement s = db.createStatement()) {
        s.execute("ALTER TABLE invite_activation ADD COLUMN IF NOT EXISTS lifetime_hours INT NOT NULL DEFAULT 24");
        s.execute("UPDATE invite_activation SET expires_at=expires_at-79200000, lifetime_hours=2 WHERE lifetime_hours=24");
      }
      if (!settings.singleInviteHash.isEmpty()) {
        // Insert only once. Re-adding a code or restarting never renews its lifetime.
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO invite_activation(code_hash,expires_at,lifetime_hours) VALUES(?,?,2)")) {
          insert.setString(1, settings.singleInviteHash);
          insert.setLong(2, clock.instant().plus(Duration.ofHours(2)).toEpochMilli());
          insert.executeUpdate();
        } catch (SQLException ex) {
          if (!"23505".equals(ex.getSQLState())) throw ex;
        }
        try (PreparedStatement select = db.prepareStatement("SELECT expires_at FROM invite_activation WHERE code_hash=?")) {
          select.setString(1, settings.singleInviteHash);
          try (ResultSet row = select.executeQuery()) {
            if (!row.next()) throw new SQLException();
            settings.singleInviteExpiry = Instant.ofEpochMilli(row.getLong(1));
          }
        }
      }
    } catch (Exception ex) { throw new IllegalStateException("Cannot open persistent invite ledger"); }
  }
  synchronized boolean reserve(String hash, Instant now) {
    if (db == null) return false;
    try (PreparedStatement s = db.prepareStatement("INSERT INTO invite_redemptions(code_hash,claimed_at,status) VALUES(?,?,'RESERVED')")) {
      s.setString(1, hash); s.setLong(2, now.toEpochMilli()); s.executeUpdate(); return true;
    } catch (SQLException ex) {
      if ("23505".equals(ex.getSQLState())) return false;
      throw new IllegalStateException("Invite ledger unavailable");
    }
  }
  synchronized void record(String hash, String status, String subject) {
    try (PreparedStatement s = db.prepareStatement("UPDATE invite_redemptions SET status=?,subject=? WHERE code_hash=?")) {
      s.setString(1, status); s.setString(2, subject); s.setString(3, hash);
      if (s.executeUpdate() != 1) throw new SQLException();
    } catch (SQLException ex) { throw new IllegalStateException("Invite ledger unavailable"); }
  }
  @Override public synchronized void close() throws SQLException { if (db != null) db.close(); }
}
