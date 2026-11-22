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
    @Test void awardsMustBePositiveAndRespectReservedCredit() throws Exception {
        store.addCredit(a, Amounts.MAX_MILLIS, "test");
        store.reserve(a, token, 60_000);
        assertThrows(CreditException.class, () -> store.addCredit(a, 1, "test"));
        assertThrows(CreditException.class, () -> store.addCredit(a, 0, "test"));
        assertThrows(CreditException.class, () -> store.addCredit(a, -60_000, "test"));
        assertEquals(Amounts.MAX_MILLIS - 60_000, store.account(a).available());
        assertEquals(60_000, store.account(a).reserved());
    }
    @Test void transferIsDurableAndConservesTotal() throws Exception {
        store.addCredit(a, 180_000, "test");
        store.transfer(a, b, 60_000);
        store.close(); store = new CreditStore(temp.resolve("test.db"));
        assertEquals(120_000, store.account(a).available());
        assertEquals(60_000, store.account(b).available());
        assertEquals(60_000, store.account(a).sent());
        assertEquals(60_000, store.account(b).received());
    }
    @Test void insufficientTransferDoesNotCreateRecipientOrChangeBalances() throws Exception {
        store.addCredit(a, 20, "test");
        assertThrows(CreditException.class, () -> store.transfer(a, b, 30));
        assertEquals(20, store.account(a).available());
        assertEquals(0, store.account(b).available());
        assertEquals(0, store.account(a).sent());
    }
    @Test void creditCannotBeSpentTwice() throws Exception {
        store.addCredit(a, 60_000, "test");
        store.transfer(a, b, 60_000);
        assertThrows(CreditException.class, () -> store.transfer(a, b, 60_000));
        assertEquals(60_000, store.account(b).available());
    }
    @Test void invalidTransferAndOverflowRollback() throws Exception {
        store.addCredit(a, 100, "test");
        store.addCredit(b, Amounts.MAX_MILLIS, "test");
        assertThrows(CreditException.class, () -> store.transfer(a, b, 1));
        assertThrows(CreditException.class, () -> store.transfer(a, a, 1));
        assertThrows(CreditException.class, () -> store.transfer(a, b, -1));
        assertThrows(CreditException.class, () -> store.transfer(a, b, 0));
        assertEquals(100, store.account(a).available());
    }
    @Test void reservationCannotBeTransferredAndUnusedTimeIsReturned() throws Exception {
        store.addCredit(a, 120_000, "test");
        assertEquals(60_000, store.reserve(a, token, 60_000));
        assertThrows(CreditException.class, () -> store.transfer(a, b, 60_001));
        store.checkpoint(a, token, 50_000, false);
        store.checkpoint(a, token, 45_000, true);
        assertEquals(105_000, store.account(a).available());
        assertEquals(15_000, store.account(a).used());
        assertEquals(0, store.account(a).reserved());
    }
    @Test void statsNeverDoubleCountASavedCheckpointOrApplyAClosedSessionsSampleToANewFlight() throws Exception {
        store.addCredit(a, 120_000, "test");
        store.reserve(a, token, 60_000);
        var sample = new CreditStore.Checkpoint(a, token, 48_000, false);
        store.checkpoint(a, token, 50_000, false);
        assertEquals(12_000, store.flightStats(a, sample).used());
        store.checkpoint(a, token, 48_000, false);
        assertEquals(12_000, store.flightStats(a, sample).used());
        store.checkpoint(a, token, 46_000, true);
        assertEquals(14_000, store.flightStats(a, sample).used());
        store.reserve(a, UUID.randomUUID(), 90_000);
        var stats = store.flightStats(a, sample);
        assertEquals(14_000, stats.used());
        assertEquals(16_000, stats.available());
    }
    @Test void zeroCannotReserveTheWholeBalance() throws Exception {
        store.addCredit(a, 180_000, "test");
        assertThrows(CreditException.class, () -> store.reserve(a, token, 0));
        assertEquals(180_000, store.account(a).available());
        assertEquals(0, store.account(a).reserved());
    }
    @Test void crashRecoveryRefundsOnlyPersistedRemainingOnce() throws Exception {
        store.addCredit(a, 60_000, "test"); store.reserve(a, token, 60_000);
        store.checkpoint(a, token, 50_000, false);
        store.close(); store = new CreditStore(temp.resolve("test.db"));
        assertEquals(1, store.recoverSessions());
        assertEquals(0, store.recoverSessions());
        assertEquals(50_000, store.account(a).available());
        assertEquals(10_000, store.account(a).used());
    }
    @Test void staleOrIncreasingCheckpointIsRejected() throws Exception {
        store.addCredit(a, 100, "test"); store.reserve(a, token, 100);
        store.checkpoint(a, token, 40, false);
        assertThrows(SQLException.class, () -> store.checkpoint(a, token, 60, false));
        assertEquals(60, store.account(a).used());
    }
    @Test void failedSecondTransferUpdateRollsBackFirstUpdate() throws Exception {
        store.addCredit(a, 120_000, "test");
        store.observe(b, "Recipient", null);
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
        store.addCredit(a, 60_000, "test");
        store.reserve(a, token, 60_000);
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
        store.addCredit(a, 100, "test"); store.addCredit(b, 100, "test");
        store.reserve(a, token, 100); store.reserve(b, token, 100);
        assertThrows(SQLException.class, () -> store.checkpointAll(List.of(
                new CreditStore.Checkpoint(a, token, 50, false),
                new CreditStore.Checkpoint(b, token, 101, false))));
        assertEquals(100, store.account(a).reserved());
        assertEquals(0, store.account(a).used());
    }
    @Test void exhaustedButNotClosedSessionStillOwnsItsReservation() throws Exception {
        store.addCredit(a, 100, "test"); store.reserve(a, token, 50);
        store.checkpoint(a, token, 0, false);
        assertThrows(CreditException.class, () -> store.reserve(a, UUID.randomUUID(), 50));
        store.checkpoint(a, token, 0, true);
        assertEquals(50, store.reserve(a, UUID.randomUUID(), 50));
    }
    @Test void oneDatabaseCannotBeOpenedByTwoPluginInstances() throws Exception {
        assertThrows(Exception.class, () -> new CreditStore(temp.resolve("test.db")));
        store.addCredit(a, 100, "test");
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
