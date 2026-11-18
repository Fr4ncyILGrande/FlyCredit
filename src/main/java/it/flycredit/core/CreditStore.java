package it.flycredit.core;

import java.nio.file.*;
import java.nio.channels.*;
import java.sql.*;
import java.util.*;

/** All calls are confined to one database worker. SQLite commits each money operation atomically. */
public final class CreditStore implements AutoCloseable {
    private Connection db;
    private FileChannel lockFile;
    private FileLock lock;
    public record Account(UUID uuid, String name, long available, long reserved, long used,
                          long sent, long received, Long playtimeTicks, Long playtimeSnapshotAt) {}
    public record Checkpoint(UUID player, UUID session, long remaining, boolean finish) {}
    public record Observation(UUID player, String name, Long ticks) {}
    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }

    public CreditStore(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try {
            lockFile = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockFile.tryLock();
            if (lock == null) throw new SQLException("Another FlyCredit instance already has this database open.");
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            initialize();
        } catch (Exception | LinkageError ex) {
            try { close(); } catch (Exception closeError) { ex.addSuppressed(closeError); }
            throw ex;
        }
    }
    private void initialize() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                if (!r.next() || !r.getString(1).equals("ok")) throw new SQLException("Database integrity check failed");
            }
            int version;
            try (ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); version = r.getInt(1); }
            if (version > 2) throw new SQLException("This database was created by a newer version of FlyCredit. Startup cancelled.");
            s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT, available INTEGER NOT NULL DEFAULT 0 CHECK(available>=0), reserved INTEGER NOT NULL DEFAULT 0 CHECK(reserved>=0), used INTEGER NOT NULL DEFAULT 0 CHECK(used>=0), sent INTEGER NOT NULL DEFAULT 0 CHECK(sent>=0), received INTEGER NOT NULL DEFAULT 0 CHECK(received>=0), ptm_ticks INTEGER, ptm_snapshot_at INTEGER)");
            s.execute("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, actor TEXT, target TEXT, amount INTEGER NOT NULL)");
            if (version < 2) transaction(() -> {
                update("ALTER TABLE accounts ADD COLUMN session_id TEXT");
                update("PRAGMA user_version=2");
                return null;
            });
            s.execute("CREATE INDEX IF NOT EXISTS account_name ON accounts(name COLLATE NOCASE)");
            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM accounts WHERE typeof(available)<>'integer' OR typeof(reserved)<>'integer' OR typeof(used)<>'integer' OR typeof(sent)<>'integer' OR typeof(received)<>'integer' OR available<0 OR reserved<0 OR used<0 OR sent<0 OR received<0 OR reserved>" + Amounts.MAX_MILLIS + " OR available>" + Amounts.MAX_MILLIS + "-reserved")) {
                if (!r.next() || r.getLong(1) != 0) throw new SQLException("Invalid account balances. Restore the database from a verified backup.");
            }
        }
    }
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        db.setAutoCommit(false);
        try {
            T value = work.run();
            db.commit();
            return value;
        } catch (SQLException | RuntimeException ex) {
            try { db.rollback(); }
            catch (SQLException rollbackError) {
                ex.addSuppressed(rollbackError);
                try { db.close(); } catch (SQLException closeError) { ex.addSuppressed(closeError); }
            }
            throw ex;
        } finally { if (!db.isClosed()) db.setAutoCommit(true); }
    }
    private int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement s = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i] instanceof UUID ? args[i].toString() : args[i]);
            return s.executeUpdate();
        }
    }
    private void ensure(UUID id) throws SQLException { update("INSERT OR IGNORE INTO accounts(uuid) VALUES (?)", id); }
    private void audit(String kind, String actor, UUID target, long amount) throws SQLException {
        update("INSERT INTO audit(at,kind,actor,target,amount) VALUES (?,?,?,?,?)", System.currentTimeMillis(), kind, actor, target, amount);
    }
    public Account account(UUID id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT * FROM accounts WHERE uuid=?")) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return new Account(id, null, 0, 0, 0, 0, 0, null, null);
                Number ticks = (Number) r.getObject("ptm_ticks"), at = (Number) r.getObject("ptm_snapshot_at");
                return new Account(id, r.getString("name"), r.getLong("available"), r.getLong("reserved"), r.getLong("used"),
                        r.getLong("sent"), r.getLong("received"), ticks == null ? null : ticks.longValue(), at == null ? null : at.longValue());
            }
        }
    }
    public Optional<UUID> findName(String name) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT uuid FROM accounts WHERE name=? COLLATE NOCASE")) {
            s.setString(1, name);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                UUID id = UUID.fromString(r.getString(1));
                if (r.next()) throw new CreditException("account.ambiguous");
                return Optional.of(id);
            }
        }
    }
    public void observe(UUID id, String name, Long ticks) throws SQLException {
        observeAll(List.of(new Observation(id, name, ticks)));
    }
    public void observeAll(List<Observation> observations) throws SQLException {
        transaction(() -> {
            for (Observation o : observations) {
                ensure(o.player());
                update("UPDATE accounts SET name=? WHERE uuid=?", o.name(), o.player());
                if (o.ticks() != null && o.ticks() >= 0) update("UPDATE accounts SET ptm_ticks=?, ptm_snapshot_at=? WHERE uuid=?", o.ticks(), System.currentTimeMillis(), o.player());
            }
            return null;
        });
    }
    private static void validateTotal(long available, long reserved) {
        if (available < 0 || reserved < 0 || available > Amounts.MAX_MILLIS - reserved)
            throw new CreditException("amount.balanceLimit");
    }
    public Account addCredit(UUID id, long amount, String actor) throws SQLException {
        if (amount <= 0 || amount > Amounts.MAX_MILLIS) throw new CreditException("amount.range");
        return transaction(() -> {
            ensure(id);
            Account a = account(id);
            validateTotal(Math.addExact(a.available(), amount), a.reserved());
            update("UPDATE accounts SET available=available+? WHERE uuid=?", amount, id);
            audit("ADMIN_ADD", actor, id, amount);
            return account(id);
        });
    }
    public void transfer(UUID from, UUID to, long amount) throws SQLException {
        if (from.equals(to)) throw new CreditException("transfer.self");
        if (amount <= 0 || amount > Amounts.MAX_MILLIS) throw new CreditException("amount.invalid");
        transaction(() -> {
            Account a = account(from), b = account(to);
            if (a.available() < amount) throw new CreditException("transfer.insufficient", Amounts.format(a.available()));
            validateTotal(Math.addExact(b.available(), amount), b.reserved());
            long sent = Math.addExact(a.sent(), amount), received = Math.addExact(b.received(), amount);
            ensure(to);
            update("UPDATE accounts SET available=available-?,sent=? WHERE uuid=?", amount, sent, from);
            update("UPDATE accounts SET available=available+?,received=? WHERE uuid=?", amount, received, to);
            audit("TRANSFER", from.toString(), to, amount);
            return null;
        });
    }
    private boolean sessionMatches(UUID id, UUID token) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT session_id FROM accounts WHERE uuid=?")) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() && token.toString().equals(r.getString(1)); }
        }
    }
    public long reserve(UUID id, UUID token, long amount) throws SQLException {
        Objects.requireNonNull(token);
        if (amount <= 0 || amount > Amounts.MAX_MILLIS) throw new CreditException("amount.invalid");
        return transaction(() -> {
            Account a = account(id);
            if (amount > a.available()) throw new CreditException("flight.insufficient", Amounts.format(a.available()));
            if (update("UPDATE accounts SET available=available-?,reserved=?,session_id=? WHERE uuid=? AND reserved=0 AND session_id IS NULL", amount, amount, token, id) != 1)
                throw new CreditException("flight.startPending");
            audit("RESERVE", id.toString(), id, amount);
            return amount;
        });
    }
    public void checkpoint(UUID id, UUID token, long remaining, boolean finish) throws SQLException {
        checkpointAll(List.of(new Checkpoint(id, token, remaining, finish)));
    }
    public void checkpointAll(List<Checkpoint> checkpoints) throws SQLException {
        transaction(() -> {
            for (Checkpoint c : checkpoints) checkpointInTransaction(c);
            return null;
        });
    }
    private void checkpointInTransaction(Checkpoint c) throws SQLException {
            UUID id = c.player();
            if (!sessionMatches(id, c.session())) return;
            long remaining = c.remaining(); boolean finish = c.finish();
            Account a = account(id);
            if (remaining < 0 || remaining > a.reserved()) throw new SQLException("Inconsistent flight checkpoint for " + id);
            long spent = a.reserved() - remaining;
            long used = Math.addExact(a.used(), spent);
            update("UPDATE accounts SET reserved=?,available=?,used=?,session_id=? WHERE uuid=?", finish ? 0 : remaining,
                    finish ? Math.addExact(a.available(), remaining) : a.available(), used, finish ? null : c.session(), id);
            if (finish) audit("SESSION_END_REFUND", id.toString(), id, remaining);
    }
    public void releaseReservation(UUID id, UUID token) throws SQLException {
        transaction(() -> {
            if (sessionMatches(id, token)) checkpointInTransaction(new Checkpoint(id, token, account(id).reserved(), true));
            return null;
        });
    }
    public int recoverSessions() throws SQLException {
        return transaction(() -> update("UPDATE accounts SET available=available+reserved,reserved=0,session_id=NULL WHERE reserved>0 OR session_id IS NOT NULL"));
    }
    @Override public void close() throws Exception {
        try { if (db != null) db.close(); }
        finally {
            try { if (lock != null && lock.isValid()) lock.release(); }
            finally { if (lockFile != null) lockFile.close(); }
        }
    }
}
