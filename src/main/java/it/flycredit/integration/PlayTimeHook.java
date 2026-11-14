package it.flycredit.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

/** Public PTM methods verified against TheGaBr0/PlayTimeManager 3.6.6. No database writes to PTM. */
public final class PlayTimeHook {
    private final Plugin ptm;
    private final LuckPerms luckPerms;
    private final Set<String> recipientGroups;
    private final boolean inherited;
    private Object onlineUsers;
    private Method getUser;
    private Method getPlaytime;
    private final Logger logger;
    private boolean warned;

    public PlayTimeHook(Plugin ptm, Set<String> groups, boolean inherited, Logger logger) throws ReflectiveOperationException {
        this.ptm = Objects.requireNonNull(ptm, "PlayTimeManager mancante");
        this.logger = logger;
        recipientGroups = Set.copyOf(groups);
        this.inherited = inherited;
        // Stable public LuckPerms API; PTM continues to assign and revoke its rank groups.
        luckPerms = LuckPermsProvider.get();
        try {
            ClassLoader loader = ptm.getClass().getClassLoader();
            Class<?> manager = Class.forName("me.thegabro.playtimemanager.Users.OnlineUsersManager", true, loader);
            onlineUsers = manager.getMethod("getInstance").invoke(null);
            getUser = manager.getMethod("getOnlineUserByUUID", String.class);
            getPlaytime = Class.forName("me.thegabro.playtimemanager.Users.OnlineUser", true, loader).getMethod("getPlaytime");
        } catch (ReflectiveOperationException | LinkageError ex) {
            logger.warning("Lettura ore PTM non disponibile con questa versione; controllo rank comunque attivo.");
        }
    }
    public boolean canReceive(Player player) {
        if (!ptm.isEnabled()) return false;
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return false;
        return allowed(user, recipientGroups, inherited);
    }
    static boolean allowed(User user, Set<String> recipientGroups, boolean inherited) {
        var options = user.getQueryOptions();
        Set<String> effective = new HashSet<>();
        user.getInheritedGroups(options).forEach(g -> effective.add(g.getName().toLowerCase(Locale.ROOT)));
        if (inherited) return effective.stream().anyMatch(recipientGroups::contains);
        return user.getNodes(NodeType.INHERITANCE).stream()
                .filter(n -> n.getValue() && !n.hasExpired())
                .filter(n -> n.getContexts().isSatisfiedBy(options.context()))
                .anyMatch(n -> recipientGroups.contains(n.getGroupName().toLowerCase(Locale.ROOT))
                        && effective.contains(n.getGroupName().toLowerCase(Locale.ROOT)));
    }
    /** Must be called on the server thread: PTM reads player statistics. */
    public Long playtimeTicks(Player player) {
        if (!ptm.isEnabled() || getPlaytime == null || onlineUsers == null) return null;
        try {
            Object user = getUser.invoke(onlineUsers, player.getUniqueId().toString());
            return user == null ? null : Math.max(0, ((Number) getPlaytime.invoke(user)).longValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (!warned) { warned = true; logger.warning("Impossibile leggere lo snapshot delle ore PTM: " + ex.getMessage()); }
            return null;
        }
    }
}
