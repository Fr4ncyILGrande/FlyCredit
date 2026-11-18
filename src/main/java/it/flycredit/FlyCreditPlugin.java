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
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class FlyCreditPlugin extends JavaPlugin implements Listener, FlightService.Host {
    private DatabaseWorker database;
    private FlightService flight;
    private PlayTimeHook playtime;
    private ClaimAccess claimAccess;
    private boolean failed;
    private volatile boolean stopping;
    private CompletableFuture<Void> observing;
    @Override public void onEnable() {
        try {
            // A declared dependency may have failed during onEnable. Check before
            // resolving its API or opening storage, even when the loader found its JAR.
            requireDependencies(getServer().getPluginManager());
            saveDefaultConfig();
            try {
                ConfigComments.update(getDataFolder().toPath().resolve("config.yml"));
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | org.yaml.snakeyaml.error.YAMLException ex) {
                getLogger().warning("Could not update config.yml: " + ex.getMessage());
            }
            Set<String> groups = new TreeSet<>();
            for (String name : getConfig().getStringList("transfer.allowed-recipient-groups")) {
                if (!name.isBlank()) groups.add(name.strip().toLowerCase(Locale.ROOT));
            }
            String mode = getConfig().getString("transfer.membership", "DIRECT");
            if (!Set.of("DIRECT", "INHERITED").contains(mode)) throw new IllegalArgumentException("transfer.membership must be DIRECT or INHERITED.");
            playtime = new PlayTimeHook(getServer().getPluginManager().getPlugin("PlayTimeManager"), groups, mode.equals("INHERITED"), getLogger());
            claimAccess = ClaimAccess.fromConfig(getConfig(), getLogger());
            database = new DatabaseWorker();
            database.open(getDataFolder().toPath().resolve("flycredit.db"));
            int recovered = database.submit(CreditStore::recoverSessions).get(15, TimeUnit.SECONDS);
            if (recovered > 0) getLogger().info("Recovered interrupted flight sessions: " + recovered);
            flight = new FlightService(this, claimAccess);
            FlyCreditCommands commands = new FlyCreditCommands(this);
            for (String name : List.of("flycredit", "flycreditinfo", "flycreditadd")) {
                Objects.requireNonNull(getCommand(name)).setExecutor(commands);
                Objects.requireNonNull(getCommand(name)).setTabCompleter(commands);
            }
            getServer().getPluginManager().registerEvents(flight, this);
            getServer().getPluginManager().registerEvents(commands, this);
            getServer().getPluginManager().registerEvents(flight.landings(), this);
            getServer().getOnlinePlayers().forEach(flight.landings()::resume);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getScheduler().runTaskTimer(this, () -> {
                try { flight.tick(); } catch (Exception | LinkageError ex) { failClosed(ex); }
            }, 1L, 1L);
            getServer().getScheduler().runTaskTimer(this, () -> {
                try { observeAll(); } catch (Exception | LinkageError ex) { failClosed(ex); }
            }, 40L, 1200L);
            String transfers = groups.isEmpty()
                    ? "Transfers are disabled. Set transfer.allowed-recipient-groups to enable /flycredit give."
                    : "Recipient groups: " + groups + " (" + mode + ").";
            List<String> guestTypes = claimAccess.guestTypeIds();
            String access = guestTypes.isEmpty() ? "Only claim owners can fly."
                    : "Guests need one of these personal claim trust types: " + String.join(", ", guestTypes) + ".";
            getLogger().info("FlyCredit enabled. " + access + " " + transfers);
        } catch (Exception | LinkageError ex) {
            getLogger().log(Level.SEVERE, "FlyCredit startup failed. Flight has not been enabled.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    static void requireDependencies(PluginManager manager) {
        for (String name : List.of("GriefDefender", "LuckPerms", "PlayTimeManager")) {
            var dependency = manager.getPlugin(name);
            if (dependency == null || !dependency.isEnabled()) {
                throw new IllegalStateException(name + " is "
                        + (dependency == null ? "missing" : "disabled")
                        + ". FlyCredit cannot start the database or enable flight. Check the first error from "
                        + name + " in the server log.");
            }
        }
    }
    @Override public void onDisable() {
        stopping = true;
        try { if (flight != null) flight.stopAll(true); }
        finally {
            if (database != null) {
                try { database.close(); }
                catch (Exception ex) { getLogger().log(Level.SEVERE, "Database shutdown did not finish. Reservations will be recovered on the next startup.", ex); }
            }
        }
    }
    public DatabaseWorker database() { return database; }
    public FlightService flight() { return flight; }
    public PlayTimeHook playtime() { return playtime; }
    ClaimAccess claimAccess() { return claimAccess; }
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
                getLogger().log(Level.SEVERE, "FlyCredit could not save pending changes.", error);
                onMain(() -> failClosed(error));
            }
        });
    }
    @Override public void message(CommandSender sender, String key, Object... arguments) {
        Language language = sender instanceof Player player ? Language.forLocale(player.locale()) : Language.ENGLISH;
        sender.sendMessage(ChatColor.DARK_AQUA + "FlyCredit" + ChatColor.DARK_GRAY + " » "
                + ChatColor.GRAY + language.text(key, arguments));
    }
    public void report(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof CompletionException || cause instanceof ExecutionException)) cause = cause.getCause();
        if (cause instanceof CreditException errorMessage) message(sender, errorMessage.key(), errorMessage.arguments());
        else { message(sender, "error.unexpected"); failClosed(cause); }
    }
    public void failClosed(Throwable error) {
        if (failed) return;
        failed = true;
        getLogger().log(Level.SEVERE, "Stopping FlyCredit to protect account balances. Check the database and plugin dependencies.", error);
        if (!stopping) getServer().getPluginManager().disablePlugin(this);
    }
    public org.bukkit.boss.BossBar createFlightBar() {
        return getServer().createBossBar("FlyCredit", org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SEGMENTED_10);
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
