package it.flycredit;

import it.flycredit.core.CreditException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseWorkerTest {
    @TempDir Path temp;
    @Test void cancellingThePublicFutureKeepsItsSlotUntilTheOperationFinishes() throws Exception {
        DatabaseWorker worker = new DatabaseWorker(1, 8);
        worker.open(temp.resolve("cancel.db"));
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try {
            var submitted = worker.submit(s -> { entered.countDown(); finish.await(); return null; });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(submitted.cancel(false));
            assertThrows(CompletionException.class, () -> worker.submit(s -> 1).join());
            finish.countDown();
            worker.critical(s -> null).get(3, TimeUnit.SECONDS);
            assertEquals(1, worker.submit(s -> 1).get(3, TimeUnit.SECONDS));
        } finally { finish.countDown(); worker.close(); }
    }
    @Test void overloadRejectsCommandsButStillSettlesReservedCredit() throws Exception {
        DatabaseWorker worker = new DatabaseWorker(1, 8);
        worker.open(temp.resolve("test.db"));
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        worker.submit(s -> { s.change(id, 1000, false, "test"); s.reserve(id, session, 1000); return null; }).get(3, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        try {
            var first = worker.submit(s -> { entered.countDown(); if (!unblock.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout"); return null; });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var rejected = worker.submit(s -> s.account(id));
            assertInstanceOf(CreditException.class, assertThrows(CompletionException.class, rejected::join).getCause());
            var settlement = worker.critical(s -> { s.checkpoint(id, session, 900, true); return null; });
            unblock.countDown(); first.get(3, TimeUnit.SECONDS); settlement.get(3, TimeUnit.SECONDS);
            assertEquals(900, worker.submit(s -> s.account(id)).get(3, TimeUnit.SECONDS).available());
        } finally { unblock.countDown(); worker.close(); }
    }
    @Test void shutdownDrainsAcceptedWritesAndRejectsNewOnes() throws Exception {
        DatabaseWorker worker = new DatabaseWorker();
        Path file = temp.resolve("test.db"); worker.open(file);
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 50; i++) worker.critical(s -> s.change(id, 1, false, "test"));
        worker.close();
        try (var store = new it.flycredit.core.CreditStore(file)) { assertEquals(50, store.account(id).available()); }
        assertThrows(CompletionException.class, () -> worker.submit(s -> s.account(id)).join());
    }
}
