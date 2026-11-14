package it.flycredit;

import it.flycredit.core.*;
import it.flycredit.integration.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FlyCreditPlugin extends JavaPlugin implements Listener, FlightService.Host {
    private DatabaseWorker database;
    private FlightService flight;
    private PlayTimeHook playtime;
    private boolean failed;
    private volatile boolean stopping;
    private CompletableFuture<Void> observing;
    @Override public void onEnable() {
        try {
            // A declared dependency may have failed during onEnable. Check before
            // resolving its API or opening storage, even when the loader found its JAR.
            requireDependencies(getServer().getPluginManager());
            saveDefaultConfig();
            Set<String> groups = new HashSet<>();
            getConfig().getStringList("transfer.allowed-recipient-groups").forEach(g -> groups.add(g.toLowerCase(Locale.ROOT)));
            if (groups.isEmpty()) throw new IllegalArgumentException("Lista rank destinatari vuota.");
            String mode = getConfig().getString("transfer.membership", "DIRECT");
            if (!Set.of("DIRECT", "INHERITED").contains(mode)) throw new IllegalArgumentException("membership deve essere DIRECT o INHERITED.");
            playtime = new PlayTimeHook(getServer().getPluginManager().getPlugin("PlayTimeManager"), groups, mode.equals("INHERITED"), getLogger());
            database = new DatabaseWorker();
            database.open(getDataFolder().toPath().resolve("flycredit.db"));
            try (InputStream seed = getResource("seed/variables.csv")) {
                if (seed != null) {
                    LegacyImport data = LegacyImport.read(seed.readNBytes(LegacyImport.MAX_BYTES + 1));
                    CreditStore.ImportResult result = database.submit(s -> s.importLegacy(data, true)).get(15, TimeUnit.SECONDS);
                    getLogger().info("Dati iniziali: " + result.imported() + " saldi importati, " + result.skipped() + " ignorati. Già importati: " + result.alreadyImported());
                }
            }
            int recovered = database.submit(CreditStore::recoverSessions).get(15, TimeUnit.SECONDS);
            if (recovered > 0) getLogger().info("Recuperate " + recovered + " sessioni interrotte.");
            flight = new FlightService(this, new ClaimAccess());
            FlyCreditCommands commands = new FlyCreditCommands(this);
            for (String name : List.of("flycredit", "claimfly", "flycreditinfo", "flycreditadd", "flycreditset")) {
                Objects.requireNonNull(getCommand(name)).setExecutor(commands);
                Objects.requireNonNull(getCommand(name)).setTabCompleter(commands);
            }
            getServer().getPluginManager().registerEvents(flight, this);
            getServer().getPluginManager().registerEvents(flight.landings(), this);
            getServer().getOnlinePlayers().forEach(flight.landings()::resume);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getScheduler().runTaskTimer(this, () -> {
                try { flight.tick(); } catch (Exception | LinkageError ex) { failClosed(ex); }
            }, 1L, 1L);
            getServer().getScheduler().runTaskTimer(this, () -> {
                try { observeAll(); } catch (Exception | LinkageError ex) { failClosed(ex); }
            }, 40L, 1200L);
            getLogger().info("FlyCredit attivo. Claim: proprietario oppure trust BUILDER/MANAGER. Rank riceventi: " + groups + " (" + mode + ").");
        } catch (Exception | LinkageError ex) {
            getLogger().log(Level.SEVERE, "Avvio FlyCredit annullato; nessun volo concesso.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    static void requireDependencies(PluginManager manager) {
        for (String name : List.of("GriefDefender", "LuckPerms", "PlayTimeManager")) {
            var dependency = manager.getPlugin(name);
            if (dependency == null || !dependency.isEnabled()) {
                throw new IllegalStateException("Dipendenza " + name + " "
                        + (dependency == null ? "mancante" : "disabilitata")
                        + ". FlyCredit non avvia database o volo. Controllare il primo errore di "
                        + name + " nel log del server.");
            }
        }
    }
    @Override public void onDisable() {
        stopping = true;
        try { if (flight != null) flight.stopAll(true); }
        finally {
            if (database != null) {
                try { database.close(); }
                catch (Exception ex) { getLogger().log(Level.SEVERE, "Chiusura database incompleta. Le prenotazioni saranno recuperate al prossimo avvio.", ex); }
            }
        }
    }
    public DatabaseWorker database() { return database; }
    public FlightService flight() { return flight; }
    public PlayTimeHook playtime() { return playtime; }
    public void onMain(Runnable task) {
        if (!isEnabled() || stopping) return;
        try { getServer().getScheduler().runTask(this, () -> {
            if (stopping) return;
            try { task.run(); } catch (Exception | LinkageError ex) { failClosed(ex); }
        }); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Stopping; outstanding reserves recover at boot. */ }
    }
    public <T> void persist(DatabaseWorker.Operation<T> op) {
        database.critical(op).whenComplete((result, error) -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Persistenza FlyCredit non completata.", error);
                onMain(() -> failClosed(error));
            }
        });
    }
    public void message(CommandSender sender, String message) { sender.sendMessage(ChatColor.AQUA + "[FlyCredit] " + ChatColor.GRAY + message); }
    public void report(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof ExecutionException)) cause = cause.getCause();
        if (cause instanceof CreditException) message(sender, cause.getMessage());
        else { message(sender, "Operazione non completata. Controllare la console; il volo è stato bloccato per proteggere i saldi."); failClosed(cause); }
    }
    public void failClosed(Throwable error) {
        if (failed) return;
        failed = true;
        getLogger().log(Level.SEVERE, "FlyCredit si arresta per un errore: saldi protetti, controllare database e dipendenze.", error);
        if (!stopping) getServer().getPluginManager().disablePlugin(this);
    }
    public org.bukkit.boss.BossBar createFlightBar() {
        return getServer().createBossBar("FlyCredit", org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SOLID);
    }
    public Player onlinePlayer(UUID id) { return getServer().getPlayer(id); }
    public void dependencyLost() { getServer().getPluginManager().disablePlugin(this); }
    private void observeAll() {
        if (observing != null && !observing.isDone()) return;
        List<CreditStore.Observation> snapshots = getServer().getOnlinePlayers().stream()
                .map(p -> new CreditStore.Observation(p.getUniqueId(), p.getName(), playtime.playtimeTicks(p))).toList();
        if (snapshots.isEmpty()) return;
        observing = database.submit(s -> { s.observeAll(snapshots); return null; });
        observing.whenComplete((result, error) -> {
            if (error != null) onMain(() -> report(getServer().getConsoleSender(), error));
        });
    }
    public void observe(Player p) {
        UUID id = p.getUniqueId(); String name = p.getName(); Long ticks = playtime.playtimeTicks(p);
        database.submit(s -> { s.observe(id, name, ticks); return null; })
                .whenComplete((result, error) -> { if (error != null) onMain(() -> report(getServer().getConsoleSender(), error)); });
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        // PTM loads its online cache asynchronously on join; later periodic snapshots fill gaps.
        getServer().getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isOnline()) observe(e.getPlayer()); }, 40L);
    }
}
