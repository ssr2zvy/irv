package dev.irv.authonly;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;

/** Durable single-use reservations. Never stores certificates, raw invites, or enrollment links. */
final class InviteLedger implements AutoCloseable {
  private final Connection db;
  InviteLedger(AuthSettings settings) {
    if (!settings.enrollmentEnabled) { db = null; return; }
    try {
      Path directory = Path.of(settings.inviteDb).getParent();
      Files.createDirectories(directory);
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      db = DriverManager.getConnection("jdbc:h2:file:" + settings.inviteDb + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
      try (Statement s = db.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS invite_redemptions (code_hash VARCHAR(64) PRIMARY KEY, claimed_at BIGINT NOT NULL, status VARCHAR(20) NOT NULL, subject VARCHAR(200))");
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
