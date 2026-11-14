package it.flycredit;

import it.flycredit.core.CreditStore;
import it.flycredit.core.CreditException;
import java.nio.file.Path;
import java.util.concurrent.*;

public final class DatabaseWorker {
    @FunctionalInterface public interface Operation<T> { T run(CreditStore store) throws Exception; }
    private final Semaphore commands;
    private final ThreadPoolExecutor executor;
    private CreditStore store;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    public DatabaseWorker() { this(128, 2048); }
    DatabaseWorker(int commandLimit, int queueLimit) {
        commands = new Semaphore(commandLimit);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueLimit), r -> {
            Thread t = new Thread(r, "FlyCredit-database"); t.setDaemon(true); return t;
        }) {
            @Override protected void terminated() {
                try { if (store != null) store.close(); closed.complete(null); }
                catch (Exception ex) { closed.completeExceptionally(ex); }
                finally { super.terminated(); }
            }
        };
    }
    public void open(Path path) throws Exception {
        critical(s -> { store = new CreditStore(path); return null; }).get(15, TimeUnit.SECONDS);
    }
    public <T> CompletableFuture<T> submit(Operation<T> operation) {
        if (!commands.tryAcquire()) return CompletableFuture.failedFuture(new CreditException("Database occupato: riprova tra un istante."));
        // Release before notifying the caller. Cancelling its copy must not cancel the release.
        return enqueue(operation).whenComplete((value, error) -> commands.release()).copy();
    }
    /** Reserved capacity for refunds and checkpoints; rejection must stop flight, never discard credit silently. */
    public <T> CompletableFuture<T> critical(Operation<T> operation) { return enqueue(operation); }
    private <T> CompletableFuture<T> enqueue(Operation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { future.complete(operation.run(store)); }
                catch (Exception | LinkageError ex) { future.completeExceptionally(ex); }
            });
        } catch (RejectedExecutionException ex) { future.completeExceptionally(ex); }
        return future;
    }
    public void close() throws Exception {
        executor.shutdown();
        closed.get(15, TimeUnit.SECONDS);
    }
}
