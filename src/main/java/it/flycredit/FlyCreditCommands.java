package it.flycredit;

import it.flycredit.core.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class FlyCreditCommands implements TabExecutor {
    private final FlyCreditPlugin plugin;
    private final Set<UUID> pendingGifts = new HashSet<>();
    private final Map<Player, Long> lastCommand = new WeakHashMap<>();
    public FlyCreditCommands(FlyCreditPlugin plugin) { this.plugin = plugin; }
    private Player player(CommandSender sender) {
        if (!(sender instanceof Player p)) throw new CreditException("command.playerOnly");
        return p;
    }
    private void admin(CommandSender sender) {
        if (!sender.hasPermission("flycredit.admin")) throw new CreditException("command.permission", "flycredit.admin");
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String name = command.getName().toLowerCase(Locale.ROOT);
            boolean stopping = name.equals("flycredit") && args.length == 1
                    && Set.of("off", "stop").contains(args[0].toLowerCase(Locale.ROOT));
            if (sender instanceof Player p && !stopping) {
                long now = System.nanoTime();
                Long last = lastCommand.get(p);
                if (last != null && now - last < 250_000_000L) throw new CreditException("command.cooldown");
                lastCommand.put(p, now);
            }
            if (name.equals("flycreditinfo")) { arity(args, 0, 1, "usage.infoAlias"); info(sender, args.length == 0 ? null : args[0]); return true; }
            if (name.equals("flycreditadd")) {
                arity(args, 2, 2, "usage.addAlias");
                addCredit(sender, args[0], args[1]);
                return true;
            }
            if (!name.equals("flycredit")) return false;
            if (args.length == 0) { info(sender, null); help(sender); return true; }
            String action = args[0].toLowerCase(Locale.ROOT);
            if (!action.isEmpty() && "0123456789+-.,".indexOf(action.charAt(0)) >= 0) {
                String invokedName = label.substring(label.lastIndexOf(':') + 1);
                if (args.length != 1 || !name.equalsIgnoreCase(invokedName)) throw new CreditException("usage.reserve");
                Player p = player(sender);
                long duration = Amounts.minutes(action);
                if (plugin.flight().active(p.getUniqueId())) throw new CreditException("flight.finishFirst");
                plugin.flight().start(p, duration);
                return true;
            }
            switch (action) {
                case "help" -> help(sender);
                case "info" -> { arity(args, 1, 2, "usage.info"); info(sender, args.length > 1 ? args[1] : null); }
                case "off", "stop" -> { arity(args, 1, 1, "usage.off"); plugin.flight().stop(player(sender), "flight.stopped"); }
                case "give", "send" -> {
                    if (args.length != 3) throw new CreditException("usage.give");
                    give(player(sender), args[1], Amounts.minutes(args[2]));
                }
                case "add" -> {
                    arity(args, 3, 3, "usage.add");
                    addCredit(sender, args[1], args[2]);
                }
                case "diagnostics" -> { arity(args, 1, 1, "usage.diagnostics"); diagnostics(sender); }
                default -> plugin.message(sender, "command.unknown");
            }
        } catch (CreditException ex) { plugin.message(sender, ex.key(), ex.arguments()); }
        catch (Exception | LinkageError ex) { plugin.report(sender, ex); }
        return true;
    }
    private void arity(String[] args, int min, int max, String usageKey) {
        if (args.length < min || args.length > max) throw new CreditException(usageKey);
    }
    private void help(CommandSender sender) {
        plugin.message(sender, "help.owner", String.join(", ", plugin.getPluginMeta().getAuthors()));
        plugin.message(sender, "help.flight");
        plugin.message(sender, "help.duration");
        plugin.message(sender, "help.credit");
        if (sender.hasPermission("flycredit.admin")) plugin.message(sender, "help.admin");
    }
    private CompletableFuture<UUID> resolve(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equalsIgnoreCase(text)) return CompletableFuture.completedFuture(id);
        } catch (IllegalArgumentException ignored) {}
        Player p = plugin.getServer().getPlayerExact(text);
        if (p != null) return CompletableFuture.completedFuture(p.getUniqueId());
        // Cache only; never trigger a blocking Mojang lookup on the server thread.
        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(text);
        if (cached != null) return CompletableFuture.completedFuture(cached.getUniqueId());
        return plugin.database().submit(s -> s.findName(text).orElseThrow(() -> new CreditException("account.unknown")));
    }
    private void info(CommandSender sender, String target) {
        if (target == null && !(sender instanceof Player)) throw new CreditException("console.infoUsage");
        if (target != null) admin(sender);
        CompletableFuture<UUID> id = target == null ? CompletableFuture.completedFuture(player(sender).getUniqueId()) : resolve(target);
        id.thenCompose(uuid -> plugin.database().submit(s -> s.account(uuid))).whenComplete((a, ex) -> plugin.onMain(() -> {
            if (ex != null) { plugin.report(sender, ex); return; }
            long reserved = plugin.flight().active(a.uuid()) ? plugin.flight().remaining(a.uuid()) : a.reserved();
            plugin.message(sender, "account.balance", a.name() == null ? a.uuid() : a.name(),
                    Amounts.format(a.available()), Amounts.format(reserved));
            plugin.message(sender, "account.history", Amounts.format(a.used()),
                    Amounts.format(a.sent()), Amounts.format(a.received()));
            Player online = Bukkit.getPlayer(a.uuid());
            Long live = online == null ? null : plugin.playtime().playtimeTicks(online);
            if (live != null) {
                plugin.message(sender, "account.playtimeLive", formatTicks(live));
            } else if (a.playtimeTicks() != null) {
                plugin.message(sender, "account.playtimeSaved", formatTicks(a.playtimeTicks()),
                        java.time.Instant.ofEpochMilli(a.playtimeSnapshotAt()));
            } else {
                plugin.message(sender, "account.playtimeUnknown");
            }
        }));
    }
    private String formatTicks(long ticks) { return Amounts.format(Math.min(ticks, Long.MAX_VALUE / 50) * 50); }
    private void addCredit(CommandSender sender, String target, String text) {
        admin(sender);
        long millis = Amounts.minutes(text);
        String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        resolve(target).thenCompose(id -> plugin.database().submit(s -> s.addCredit(id, millis, actor)))
                .whenComplete((a, ex) -> plugin.onMain(() -> {
                    if (ex != null) { plugin.report(sender, ex); return; }
                    plugin.message(sender, "account.balanceChanged", target, Amounts.format(a.available()));
                    Player recipient = plugin.getServer().getPlayer(a.uuid());
                    if (recipient != null && recipient != sender) plugin.message(recipient, "account.yourBalance", Amounts.format(a.available()));
                }));
    }
    private void give(Player from, String targetName, long millis) {
        if (!from.hasPermission("flycredit.pay")) throw new CreditException("command.permission", "flycredit.pay");
        Set<String> groups = plugin.playtime().recipientGroups();
        if (groups.isEmpty()) throw new CreditException("transfer.disabled");
        Player to = plugin.getServer().getPlayerExact(targetName);
        if (to == null || !from.canSee(to)) throw new CreditException("transfer.offline");
        if (from.getUniqueId().equals(to.getUniqueId())) throw new CreditException("transfer.self");
        if (!plugin.playtime().canReceive(to)) throw new CreditException("transfer.group", String.join(", ", groups));
        UUID sender = from.getUniqueId(), recipient = to.getUniqueId();
        if (!pendingGifts.add(sender)) throw new CreditException("transfer.pending");
        // The rank assigned by PTM is evaluated through LuckPerms at command acceptance.
        plugin.database().submit(s -> { s.transfer(sender, recipient, millis); return null; })
                .whenComplete((unused, ex) -> plugin.onMain(() -> {
                    pendingGifts.remove(sender);
                    if (ex != null) { plugin.report(from, ex); return; }
                    if (from.isOnline()) plugin.message(from, "transfer.sent", to.getName(),
                            Language.forLocale(from.locale()).duration(millis));
                    if (to.isOnline()) plugin.message(to, "transfer.received", from.getName(),
                            Language.forLocale(to.locale()).duration(millis));
                }));
    }
    private void diagnostics(CommandSender sender) {
        admin(sender);
        plugin.message(sender, "diagnostics.server", Bukkit.getVersion(), System.getProperty("java.version"));
        for (String name : List.of("GriefDefender", "LuckPerms", "PlayTimeManager")) {
            var p = Bukkit.getPluginManager().getPlugin(name);
            if (p == null) {
                plugin.message(sender, "diagnostics.missing", name);
            } else {
                plugin.message(sender, p.isEnabled() ? "diagnostics.enabled" : "diagnostics.disabled", name, p.getPluginMeta().getVersion());
            }
        }
        Set<String> groups = plugin.playtime().recipientGroups();
        if (groups.isEmpty()) {
            plugin.message(sender, "diagnostics.transfersOff");
        } else {
            plugin.message(sender, "diagnostics.groups", String.join(", ", groups), plugin.getConfig().getString("transfer.membership"));
        }
        if (sender instanceof Player p) {
            plugin.message(sender, plugin.playtime().canReceive(p) ? "diagnostics.canReceive" : "diagnostics.cannotReceive");
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("flycredit") && args.length == 1) {
            choices.addAll(List.of("info", "off", "give", "help"));
            if (sender.hasPermission("flycredit.admin")) choices.addAll(List.of("add", "diagnostics"));
        } else if (args.length == 2 && Set.of("give", "send", "info", "add").contains(args[0].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().stream().filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p)).map(Player::getName).forEach(choices::add);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
