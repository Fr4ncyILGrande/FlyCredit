package it.flycredit;

import it.flycredit.core.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class FlyCreditCommands implements TabExecutor {
    private final FlyCreditPlugin plugin;
    private final Set<UUID> pendingGifts = new HashSet<>();
    private final Map<Player, Long> lastCommand = new WeakHashMap<>();
    private String pendingImportHash;
    private long importPreviewAt;
    public FlyCreditCommands(FlyCreditPlugin plugin) { this.plugin = plugin; }
    private Player player(CommandSender sender) {
        if (!(sender instanceof Player p)) throw new CreditException("Questo comando richiede un giocatore.");
        return p;
    }
    private void admin(CommandSender sender) {
        if (!sender.hasPermission("flycredit.admin")) throw new CreditException("Serve il permesso flycredit.admin.");
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String name = command.getName().toLowerCase(Locale.ROOT);
            boolean stopping = (name.equals("claimfly") && sender instanceof Player p && plugin.flight().busy(p.getUniqueId()))
                    || (name.equals("flycredit") && args.length == 1 && Set.of("off", "stop").contains(args[0].toLowerCase(Locale.ROOT)));
            if (sender instanceof Player p && !stopping) {
                long now = System.nanoTime();
                Long last = lastCommand.get(p);
                if (last != null && now - last < 250_000_000L) throw new CreditException("Attendi un istante prima di ripetere il comando.");
                lastCommand.put(p, now);
            }
            if (name.equals("claimfly")) { arity(args, 0, 0, "/claimfly"); plugin.flight().toggle(player(sender), 0); return true; }
            if (name.equals("flycreditinfo")) { arity(args, 0, 1, "/flycreditinfo [nome|UUID]"); info(sender, args.length == 0 ? null : args[0]); return true; }
            if (name.equals("flycreditadd") || name.equals("flycreditset")) {
                if (args.length != 2) throw new CreditException("Uso: /" + name + " <nome|UUID> <minuti>");
                change(sender, args[0], args[1], name.equals("flycreditset")); return true;
            }
            if (args.length == 0) { info(sender, null); help(sender); return true; }
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.matches("[0-9]+[mM]?")) {
                if (args.length != 1) throw new CreditException("Uso: /flycredit <minuti>");
                if (plugin.flight().active(player(sender).getUniqueId())) throw new CreditException("Una sessione è già attiva. Usa /claimfly per terminarla prima di prenotare altro credito.");
                plugin.flight().start(player(sender), Amounts.minutes(action, false)); return true;
            }
            switch (action) {
                case "help", "aiuto" -> help(sender);
                case "info", "saldo" -> { arity(args, 1, 2, "/flycredit info [nome|UUID]"); info(sender, args.length > 1 ? args[1] : null); }
                case "on", "vola" -> {
                    arity(args, 1, 2, "/flycredit on [minuti]");
                    Player p = player(sender);
                    plugin.flight().start(p, args.length > 1 ? Amounts.minutes(args[1], false) : 0);
                }
                case "off", "stop" -> { arity(args, 1, 1, "/flycredit off"); plugin.flight().stop(player(sender), "Volo disattivato: residuo restituito."); }
                case "give", "cedi", "send" -> {
                    if (args.length != 3) throw new CreditException("Uso: /flycredit give <giocatore online> <minuti>");
                    give(player(sender), args[1], Amounts.minutes(args[2], false));
                }
                case "add", "set" -> {
                    if (args.length != 3) throw new CreditException("Uso: /flycredit " + action + " <nome|UUID> <minuti>");
                    change(sender, args[1], args[2], action.equals("set"));
                }
                case "import" -> {
                    arity(args, 1, 2, "/flycredit import [confirm]");
                    if (args.length == 2 && !args[1].equalsIgnoreCase("confirm")) throw new CreditException("Uso: /flycredit import [confirm]");
                    legacyImport(sender, args.length == 2);
                }
                case "diagnostics" -> { arity(args, 1, 1, "/flycredit diagnostics"); diagnostics(sender); }
                default -> help(sender);
            }
        } catch (CreditException ex) { plugin.message(sender, ex.getMessage()); }
        catch (Exception | LinkageError ex) { plugin.report(sender, ex); }
        return true;
    }
    private void arity(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) throw new CreditException("Uso: " + usage);
    }
    private void help(CommandSender sender) {
        plugin.message(sender, "Proprietario del plugin: " + String.join(", ", plugin.getPluginMeta().getAuthors()));
        plugin.message(sender, "/claimfly — attiva/disattiva | /flycredit <minuti> — prenota una sessione");
        plugin.message(sender, "/flycredit info — saldo | /flycredit give <giocatore> <minuti> — cedi credito");
        if (sender.hasPermission("flycredit.admin")) plugin.message(sender, "/flycredit add|set <nome|UUID> <minuti> | info <nome|UUID> | diagnostics | import");
    }
    private CompletableFuture<UUID> resolve(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equalsIgnoreCase(text)) return CompletableFuture.completedFuture(id);
        } catch (IllegalArgumentException ignored) {}
        Player p = Bukkit.getPlayerExact(text);
        if (p != null) return CompletableFuture.completedFuture(p.getUniqueId());
        // Cache only; never trigger a blocking Mojang lookup on the server thread.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(text);
        if (cached != null) return CompletableFuture.completedFuture(cached.getUniqueId());
        return plugin.database().submit(s -> s.findName(text).orElseThrow(() -> new CreditException("Giocatore sconosciuto: usa il suo UUID oppure attendi un accesso.")));
    }
    private void info(CommandSender sender, String target) {
        if (target == null && !(sender instanceof Player)) throw new CreditException("Uso console: /flycredit info <nome|UUID>");
        if (target != null) admin(sender);
        CompletableFuture<UUID> id = target == null ? CompletableFuture.completedFuture(player(sender).getUniqueId()) : resolve(target);
        id.thenCompose(uuid -> plugin.database().submit(s -> s.account(uuid))).whenComplete((a, ex) -> plugin.onMain(() -> {
            if (ex != null) { plugin.report(sender, ex); return; }
            long reserved = plugin.flight().active(a.uuid()) ? plugin.flight().remaining(a.uuid()) : a.reserved();
            plugin.message(sender, (a.name() == null ? a.uuid() : a.name()) + " | Disponibile: " + Amounts.format(a.available()) + " | In sessione: " + Amounts.format(reserved));
            plugin.message(sender, "Volo consumato registrato dal nuovo plugin: " + Amounts.format(a.used()) + " | Ceduto: " + Amounts.format(a.sent()) + " | Ricevuto: " + Amounts.format(a.received()));
            Player online = Bukkit.getPlayer(a.uuid());
            Long live = online == null ? null : plugin.playtime().playtimeTicks(online);
            Long ticks = live != null ? live : a.playtimeTicks();
            if (ticks != null) plugin.message(sender, "Tempo di gioco PTM: " + formatTicks(ticks) + (live != null ? " (attuale)." : " (ultimo snapshot: " + java.time.Instant.ofEpochMilli(a.playtimeSnapshotAt()) + ")."));
            else plugin.message(sender, "Tempo di gioco PTM non ancora disponibile.");
        }));
    }
    private String formatTicks(long ticks) { return Amounts.format(Math.min(ticks, Long.MAX_VALUE / 50) * 50); }
    private void change(CommandSender sender, String target, String text, boolean set) {
        admin(sender);
        long millis = Amounts.minutes(text, set);
        String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
        resolve(target).thenCompose(id -> plugin.database().submit(s -> s.change(id, millis, set, actor)))
                .whenComplete((a, ex) -> plugin.onMain(() -> {
                    if (ex != null) { plugin.report(sender, ex); return; }
                    plugin.message(sender, "Saldo disponibile di " + target + ": " + Amounts.format(a.available()) + ".");
                    Player recipient = Bukkit.getPlayer(a.uuid());
                    if (recipient != null && recipient != sender) plugin.message(recipient, "Il tuo saldo disponibile è ora " + Amounts.format(a.available()) + ".");
                }));
    }
    private void give(Player from, String targetName, long millis) {
        if (!from.hasPermission("flycredit.pay")) throw new CreditException("Non hai il permesso flycredit.pay.");
        Set<String> groups = plugin.playtime().recipientGroups();
        if (groups.isEmpty()) throw new CreditException("I trasferimenti di flycredit sono disattivati su questo server.");
        Player to = plugin.getServer().getPlayerExact(targetName);
        if (to == null || !from.canSee(to)) throw new CreditException("Il destinatario deve essere online e visibile.");
        if (from.getUniqueId().equals(to.getUniqueId())) throw new CreditException("Non puoi cedere credito a te stesso.");
        if (!plugin.playtime().canReceive(to)) throw new CreditException("Il destinatario deve appartenere a uno dei gruppi abilitati: " + groups);
        UUID sender = from.getUniqueId(), recipient = to.getUniqueId();
        if (!pendingGifts.add(sender)) throw new CreditException("Trasferimento già in corso.");
        // The rank assigned by PTM is evaluated through LuckPerms at command acceptance.
        plugin.database().submit(s -> { s.transfer(sender, recipient, millis); return null; })
                .whenComplete((unused, ex) -> plugin.onMain(() -> {
                    pendingGifts.remove(sender);
                    if (ex != null) { plugin.report(from, ex); return; }
                    if (from.isOnline()) plugin.message(from, "Ceduti " + Amounts.format(millis) + " a " + to.getName() + ".");
                    if (to.isOnline()) plugin.message(to, "Hai ricevuto " + Amounts.format(millis) + " da " + from.getName() + ".");
                }));
    }
    private void legacyImport(CommandSender sender, boolean confirm) {
        admin(sender);
        if (!(sender instanceof ConsoleCommandSender)) throw new CreditException("Importazione disponibile solo dalla console.");
        Path path = plugin.getDataFolder().toPath().resolve("import/variables.csv");
        // I/O and parsing run on the database worker, without blocking the server thread.
        plugin.database().submit(store -> {
            if (!Files.isRegularFile(path)) throw new CreditException("Inserisci variables.csv (max 10 MB) in plugins/FlyCredit/import/.");
            try (var input = Files.newInputStream(path)) { return LegacyImport.read(input.readNBytes(LegacyImport.MAX_BYTES + 1)); }
            catch (java.io.IOException ex) { throw new CreditException("Importazione rifiutata: " + ex.getMessage()); }
        }).whenComplete((data, error) -> plugin.onMain(() -> {
            if (error != null) { plugin.report(sender, error); return; }
            if (!confirm) {
                pendingImportHash = data.sha256(); importPreviewAt = System.nanoTime();
                plugin.message(sender, "Anteprima: " + data.balances().size() + " saldi, " + Amounts.format(data.totalMillis()) + " totali. Righe estranee ignorate: " + data.ignoredRows());
                plugin.message(sender, "Gli UUID già presenti non saranno modificati. Per applicare entro 5 minuti: /flycredit import confirm");
                return;
            }
            if (!data.sha256().equals(pendingImportHash) || System.nanoTime() - importPreviewAt > 300_000_000_000L) {
                plugin.message(sender, "Esegui prima /flycredit import per una nuova anteprima del file corrente."); return;
            }
            pendingImportHash = null;
            plugin.database().submit(s -> s.importLegacy(data, false)).whenComplete((result, ex) -> plugin.onMain(() -> {
                if (ex != null) { plugin.report(sender, ex); return; }
                plugin.message(sender, "Importati: " + result.imported() + "; ignorati: " + result.skipped() + "; file già importato: " + result.alreadyImported());
            }));
        }));
    }
    private void diagnostics(CommandSender sender) {
        admin(sender);
        plugin.message(sender, Bukkit.getVersion() + " | Java " + System.getProperty("java.version"));
        for (String name : List.of("GriefDefender", "LuckPerms", "PlayTimeManager")) {
            var p = Bukkit.getPluginManager().getPlugin(name);
            plugin.message(sender, name + ": " + (p == null ? "MANCANTE" : p.getPluginMeta().getVersion() + " enabled=" + p.isEnabled()));
        }
        Set<String> groups = plugin.playtime().recipientGroups();
        plugin.message(sender, groups.isEmpty() ? "Trasferimenti disattivati. Configura transfer.allowed-recipient-groups."
                : "Rank riceventi: " + groups + "; modalità: " + plugin.getConfig().getString("transfer.membership"));
        if (sender instanceof Player p) plugin.message(sender, "Puoi ricevere: " + plugin.playtime().canReceive(p));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("flycredit") && args.length == 1) {
            choices.addAll(List.of("info", "on", "off", "give", "help"));
            if (sender.hasPermission("flycredit.admin")) choices.addAll(List.of("add", "set", "import", "diagnostics"));
        } else if (args.length == 2 && Set.of("give", "cedi", "send", "info", "add", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().stream().filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p)).map(Player::getName).forEach(choices::add);
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
