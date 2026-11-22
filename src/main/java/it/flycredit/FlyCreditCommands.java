package it.flycredit;

import it.flycredit.core.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class FlyCreditCommands implements TabExecutor, Listener {
    private final FlyCreditPlugin plugin;
    private final Set<UUID> pendingGifts = new HashSet<>();
    private final Map<Player, Long> lastCommand = new WeakHashMap<>();
    public FlyCreditCommands(FlyCreditPlugin plugin) { this.plugin = plugin; }
    private Player player(CommandSender sender) {
        if (!(sender instanceof Player p)) throw new CreditException("command.playerOnly");
        return p;
    }
    private boolean offersGifts(CommandSender sender) {
        return sender instanceof Player && sender.hasPermission("flycredit.pay")
                && !plugin.playtime().recipientGroups().isEmpty();
    }
    private boolean offersStop(Player player) {
        return player.hasPermission("flycredit.use") || plugin.flight().busy(player.getUniqueId());
    }

    // Check the same permissions for canonical commands and aliases, before giving usage or cooldown feedback.
    private void checkAccess(CommandSender sender, String action, int arguments, boolean duration) {
        String permission = switch (action) {
            case "add", "diagnostics" -> "flycredit.admin";
            case "info" -> arguments > 0 ? "flycredit.admin" : null;
            case "give", "send" -> "flycredit.pay";
            default -> duration ? "flycredit.use" : null;
        };
        if (duration || action.equals("give") || action.equals("send")) player(sender);
        if (permission != null && !sender.hasPermission(permission)) throw new CreditException("command.permission");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String name = command.getName().toLowerCase(Locale.ROOT);
            String action;
            String[] parameters = args;
            switch (name) {
                case "flycreditinfo" -> action = "info";
                case "flycreditadd" -> action = "add";
                case "flycredit" -> {
                    action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
                    if (args.length > 0) parameters = Arrays.copyOfRange(args, 1, args.length);
                }
                default -> { return false; }
            }
            boolean duration = !action.isEmpty() && "0123456789+-.,".indexOf(action.charAt(0)) >= 0;
            checkAccess(sender, action, parameters.length, duration);
            boolean stopping = parameters.length == 0 && (action.equals("off") || action.equals("stop"));
            if (sender instanceof Player p && !stopping) {
                long now = System.nanoTime();
                Long last = lastCommand.get(p);
                if (last != null && now - last < 250_000_000L) throw new CreditException("command.cooldown");
                lastCommand.put(p, now);
            }
            if (duration) {
                String invokedName = label.substring(label.lastIndexOf(':') + 1);
                if (parameters.length != 0 || !invokedName.equalsIgnoreCase("flycredit")) throw new CreditException("usage.reserve");
                Player p = player(sender);
                long amount = Amounts.minutes(action);
                if (plugin.flight().active(p.getUniqueId())) throw new CreditException("flight.finishFirst", "/flycredit off");
                plugin.flight().start(p, amount);
                return true;
            }
            switch (action) {
                case "help" -> { arity(parameters, 0, 0, "usage.help"); help(sender); }
                case "info" -> {
                    arity(parameters, 0, 1, sender instanceof Player ? "usage.infoAny" : "usage.inspect");
                    info(sender, parameters.length == 0 ? null : parameters[0]);
                }
                case "off", "stop" -> {
                    arity(parameters, 0, 0, "usage.off");
                    plugin.flight().stopManually(player(sender));
                }
                case "give", "send" -> {
                    arity(parameters, 2, 2, "usage.give");
                    give(player(sender), parameters[0], Amounts.minutes(parameters[1]));
                }
                case "add" -> {
                    arity(parameters, 2, 2, "usage.add");
                    addCredit(sender, parameters[0], parameters[1]);
                }
                case "diagnostics" -> { arity(parameters, 0, 0, "usage.diagnostics"); diagnostics(sender); }
                default -> plugin.message(sender, parameters.length == 0 ? "command.unknownOrDuration" : "command.unknown", "/flycredit help");
            }
        } catch (CreditException ex) { plugin.message(sender, ex.key(), ex.arguments()); }
        catch (Exception | LinkageError ex) { plugin.report(sender, ex); }
        return true;
    }

    private void arity(String[] args, int min, int max, String usageKey) {
        if (args.length < min || args.length > max) throw new CreditException(usageKey);
    }

    private void help(CommandSender sender) {
        var panel = new Chat.Panel(sender, "/flycredit", "help.title");
        boolean admin = sender.hasPermission("flycredit.admin");
        if (sender instanceof Player player) {
            if (sender.hasPermission("flycredit.use")) {
                panel.command("usage.reserve", "help.flight");
            }
            if (offersStop(player)) panel.command("usage.off", "help.off");
            if (admin) panel.command("usage.infoAny", "help.infoAny");
            else panel.command("usage.info", "help.info");
            if (offersGifts(sender)) panel.command("usage.give", "help.give");
        } else if (admin) {
            panel.command("usage.inspect", "help.inspect");
        }
        if (admin) {
            panel.command("usage.add", "help.add");
            panel.command("usage.diagnostics", "help.diagnostics");
        }
        panel.command("usage.help", "help.help");
        panel.send();
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
        if (target == null) {
            if (!(sender instanceof Player p)) throw new CreditException("usage.inspect");
            showStats(sender, p.getUniqueId(), null);
            return;
        }
        resolve(target).whenComplete((id, error) -> plugin.onMain(() -> {
            if (error != null) plugin.report(sender, error);
            else showStats(sender, id, target);
        }));
    }

    private void showStats(CommandSender sender, UUID id, String target) {
        // Sample Bukkit session state on the server thread before the read-only database query.
        var live = plugin.flight().usageSnapshot(id);
        plugin.database().submit(store -> store.flightStats(id, live)).whenComplete((stats, error) -> plugin.onMain(() -> {
            if (error != null) { plugin.report(sender, error); return; }
            Chat.Panel panel;
            if (target == null) {
                panel = new Chat.Panel(sender, "FlyCredit", "account.title");
            } else {
                Player online = plugin.getServer().getPlayer(id);
                String accountName = online == null ? stats.name() : online.getName();
                panel = new Chat.Panel(sender, "FlyCredit", "account.target", accountName == null ? target : accountName);
            }
            panel.field("account.available", Amounts.format(stats.available()));
            panel.field("account.used", Amounts.format(stats.used()));
            panel.send();
        }));
    }
    private void addCredit(CommandSender sender, String target, String text) {
        long millis = Amounts.minutes(text);
        String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        resolve(target).thenCompose(id -> plugin.database().submit(s -> s.addCredit(id, millis, actor)))
                .whenComplete((a, ex) -> plugin.onMain(() -> {
                    if (ex != null) { plugin.report(sender, ex); return; }
                    plugin.message(sender, "account.balanceChanged", target, Amounts.format(millis), Amounts.format(a.available()));
                    Player recipient = plugin.getServer().getPlayer(a.uuid());
                    if (recipient != null && recipient != sender) plugin.message(recipient, "account.yourBalance",
                            Amounts.format(millis), Amounts.format(a.available()));
                }));
    }
    private void give(Player from, String targetName, long millis) {
        Set<String> groups = plugin.playtime().recipientGroups();
        if (groups.isEmpty()) throw new CreditException("transfer.disabled");
        Player to = plugin.getServer().getPlayerExact(targetName);
        if (to == null || !from.canSee(to)) throw new CreditException("transfer.offline");
        if (from.getUniqueId().equals(to.getUniqueId())) throw new CreditException("transfer.self");
        if (!plugin.playtime().canReceive(to)) throw new CreditException("transfer.group");
        UUID sender = from.getUniqueId(), recipient = to.getUniqueId();
        if (!pendingGifts.add(sender)) throw new CreditException("transfer.pending");
        // The rank assigned by PTM is evaluated through LuckPerms at command acceptance.
        plugin.database().submit(s -> { s.transfer(sender, recipient, millis); return null; })
                .whenComplete((unused, ex) -> plugin.onMain(() -> {
                    pendingGifts.remove(sender);
                    if (ex != null) { plugin.report(from, ex); return; }
                    if (from.isOnline()) plugin.message(from, "transfer.sent", to.getName(),
                            Amounts.format(millis));
                    if (to.isOnline()) plugin.message(to, "transfer.received", from.getName(),
                            Amounts.format(millis));
                }));
    }
    private void diagnostics(CommandSender sender) {
        var panel = new Chat.Panel(sender, "FlyCredit", "diagnostics.title");
        panel.detail("diagnostics.server", plugin.getServer().getVersion(), System.getProperty("java.version"));
        for (String name : List.of("GriefDefender", "LuckPerms", "PlayTimeManager")) {
            var dependency = plugin.getServer().getPluginManager().getPlugin(name);
            if (dependency == null) panel.detail("diagnostics.missing", name);
            else panel.detail(dependency.isEnabled() ? "diagnostics.enabled" : "diagnostics.disabled",
                    name, dependency.getPluginMeta().getVersion());
        }
        List<String> guestTypes = plugin.claimAccess().guestTypeIds();
        if (guestTypes.isEmpty()) panel.detail("diagnostics.ownersOnly");
        else panel.detail("diagnostics.guestTypes", String.join(", ", guestTypes));
        Set<String> groups = plugin.playtime().recipientGroups();
        if (groups.isEmpty()) {
            panel.detail("diagnostics.transfersOff");
        } else {
            String mode = "INHERITED".equals(plugin.getConfig().getString("transfer.membership"))
                    ? "diagnostics.inherited" : "diagnostics.direct";
            panel.detail("diagnostics.groups", String.join(", ", groups), panel.language().text(mode));
            if (sender instanceof Player p) {
                panel.detail(plugin.playtime().canReceive(p) ? "diagnostics.canReceive" : "diagnostics.cannotReceive");
            }
        }
        panel.send();
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void commandList(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(name -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            return normalized.startsWith("flycredit:")
                    || normalized.equals("flycreditadd") || normalized.equals("flycreditinfo");
        });
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        boolean admin = sender.hasPermission("flycredit.admin");
        String name = command.getName().toLowerCase(Locale.ROOT);
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (name.equals("flycredit")) {
            if (args.length <= 1) {
                choices.add("help");
                if (sender instanceof Player || admin) choices.add("info");
                if (sender instanceof Player player && offersStop(player)) choices.add("off");
                if (offersGifts(sender)) choices.add("give");
                if (admin) choices.addAll(List.of("add", "diagnostics"));
            } else if (args.length == 2) {
                if ((action.equals("give") || action.equals("send")) && offersGifts(sender)) {
                    choices.addAll(playerNames(sender, true));
                } else if (admin && (action.equals("info") || action.equals("add"))) {
                    choices.addAll(playerNames(sender, false));
                }
            }
        } else if (admin && args.length == 1 && Set.of("flycreditadd", "flycreditinfo").contains(name)) {
            choices.addAll(playerNames(sender, false));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
    private List<String> playerNames(CommandSender sender, boolean gift) {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p))
                .filter(p -> !gift || p != sender && plugin.playtime().canReceive(p))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
