package it.flycredit;

import it.flycredit.core.*;
import it.flycredit.integration.ClaimAccess;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Server-thread session ownership; the database worker only receives immutable values. */
public final class FlightService implements Listener {
    interface Host {
        DatabaseWorker database();
        void onMain(Runnable task);
        <T> void persist(DatabaseWorker.Operation<T> operation);
        void message(CommandSender player, String key, Object... arguments);
        void report(CommandSender player, Throwable error);
        void failClosed(Throwable error);
        BossBar createFlightBar();
        Player onlinePlayer(UUID id);
        void dependencyLost();
    }
    private final Host host;
    private final BiPredicate<UUID, Location> claims;
    private final LongSupplier clock;
    private final Predicate<Player> grounded;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> pending = new HashMap<>();
    private final FallProtection landings;
    private final List<End> endings = new ArrayList<>();
    private long ticks;
    private final int checkpointTicks;
    private final boolean bars;
    private CompletableFuture<Void> saving;
    private long savingSince;
    private record End(Player player, String messageKey) {}
    private static final class Session {
        final Player player;
        final UUID token;
        final FlightMeter meter;
        final long initial;
        final BossBar bar;
        long submitted;
        long shownSecond = -1;
        boolean shownConsuming;
        Language shownLanguage;
        Session(Player p, UUID token, long millis, BossBar bar, long now) {
            player = p; this.token = token;
            meter = new FlightMeter(millis, now); initial = millis; submitted = millis; this.bar = bar;
        }
    }
    public FlightService(FlyCreditPlugin plugin, ClaimAccess claims) {
        this(plugin, claims::canFly, System::nanoTime, GroundProbe::isSupported,
                plugin.getConfig().getInt("storage.checkpoint-ticks", 20),
                plugin.getConfig().getInt("flight.fall-protection-seconds", 15),
                plugin.getConfig().getBoolean("flight.bossbar", true), FallProtection.BUKKIT_EFFECT);
    }
    FlightService(Host host, BiPredicate<UUID, Location> claims, LongSupplier clock,
                  Predicate<Player> grounded, int checkpointTicks, int protectionSeconds, boolean bars,
                  FallProtection.SlowFall slowFall) {
        this.host = host; this.claims = claims; this.clock = clock; this.grounded = grounded;
        this.checkpointTicks = Math.max(1, Math.min(200, checkpointTicks));
        this.landings = new FallProtection(clock, grounded, host::onlinePlayer, protectionSeconds, slowFall);
        this.bars = bars;
    }
    FallProtection landings() { return landings; }
    private boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }
    private boolean valid(Player p, Location at) {
        return at != null && p.isOnline() && !p.isDead() && survival(p)
                && p.hasPermission("flycredit.use") && claims.test(p.getUniqueId(), at);
    }
    private boolean consuming(Player p) { return p.isFlying() && !p.isInsideVehicle() && !grounded.test(p); }
    public boolean active(UUID id) { return sessions.containsKey(id); }
    public boolean busy(UUID id) { return active(id) || pending.containsKey(id); }
    public long remaining(UUID id) { Session s = sessions.get(id); return s == null ? 0 : s.meter.remainingMillis(); }
    CreditStore.Checkpoint usageSnapshot(UUID player) {
        Session session = sessions.get(player);
        if (session == null) return null;
        return new CreditStore.Checkpoint(player, session.token, session.meter.remainingMillis(), false);
    }
    public void start(Player p, long amount) {
        if (amount <= 0 || amount > Amounts.MAX_MILLIS) throw new CreditException("amount.invalid");
        if (busy(p.getUniqueId())) throw new CreditException("flight.busy");
        if (!p.hasPermission("flycredit.use")) throw new CreditException("command.permission");
        if (!survival(p)) throw new CreditException("flight.gameModeRequired");
        if (!valid(p, p.getLocation())) throw new CreditException("flight.claimRequired");
        if (p.getAllowFlight()) throw new CreditException("flight.alreadyFlying");
        UUID id = p.getUniqueId(), token = UUID.randomUUID();
        pending.put(id, token);
        host.database().submit(store -> store.reserve(id, token, amount)).whenComplete((reserved, error) ->
                host.onMain(() -> guarded(() -> activate(p, token, reserved, error))));
    }
    private void release(UUID id, UUID token) {
        host.persist(store -> { store.releaseReservation(id, token); return null; });
    }
    private void activate(Player p, UUID token, Long reserved, Throwable error) {
        UUID id = p.getUniqueId();
        boolean current = token.equals(pending.get(id));
        if (error != null) {
            pending.remove(id, token);
            if (current) host.report(p, error);
            return;
        }
        try {
            if (!current || !valid(p, p.getLocation()) || p.getAllowFlight()) {
                pending.remove(id, token);
                release(id, token);
                if (current && p.isOnline()) host.message(p, "flight.startCancelled");
                return;
            }
            BossBar bar = bars ? host.createFlightBar() : null;
            Session s = new Session(p, token, reserved, bar, clock.getAsLong());
            sessions.put(id, s);
            pending.remove(id, token);
            landings.clear(p);
            p.setAllowFlight(true); p.setFlying(true);
            s.meter.update(clock.getAsLong(), consuming(p));
            if (bar != null) bar.addPlayer(p);
            updateBar(s);
            host.message(p, "flight.started", Amounts.format(reserved));
        } catch (RuntimeException | LinkageError ex) {
            if (!sessions.containsKey(id)) { pending.remove(id, token); release(id, token); }
            throw ex; // The guard stops every session if an integration or Bukkit call fails.
        }
    }
    public void stop(Player p, String reasonKey) {
        stop(p, reasonKey, true);
    }
    public void stopManually(Player p) {
        if (!busy(p.getUniqueId())) {
            host.message(p, "flight.inactive");
            return;
        }
        stop(p, active(p.getUniqueId()) ? "flight.stopped" : "flight.startCancelled", false);
    }
    private void stop(Player p, String reasonKey, boolean protectLanding) {
        UUID id = p.getUniqueId();
        UUID pendingToken = pending.remove(id);
        if (pendingToken != null) release(id, pendingToken);
        Session s = sessions.remove(id);
        if (s != null) {
            s.meter.update(clock.getAsLong(), false);
            long left = s.meter.remainingMillis();
            // Enqueue settlement even if another plugin causes an exception while flight flags are reset.
            host.persist(store -> { store.checkpoint(id, s.token, left, true); return null; });
            revoke(s, protectLanding);
        } else if (pendingToken != null && !protectLanding) {
            landings.clear(p);
        }
        if ((s != null || pendingToken != null) && reasonKey != null && p.isOnline()) host.message(p, reasonKey);
    }
    private void revoke(Session s, boolean protectLanding) {
        Player p = s.player;
        try {
            if (survival(p)) {
                try { p.setFlying(false); } finally { p.setAllowFlight(false); }
                if (protectLanding) {
                    p.setFallDistance(0);
                    landings.grant(p);
                }
            }
        } finally {
            try { if (!protectLanding) landings.clear(p); }
            finally { if (s.bar != null) s.bar.removeAll(); }
        }
    }
    public void tick() {
        ticks++;
        long now = clock.getAsLong();
        if (saving != null && !saving.isDone() && now - savingSince > 10_000_000_000L)
            throw new IllegalStateException("Saving flight usage has stalled for more than ten seconds.");
        endings.clear();
        for (Session s : sessions.values()) {
            Player p = s.player;
            if (ticks % 5 == 0 && (!p.getAllowFlight() || !valid(p, p.getLocation()))) {
                endings.add(new End(p, "flight.accessLost"));
                continue;
            }
            s.meter.update(now, consuming(p));
            if (s.meter.remainingMillis() == 0) {
                endings.add(new End(p, "flight.exhausted"));
                continue;
            }
            if (ticks % 5 == 0) updateBar(s);
        }
        for (End end : endings) stop(end.player(), end.messageKey());
        endings.clear();
        if (ticks % checkpointTicks == 0 && (saving == null || saving.isDone())) saveBatch(now);
        landings.tick(now);
    }
    private void saveBatch(long now) {
        List<CreditStore.Checkpoint> writes = new ArrayList<>();
        for (Session s : sessions.values()) {
            long left = s.meter.remainingMillis();
            if (left != s.submitted) {
                writes.add(new CreditStore.Checkpoint(s.player.getUniqueId(), s.token, left, false));
                s.submitted = left;
            }
        }
        if (writes.isEmpty()) return;
        List<CreditStore.Checkpoint> batch = List.copyOf(writes);
        savingSince = now;
        saving = host.database().critical(store -> { store.checkpointAll(batch); return null; });
        saving.whenComplete((unused, error) -> {
            if (error != null) host.onMain(() -> host.failClosed(error));
        });
    }
    private void updateBar(Session s) {
        if (s.bar == null) return;
        long remaining = s.meter.remainingMillis();
        long second = Math.ceilDiv(remaining, 1000);
        Language language = Language.forLocale(s.player.locale());
        if (s.shownSecond != second || s.shownConsuming != s.meter.consuming() || s.shownLanguage != language) {
            s.shownSecond = second;
            s.shownConsuming = s.meter.consuming();
            s.shownLanguage = language;
            ChatColor labelColor = s.shownConsuming ? ChatColor.AQUA : ChatColor.GRAY;
            s.bar.setTitle(labelColor + language.text(s.shownConsuming ? "bar.active" : "bar.paused")
                    + ChatColor.DARK_GRAY + "  •  " + ChatColor.WHITE + ChatColor.BOLD + Amounts.clock(remaining));
        }
        s.bar.setColor(!s.meter.consuming() ? BarColor.WHITE
                : remaining <= 30_000 ? BarColor.RED : remaining <= 120_000 ? BarColor.YELLOW : BarColor.GREEN);
        s.bar.setProgress(Math.max(0, Math.min(1, remaining / (double) s.initial)));
    }
    private void guarded(Runnable action) {
        try { action.run(); } catch (RuntimeException | LinkageError ex) { host.failClosed(ex); }
    }
    private void checkDestination(Player p, Location destination) {
        if (busy(p.getUniqueId()) && !valid(p, destination))
            stop(p, "flight.leftClaim");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.hasChangedBlock()) guarded(() -> checkDestination(e.getPlayer(), e.getTo()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) { guarded(() -> checkDestination(e.getPlayer(), e.getTo())); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void toggleFlight(PlayerToggleFlightEvent e) {
        guarded(() -> {
            Session s = sessions.get(e.getPlayer().getUniqueId());
            if (s != null) s.meter.update(clock.getAsLong(),
                    e.isFlying() && !e.getPlayer().isInsideVehicle() && !grounded.test(e.getPlayer()));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent e) { guarded(() -> stop(e.getPlayer(), "flight.gameModeChanged")); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        guarded(() -> {
            stop(e.getPlayer(), null);
            landings.suspend(e.getPlayer());
        });
    }
    @EventHandler public void death(PlayerDeathEvent e) {
        guarded(() -> { stop(e.getEntity(), null); landings.clear(e.getEntity()); });
    }
    @EventHandler public void world(PlayerChangedWorldEvent e) { guarded(() -> checkDestination(e.getPlayer(), e.getPlayer().getLocation())); }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        guarded(() -> { stop(e.getPlayer(), null); landings.clear(e.getPlayer()); });
    }
    @EventHandler public void dependencyDisabled(PluginDisableEvent e) {
        if (Set.of("GriefDefender", "LuckPerms", "PlayTimeManager").contains(e.getPlugin().getName())) host.dependencyLost();
    }
    public void stopAll(boolean shutdown) {
        // One transaction for all active sessions; do not depend on callbacks running during shutdown.
        List<Session> ending = List.copyOf(sessions.values());
        sessions.clear();
        List<CreditStore.Checkpoint> batch = new ArrayList<>(ending.size());
        for (Session s : ending) {
            s.meter.update(clock.getAsLong(), false);
            batch.add(new CreditStore.Checkpoint(s.player.getUniqueId(), s.token, s.meter.remainingMillis(), true));
        }
        Map<UUID, UUID> cancelled = Map.copyOf(pending);
        pending.clear();
        if (!batch.isEmpty() || !cancelled.isEmpty()) host.persist(store -> {
            store.checkpointAll(batch);
            for (var entry : cancelled.entrySet()) store.releaseReservation(entry.getKey(), entry.getValue());
            return null;
        });
        Throwable failure = null;
        for (Session s : ending) {
            try { revoke(s, true); }
            catch (RuntimeException | LinkageError ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        if (shutdown) landings.suspendAll();
        if (failure != null) host.failClosed(failure);
    }
}
