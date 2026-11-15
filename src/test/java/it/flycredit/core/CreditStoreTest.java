package it.flycredit.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CreditStoreTest {
    @TempDir Path temp;
    CreditStore store;
    final UUID a = UUID.fromString("11111111-1111-4111-8111-111111111111");
    final UUID b = UUID.fromString("22222222-2222-4222-8222-222222222222");
    final UUID token = UUID.randomUUID();
    @BeforeEach void open() throws Exception { store = new CreditStore(temp.resolve("test.db")); }
    @AfterEach void close() throws Exception { if (store != null) store.close(); }
    @Test void transferIsDurableAndConservesTotal() throws Exception {
        store.change(a, 180_000, false, "test");
        store.transfer(a, b, 60_000);
        store.close(); store = new CreditStore(temp.resolve("test.db"));
        assertEquals(120_000, store.account(a).available());
        assertEquals(60_000, store.account(b).available());
        assertEquals(60_000, store.account(a).sent());
        assertEquals(60_000, store.account(b).received());
    }
    @Test void insufficientTransferDoesNotCreateRecipientOrChangeBalances() throws Exception {
        store.change(a, 20, false, "test");
        assertThrows(CreditException.class, () -> store.transfer(a, b, 30));
        assertEquals(20, store.account(a).available());
        assertEquals(0, store.account(b).available());
        assertEquals(0, store.account(a).sent());
    }
    @Test void creditCannotBeSpentTwice() throws Exception {
        store.change(a, 60_000, false, "test");
        store.transfer(a, b, 60_000);
        assertThrows(CreditException.class, () -> store.transfer(a, b, 60_000));
        assertEquals(60_000, store.account(b).available());
    }
    @Test void invalidTransferAndOverflowRollback() throws Exception {
        store.change(a, 100, false, "test");
        store.change(b, Amounts.MAX_MILLIS, false, "test");
        assertThrows(CreditException.class, () -> store.transfer(a, b, 1));
        assertThrows(CreditException.class, () -> store.transfer(a, a, 1));
        assertThrows(CreditException.class, () -> store.transfer(a, b, -1));
        assertThrows(CreditException.class, () -> store.transfer(a, b, 0));
        assertEquals(100, store.account(a).available());
    }
    @Test void reservationCannotBeTransferredAndUnusedTimeIsReturned() throws Exception {
        store.change(a, 120_000, false, "test");
        assertEquals(60_000, store.reserve(a, token, 60_000));
        assertThrows(CreditException.class, () -> store.transfer(a, b, 60_001));
        store.checkpoint(a, token, 50_000, false);
        store.checkpoint(a, token, 45_000, true);
        assertEquals(105_000, store.account(a).available());
        assertEquals(15_000, store.account(a).used());
        assertEquals(0, store.account(a).reserved());
    }
    @Test void crashRecoveryRefundsOnlyPersistedRemainingOnce() throws Exception {
        store.change(a, 60_000, false, "test"); store.reserve(a, token, 0);
        store.checkpoint(a, token, 50_000, false);
        store.close(); store = new CreditStore(temp.resolve("test.db"));
        assertEquals(1, store.recoverSessions());
        assertEquals(0, store.recoverSessions());
        assertEquals(50_000, store.account(a).available());
        assertEquals(10_000, store.account(a).used());
    }
    @Test void staleOrIncreasingCheckpointIsRejected() throws Exception {
        store.change(a, 100, false, "test"); store.reserve(a, token, 0);
        store.checkpoint(a, token, 40, false);
        assertThrows(SQLException.class, () -> store.checkpoint(a, token, 60, false));
        assertEquals(60, store.account(a).used());
    }
    @Test void seedOnlyRunsOnceAndNeverOverwrites() throws Exception {
        LegacyImport data = new LegacyImport("one", Map.of(a, 120_000L, b, 60_000L), 0);
        assertEquals(2, store.importLegacy(data, true).imported());
        store.change(a, 0, true, "test");
        assertTrue(store.importLegacy(data, true).alreadyImported());
        assertEquals(0, store.account(a).available());
    }
    @Test void seedRefusesExistingDatabaseButManualMergeAddsAbsentOnly() throws Exception {
        store.change(a, 123, false, "test");
        LegacyImport data = new LegacyImport("one", Map.of(a, 120_000L, b, 60_000L), 0);
        assertEquals(0, store.importLegacy(data, true).imported());
        assertEquals(123, store.account(a).available());
        assertEquals(1, store.importLegacy(data, false).imported());
        assertEquals(123, store.account(a).available());
        assertEquals(60_000, store.account(b).available());
    }
    @Test void failedSecondTransferUpdateRollsBackFirstUpdate() throws Exception {
        store.change(a, 120_000, false, "test"); store.change(b, 0, false, "test");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("test.db")); Statement s = c.createStatement()) {
            s.execute("CREATE TRIGGER fail_recipient BEFORE UPDATE ON accounts WHEN NEW.uuid='" + b + "' BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");
        }
        assertThrows(SQLException.class, () -> store.transfer(a, b, 60_000));
        assertEquals(120_000, store.account(a).available());
        assertEquals(0, store.account(a).sent());
        assertEquals(0, store.account(b).available());
    }
    @Test void ptmSnapshotPreservesTicksAndNullableMissingData() throws Exception {
        assertNull(store.account(a).playtimeTicks());
        store.observe(a, "TestPlayer", 72_000L);
        store.observe(a, "NewName", null);
        assertEquals(72_000L, store.account(a).playtimeTicks());
        assertEquals(a, store.findName("newname").orElseThrow());
        assertTrue(store.findName("testplayer").isEmpty());
    }
    @Test void duplicateAndStaleSettlementsCannotRefundANewerSession() throws Exception {
        store.change(a, 60_000, false, "test");
        store.reserve(a, token, 0);
        store.checkpoint(a, token, 50_000, true);
        UUID newer = UUID.randomUUID();
        store.reserve(a, newer, 30_000);
        store.checkpoint(a, token, 50_000, true);
        store.releaseReservation(a, token);
        assertEquals(20_000, store.account(a).available());
        assertEquals(30_000, store.account(a).reserved());
        assertEquals(10_000, store.account(a).used());
        store.releaseReservation(a, newer);
        store.releaseReservation(a, newer);
        assertEquals(50_000, store.account(a).available());
    }
    @Test void batchFailureRollsBackEveryPlayer() throws Exception {
        store.change(a, 100, false, "test"); store.change(b, 100, false, "test");
        store.reserve(a, token, 0); store.reserve(b, token, 0);
        assertThrows(SQLException.class, () -> store.checkpointAll(List.of(
                new CreditStore.Checkpoint(a, token, 50, false),
                new CreditStore.Checkpoint(b, token, 101, false))));
        assertEquals(100, store.account(a).reserved());
        assertEquals(0, store.account(a).used());
    }
    @Test void exhaustedButNotClosedSessionStillOwnsItsReservation() throws Exception {
        store.change(a, 100, false, "test"); store.reserve(a, token, 50);
        store.checkpoint(a, token, 0, false);
        assertThrows(CreditException.class, () -> store.reserve(a, UUID.randomUUID(), 50));
        store.checkpoint(a, token, 0, true);
        assertEquals(50, store.reserve(a, UUID.randomUUID(), 50));
    }
    @Test void oneDatabaseCannotBeOpenedByTwoPluginInstances() throws Exception {
        assertThrows(Exception.class, () -> new CreditStore(temp.resolve("test.db")));
        store.change(a, 100, false, "test");
        assertEquals(100, store.account(a).available());
    }
    @Test void schemaOneUpgradePreservesMoneyAndRecoversReservation() throws Exception {
        store.close(); store = null;
        Path old = temp.resolve("v1.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE accounts(uuid TEXT PRIMARY KEY,name TEXT,available INTEGER NOT NULL DEFAULT 0,reserved INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,sent INTEGER NOT NULL DEFAULT 0,received INTEGER NOT NULL DEFAULT 0,ptm_ticks INTEGER,ptm_snapshot_at INTEGER)");
            s.execute("INSERT INTO accounts(uuid,available,reserved,used,ptm_ticks,ptm_snapshot_at) VALUES ('" + a + "',1000,500,200,72000,1234)");
            s.execute("PRAGMA user_version=1");
        }
        store = new CreditStore(old);
        assertEquals(1, store.recoverSessions());
        assertEquals(1500, store.account(a).available());
        assertEquals(200, store.account(a).used());
        assertEquals(72_000L, store.account(a).playtimeTicks());
        assertEquals(0, store.recoverSessions());
        assertEquals(100, store.reserve(a, token, 100));
    }
    @Test void failedOpenReleasesTheFileLock() throws Exception {
        Path bad = temp.resolve("future.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bad); Statement s = c.createStatement()) { s.execute("PRAGMA user_version=99"); }
        assertThrows(SQLException.class, () -> new CreditStore(bad));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bad); Statement s = c.createStatement()) { s.execute("PRAGMA user_version=0"); }
        try (CreditStore reopened = new CreditStore(bad)) { assertEquals(0, reopened.account(a).available()); }
    }
}
